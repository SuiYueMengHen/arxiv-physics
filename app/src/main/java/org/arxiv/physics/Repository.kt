package org.arxiv.physics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class Repository(private val context: Context, val store: Store) {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val apiMutex = Mutex()
    private val pdfMutex = Mutex()
    private var lastRequest = 0L
    fun pdf(p: Paper) = File(context.filesDir, "papers/${p.fileKey}.pdf")
    fun markdown(p: Paper) = File(context.filesDir, "translations/${p.fileKey}.md")

    suspend fun feed(query: String, offset: Int = 0, ascending: Boolean = false, refresh: Boolean = false): Feed = withContext(Dispatchers.IO) {
        val key = "$query|$offset|$ascending"
        apiMutex.withLock {
            val cached = store.feed(key)
            if (!refresh && cached != null && System.currentTimeMillis() - cached.fetched < 15 * 60_000) return@withLock cached
            try {
                // arXiv asks for at least three seconds between sequential API requests.
                delay((3100 - (android.os.SystemClock.elapsedRealtime() - lastRequest)).coerceAtLeast(0))
                val url = "https://export.arxiv.org/api/query".toHttpUrl().newBuilder()
                    .addQueryParameter("search_query", query).addQueryParameter("start", offset.toString())
                    .addQueryParameter("max_results", "30").addQueryParameter("sortBy", "submittedDate")
                    .addQueryParameter("sortOrder", if (ascending) "ascending" else "descending").build()
                lastRequest = android.os.SystemClock.elapsedRealtime()
                client.newCall(Request.Builder().url(url).header("User-Agent", "ArxivPhysics/0.1 (Android; personal research reader)").build()).execute().use { response ->
                    check(response.isSuccessful) { "arXiv 请求失败 HTTP ${response.code}，请稍后重试" }
                    val body = checkNotNull(response.body)
                    val (papers, total) = AtomParser.parse(body.byteStream())
                    Feed(papers, total, System.currentTimeMillis()).also { store.putFeed(key, it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (cached != null) cached.copy(stale = true) else throw e
            }
        }
    }
    suspend fun download(p: Paper, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        pdfMutex.withLock {
            val target = pdf(p)
            if (target.exists()) return@withLock target
            target.parentFile!!.mkdirs()
            val part = File(target.path + ".part")
            try {
                client.newCall(Request.Builder().url(p.pdfUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "PDF 下载失败 HTTP ${response.code}" }
                    val body = checkNotNull(response.body)
                    val total = body.contentLength()
                    require(total <= 64 * 1024 * 1024) { "PDF 超过 64 MB 下载限制" }
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var loaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            loaded += count
                            require(loaded <= 64 * 1024 * 1024) { "PDF 超过 64 MB 下载限制" }
                            output.write(buffer, 0, count)
                            progress(if (total > 0) (loaded * 100 / total).toInt() else -1)
                        }
                        if (total > 0) check(loaded == total) { "PDF 下载不完整，请重试" }
                    } }
                }
                val header = ByteArray(5)
                part.inputStream().use { check(it.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") { "服务器未返回 PDF 文件" } }
                check(part.renameTo(target)) { "无法保存 PDF" }
                progress(100)
                target
            } finally { part.delete() }
        }
    }
    suspend fun saveMarkdown(p: Paper, text: String) = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "没有可保存的译文" }
        val target = markdown(p)
        target.parentFile!!.mkdirs()
        val temp = File(target.path + ".part")
        temp.writeText(text)
        check(temp.renameTo(target)) { "无法保存译文" }
    }
}
