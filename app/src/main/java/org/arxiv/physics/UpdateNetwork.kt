package org.arxiv.physics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small metadata calls have a total deadline, including DNS, redirects and body reads. */
internal class UpdateNetwork(timeoutMillis: Long = 8_000) {
    private val client = OkHttpClient.Builder().connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    suspend fun manifest(url: String): String {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "ArxivPhysics/${BuildConfig.VERSION_NAME}").build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        return withContext(Dispatchers.IO) {
            response.use {
                check(it.isSuccessful) { "更新源 HTTP ${it.code}" }
                val output = java.io.ByteArrayOutputStream()
                checkNotNull(it.body).byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        check(output.size() + n <= 256 * 1024) { "更新清单过大" }
                        output.write(buffer, 0, n)
                    }
                }
                output.toString("UTF-8")
            }
        }
    }
}
