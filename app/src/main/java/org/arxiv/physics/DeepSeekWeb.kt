package org.arxiv.physics

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.*
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/** Runs only while the in-app web page is visible. Prepares one attachment and prompt; sending and copying remain manual. */
class DeepSeekWeb(private val app: ArxivApp) {
    private val context = MutableContextWrapper(app)
    var paper by mutableStateOf<Paper?>(null)
        private set
    var status by mutableStateOf("请在网页中登录 DeepSeek")
        private set
    var savedId by mutableStateOf<String?>(null)
        private set
    private var file: File? = null
    private var chooserProvided = false
    private var prepared = false
    private var pageRestricted = false
    private val controlsScript by lazy { app.assets.open("deepseek-controls.js").bufferedReader().use { it.readText() } }
    private var session = 0
    private var attachmentAttempts = 0
    private var lastAttachmentAttempt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Keep the normal Android WebView identity and the site's own mobile viewport.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || !(uri.host == "deepseek.com" || uri.host.orEmpty().endsWith(".deepseek.com"))
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { pageRestricted = false }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode in setOf(403, 429)) { pageRestricted = true; status = "网站限制访问，请手动处理；应用不会自动重试" }
            }
            override fun onPageFinished(view: WebView, url: String) { CookieManager.getInstance().flush() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status = "网页加载失败，可从菜单重新加载：${error.description}"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val attachment = file
                if (attachment != null && attachment.exists() && trusted() && !chooserProvided) {
                    callback.onReceiveValue(arrayOf(FileProvider.getUriForFile(app, "${app.packageName}.files", attachment)))
                    chooserProvided = true
                    status = "PDF 已交给网页，请确认解析完成后手动发送；手动复制回答即可保存"
                } else callback.onReceiveValue(null)
                return true
            }
        }
        loadUrl(HOME)
    }
    private fun trusted() = Uri.parse(view.url ?: "").let { it.scheme == "https" && it.host == "chat.deepseek.com" }
    fun attachContext(host: Context) { context.baseContext = host; view.onResume() }
    fun detachContext() { view.onPause(); context.baseContext = app; CookieManager.getInstance().flush() }
    fun prepare(p: Paper, pdf: File) {
        require(pdf.exists()) { "请先下载 PDF" }
        session++
        app.recordDownloaded(p)
        paper = p; file = pdf; prepared = false; chooserProvided = false
        savedId = null
        attachmentAttempts = 0; lastAttachmentAttempt = 0
        status = "登录后准备 PDF 和全文翻译指令，请手动发送、手动复制回答；超过 500 字符自动保存并预览"
        // A fresh conversation prevents attaching a paper to a previous translation.
        view.loadUrl(HOME)
    }
    fun retryAttachment() {
        chooserProvided = false; prepared = false; session++
        attachmentAttempts = 0; lastAttachmentAttempt = 0
        status = "重新准备附件；已有输入和对话不会被覆盖"
    }
    fun reload() { view.reload() }
    fun logout() {
        session++; paper = null; file = null; prepared = false
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); view.loadUrl(HOME) }
        WebStorage.getInstance().deleteAllData()
        view.clearCache(true)
    }
    internal suspend fun evaluate(script: String): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume((runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: result).orEmpty())
        }
    }
    private suspend fun state() = JSONObject(evaluate("""
        (()=>JSON.stringify({ready:!!document.querySelector('textarea'),
          input:document.querySelector('textarea')?.value || '',
          hasAnswer:!!document.querySelector('.ds-markdown,[data-message-role="assistant"]'),
          restricted:!!document.querySelector('iframe[src*="captcha"],iframe[src*="challenge"]') ||
            /访问频繁|异常请求|请完成验证|Access denied|Too many requests/i.test(document.body.innerText.slice(-4000))}))()
    """.trimIndent()))
    fun showStatus(message: String) { status = message }

    suspend fun monitor() {
        val run = session
        var checks = 0
        while (run == session && checks++ < 60) {
            if (prepared && chooserProvided) return // No DOM polling during translation.
            delay(1500)
            val p = paper ?: return
            try {
                if (pageRestricted) return
                if (!trusted()) continue
                val state = state()
                if (run != session) return
                if (state.optBoolean("restricted")) {
                    status = "网页要求验证或限制访问，请手动处理；附件准备已暂停"
                    return
                }
                if (!state.optBoolean("ready")) continue
                if (state.optBoolean("hasAnswer")) {
                    status = "已有对话，附件准备暂停；请从论文详情重新打开新对话"
                    return
                }
                if (!prepared) {
                    if (state.optString("input").isNotBlank() && state.optString("input") != TranslationProtocol.prompt(p)) {
                        status = "输入框已有内容，请先确认草稿，再从菜单重新准备附件与指令"
                        return
                    }
                    if (state.optString("input").isBlank()) fill(TranslationProtocol.prompt(p))
                    prepared = true
                }
                if (!chooserProvided && attachmentAttempts < 3) requestAttachment()
                if (chooserProvided) { status = "附件与指令已准备，请确认解析完成后手动发送；复制回答后自动保存"; return }
                if (attachmentAttempts >= 3) {
                    status = "自动准备已暂停，请手动点附件按钮，或从菜单重试"
                    return
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status = "网页准备失败：${e.message}"; return }
        }
        if (!chooserProvided) status = "网页等待已暂停，登录后可从菜单重新准备附件与指令"
    }
    suspend fun prepareContinuation() {
        val p = paper ?: return
        if (!trusted()) return
        val state = state()
        if (state.optBoolean("restricted") || !state.optBoolean("ready") || state.optString("input").isNotBlank()) {
            status = "请先处理网页验证或现有输入，续译指令未填入"; return
        }
        fill(TranslationProtocol.continuation(p))
        status = "续译指令已填入，请手动发送；多段译文请合并后复制保存"
    }
    private suspend fun fill(text: String) {
        evaluate("""
            (()=>{
              const el=document.querySelector('textarea');if(!el)return;
              Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(el,${JSONObject.quote(text)});
              el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
            })()
        """.trimIndent())
    }
    private suspend fun requestAttachment() {
        if (chooserProvided) return
        if (attachmentAttempts >= 3 || System.currentTimeMillis() - lastAttachmentAttempt < 5000) return
        attachmentAttempts++; lastAttachmentAttempt = System.currentTimeMillis()
        if (!tapControl("upload")) status = "尚未找到网页附件入口，请确认已登录，或从菜单重新准备附件"
    }
    /** A DOM click alone lacks the user activation required by Chromium's file picker. */
    private suspend fun tapControl(kind: String): Boolean {
        val raw = evaluate(controlsScript.replace("__ARXIV_ACTION__", JSONObject.quote(kind)))
        val point = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        if (!point.optBoolean("found")) return false
        val scale = view.width.toFloat() / point.optDouble("width", 1.0).toFloat()
        val x = point.optDouble("x").toFloat() * scale
        val y = point.optDouble("y").toFloat() * scale
        if (x !in 0f..view.width.toFloat() || y !in 0f..view.height.toFloat()) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        try { view.dispatchTouchEvent(down) } finally { down.recycle() }
        delay(80)
        val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try { view.dispatchTouchEvent(up) } finally { up.recycle() }
        return true
    }
    companion object { const val HOME = "https://chat.deepseek.com/" }
}
