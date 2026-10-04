package org.arxiv.physics

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Exercises Android-specific XML, SQLite and the actual offline WebView renderer. */
class SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = mutableListOf<String>()
        val tests: List<Pair<String, () -> Unit>> = listOf("Android Atom parser" to ::atom, "SQLite cache and bookmark" to ::database, "Offline Markdown and formulas" to ::reader, "Clipboard update imports paper Markdown" to ::clipboardImport, "PDF upload and prompt without automatic send" to ::uploadAndSend, "Theme preference persists" to ::themePreference, "Adaptive launcher icon renders" to ::icon)
        tests.forEach { (name, run) ->
            runCatching(run).onSuccess { results.add("PASS $name") }.onFailure { results.add("FAIL $name: $it") }
        }
        val failed = results.any { it.startsWith("FAIL") }
        finish(if (failed) Activity.RESULT_CANCELED else Activity.RESULT_OK, Bundle().apply {
            putString("stream", "\n" + results.joinToString("\n") + "\n")
            putString("result", if (failed) "FAILED" else "PASSED")
        })
    }
    private fun atom() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns:o="http://a9.com/-/spec/opensearch/1.1/"><o:totalResults>1</o:totalResults><entry><id>https://arxiv.org/abs/2501.12345v1</id><title>Android parser</title><author><name>Alice</name></author><category term="quant-ph"/><arxiv:primary_category term="quant-ph"/></entry></feed>"""
        val (papers, total) = AtomParser.parse(xml.byteInputStream())
        check(total == 1 && papers.single().primary == "quant-ph")
        check(runCatching { AtomParser.parse("<!DOCTYPE feed><feed/>".byteInputStream()) }.isFailure)
    }
    private fun database() {
        targetContext.deleteDatabase("arxiv-smoke.db")
        val store = Store(targetContext, "arxiv-smoke.db")
        try {
            val p = Paper("2501.12345v1", "Original", listOf("Alice"), "Summary", "2025-01-01", "2025-01-01", listOf("quant-ph"), "quant-ph")
            store.putFeed("query", Feed(listOf(p), 1, 123L))
            store.save(p.id, true)
            store.putFeed("query", Feed(listOf(p.copy(title = "Updated")), 1, 456L))
            check(store.saved(p.id)) { "Refresh lost bookmark" }
            check(store.library().single().title == "Updated")
            check(store.feed("query")!!.fetched == 456L)
            store.job(TranslationJob(p.id, Stage.PARTIAL, -1, "Incomplete"))
            check(store.jobs().single().stage == Stage.PARTIAL)
        } finally { store.close(); targetContext.deleteDatabase("arxiv-smoke.db") }
    }
    private fun reader() {
        lateinit var view: WebView
        runOnMainSync {
            view = createMarkdownView(targetContext)
            updateMarkdown(view, """
                # 量子物理
                行内公式 ${'$'}E=mc^2${'$'}。

                ${'$'}${'$'}\int_0^1 x^2 dx=\frac{1}{3}${'$'}${'$'}

                | 参数 | 数值 |
                | --- | --- |
                | 能量 | 1 |

                <img src="https://example.com/tracker" onerror="window.compromised=true">
                <script>window.compromised=true</script>
            """.trimIndent(), false)
        }
        try {
            var state = JSONObject()
            for (attempt in 0 until 40) {
                Thread.sleep(250)
                val latch = CountDownLatch(1)
                runOnMainSync {
                    view.evaluateJavascript("JSON.stringify({math:document.querySelectorAll('.katex').length,table:document.querySelectorAll('table').length,images:document.querySelectorAll('img').length,bad:!!window.compromised,title:document.querySelector('h1')?.textContent})") { raw ->
                        runCatching { state = JSONObject(JSONTokener(raw).nextValue() as String) }
                        latch.countDown()
                    }
                }
                check(latch.await(2, TimeUnit.SECONDS))
                if (state.optInt("math") == 2) break
            }
            check(state.optInt("math") == 2) { "Offline math did not render: $state" }
            check(state.optInt("table") == 1 && state.optString("title") == "量子物理") { "Markdown structure missing: $state" }
            check(state.optInt("images") == 0 && !state.optBoolean("bad")) { "Unsanitized HTML: $state" }
        } finally { runOnMainSync { view.destroy() } }
    }
    private fun clipboardImport() = runBlocking {
        val app = targetContext.applicationContext as ArxivApp
        val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val originalStore = app.store
        val originalRepository = app.repository
        val testStore = Store(targetContext, "clipboard-smoke.db")
        val p = Paper("9999.99999v1", "Clipboard fixture", listOf("Test"), "", "2026-01-01", "", listOf("quant-ph"), "quant-ph")
        val other = p.copy(id = "9999.99998v1")
        val markdown = app.repository.markdown(p)
        val otherMarkdown = app.repository.markdown(other)
        val clipboard = targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        var importer: ClipboardTranslationImporter? = null
        var previewId: String? = null
        val raw = "# 剪贴板译文\n\n" + "保留原始公式 ${'$'}E=mc^2${'$'}。\n".repeat(50)
        try {
            withContext(Dispatchers.Main) {
                app.store = testStore; app.repository = Repository(targetContext, testStore)
                testStore.putFeed("fixture", Feed(listOf(p, other), 2, 1))
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("old", "旧内容".repeat(300)))
                importer = ClipboardTranslationImporter(app)
                importer!!.start(p, { previewId = it.id })
            }
            delay(300)
            check(!markdown.exists()) { "Existing clipboard was imported" }
            withContext(Dispatchers.Main) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("short", "字".repeat(500))) }
            delay(300)
            check(!markdown.exists()) { "500 characters should not trigger import" }
            withContext(Dispatchers.Main) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("translation", raw)) }
            withTimeout(8000) { while (withContext(Dispatchers.Main) { previewId } == null) delay(100) }
            check(previewId == p.id) { "Preview is for wrong article" }
            check(markdown.readText() == raw.trim()) { "Clipboard Markdown did not persist exactly" }
            check(!otherMarkdown.exists()) { "Wrong paper was overwritten" }
            withContext(Dispatchers.Main) {
                importer!!.stop()
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("stopped", "其他文本".repeat(300)))
            }
            delay(300)
            check(markdown.readText() == raw.trim()) { "Listener remained active after leaving page" }
        } finally {
            withContext(Dispatchers.Main) {
                importer?.stop(); clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                app.store = originalStore; app.repository = originalRepository; app.reloadJobs()
                activity.finish()
            }
            markdown.delete(); testStore.close(); targetContext.deleteDatabase("clipboard-smoke.db")
        }
    }
    private fun icon() {
        val drawable = targetContext.packageManager.getApplicationIcon(targetContext.packageName)
        check(drawable is android.graphics.drawable.AdaptiveIconDrawable)
        val bitmap = android.graphics.Bitmap.createBitmap(432, 432, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 432, 432)
        drawable.draw(android.graphics.Canvas(bitmap))
        java.io.File(targetContext.cacheDir, "icon-smoke.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        check(android.graphics.Color.alpha(bitmap.getPixel(216, 216)) == 255)
        bitmap.recycle()
    }
    private fun themePreference() {
        val app = targetContext.applicationContext as ArxivApp
        val old = app.themeMode
        try {
            runOnMainSync { app.setTheme(ThemeMode.DARK) }
            check(app.themeMode == ThemeMode.DARK && app.prefs.getString("theme", "") == "DARK")
            runOnMainSync { app.setTheme(ThemeMode.LIGHT) }
            check(app.themeMode == ThemeMode.LIGHT && app.prefs.getString("theme", "") == "LIGHT")
        } finally { runOnMainSync { app.setTheme(old) } }
    }
    private fun uploadAndSend() = runBlocking {
        val app = targetContext.applicationContext as ArxivApp
        val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val p = Paper("9999.99997v1", "Upload fixture", emptyList(), "", "", "", listOf("quant-ph"), "quant-ph")
        val pdf = app.repository.pdf(p).apply { parentFile!!.mkdirs(); writeText("%PDF-1.4\nfixture") }
        val originalStore = app.store
        val testStore = Store(targetContext, "upload-smoke.db")
        val engine = withContext(Dispatchers.Main) { DeepSeekWeb(app) }
        var monitor: Job? = null
        try {
            withContext(Dispatchers.Main) {
                app.store = testStore
                engine.attachContext(activity)
                activity.setContentView(android.widget.FrameLayout(activity).apply { addView(engine.view, android.widget.FrameLayout.LayoutParams(-1, -1)) })
                engine.prepare(p, pdf)
                engine.view.stopLoading()
                engine.view.loadDataWithBaseURL(DeepSeekWeb.HOME, """
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <textarea style="width:90%;height:120px"></textarea>
                  <input type="file" style="display:none" accept="application/pdf">
                  <div id="attachment"></div><div id="progress"></div>
                  <button type="button" aria-label="发送" disabled>发送</button>
                  <script>
                    window.sent=0;window.early=false;window.activated=false;window.bytes='';window.ready=false;
                    const input=document.querySelector('input'); const send=document.querySelector('button');
                    input.onclick=()=>window.activated=navigator.userActivation.isActive;
                    input.onchange=async()=>{
                      const file=input.files[0];document.querySelector('#attachment').textContent=file.name;
                      window.bytes=await file.text();
                      document.querySelector('#progress').innerHTML='<div role="progressbar">解析中</div>';
                      setTimeout(()=>{document.querySelector('#progress').innerHTML='';window.ready=true;send.disabled=false;},1800);
                    };
                    send.onclick=()=>{window.sent++;window.early=!window.ready;window.prompt=document.querySelector('textarea').value;};
                  </script>
                """.trimIndent(), "text/html", "UTF-8", DeepSeekWeb.HOME)
                monitor = app.scope.launch { engine.monitor() }
            }
            var result = JSONObject()
            try { withTimeout(22000) {
                while (true) {
                    delay(300)
                    result = withContext(Dispatchers.Main) { runCatching { JSONObject(engine.evaluate("JSON.stringify({ready:window.ready,sent:window.sent,early:window.early,activated:window.activated,bytes:window.bytes,prompt:document.querySelector('textarea')?.value})")) }.getOrDefault(JSONObject()) }
                    if (result.optBoolean("ready")) break
                }
            } } catch (e: TimeoutCancellationException) {
                val diagnostic = withContext(Dispatchers.Main) { engine.evaluate("JSON.stringify({url:location.href,text:document.body.innerText,input:document.querySelector('textarea')?.value,activation:window.activated,bytes:window.bytes,sent:window.sent,ready:window.ready,rect:document.querySelector('textarea')?.getBoundingClientRect().toJSON(),width:window.innerWidth})") + " STATUS=" + engine.status + " URL=" + engine.view.url + " WIDTH=" + engine.view.width }
                error("Upload timeout: $diagnostic")
            }
            check(result.optBoolean("activated")) { "File picker lacked native user activation: $result" }
            check(result.optString("bytes").startsWith("%PDF-")) { "PDF bytes were not attached: $result" }
            check(result.optString("prompt") == TranslationProtocol.prompt(p)) { "Simple translation prompt missing: $result" }
            withContext(Dispatchers.Main) { engine.detachContext() }
            check(testStore.jobs().single().stage == Stage.DOWNLOADED) { "Leaving without copying created an active translation job" }
            check(testStore.jobs().single().message == "本地文件已下载")
            delay(3500)
            val sent = withContext(Dispatchers.Main) { engine.evaluate("String(window.sent)") }
            check(sent == "0") { "Request was automatically sent: $sent" }
            check(monitor?.isCompleted == true) { "DOM polling continued after attachment preparation" }
        } finally {
            withContext(Dispatchers.Main) {
                monitor?.cancel(); engine.view.stopLoading(); engine.view.destroy(); app.store = originalStore; app.reloadJobs(); activity.finish()
            }
            pdf.delete(); testStore.close(); targetContext.deleteDatabase("upload-smoke.db")
        }
    }

}
