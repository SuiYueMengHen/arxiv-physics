package org.arxiv.physics

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream

@Composable
fun MarkdownReader(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = ::createMarkdownView,
        update = { updateMarkdown(it, text, dark) }, onRelease = { it.destroy() })
}

private data class ReaderDocument(val text: String, val dark: Boolean) {
    val script get() = "window.pendingDocument=${JSONObject.quote(text)};window.readerDark=$dark;if(window.renderDocument)window.renderDocument();"
}

@SuppressLint("SetJavaScriptEnabled")
internal fun createMarkdownView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val uri = request.url
            val path = uri.path.orEmpty().removePrefix("/assets/")
            if (uri.scheme == "https" && uri.host == "reader.local" && uri.path.orEmpty().startsWith("/assets/") && !path.contains("..")) {
                val mime = when {
                    path.endsWith(".js") -> "application/javascript"
                    path.endsWith(".css") -> "text/css"
                    path.endsWith(".woff2") -> "font/woff2"
                    path.endsWith(".woff") -> "font/woff"
                    else -> "application/octet-stream"
                }
                return runCatching { WebResourceResponse(mime, "UTF-8", context.assets.open(path)) }.getOrElse { blocked() }
            }
            return blocked()
        }
        override fun onPageFinished(view: WebView, url: String) {
            (view.tag as? ReaderDocument)?.let { view.evaluateJavascript(it.script, null) }
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.scheme in setOf("https", "http")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }
    }
    loadDataWithBaseURL("https://reader.local/", context.assets.open("reader.html").bufferedReader().use { it.readText() }, "text/html", "UTF-8", null)
}

internal fun updateMarkdown(view: WebView, text: String, dark: Boolean) {
    val doc = ReaderDocument(text, dark)
    if (view.tag == doc) return
    view.tag = doc
    view.setBackgroundColor(if (dark) Color.rgb(18, 19, 24) else Color.rgb(250, 249, 255))
    view.evaluateJavascript(doc.script, null)
}

private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
