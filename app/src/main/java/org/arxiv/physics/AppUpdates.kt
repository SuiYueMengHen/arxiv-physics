package org.arxiv.physics

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdatePolicy {
    const val REPO = "SuiYueMengHen/arxiv-physics"
    const val MAX_APK = 128L * 1024 * 1024
    fun newer(candidate: Long, installed: Long) = candidate > installed
    fun releaseAsset(url: String) = url.startsWith("https://github.com/$REPO/releases/download/") &&
        !url.contains("..") && !url.contains('?') && !url.contains('#')
    fun validHash(hash: String) = Regex("[a-f0-9]{64}").matches(hash)
}

data class AppRelease(val versionCode: Long, val versionName: String, val apkName: String,
    val url: String, val sha256: String, val size: Long, val notes: String) {
    fun json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("apkName", apkName).put("url", url).put("sha256", sha256).put("size", size).put("notes", notes)
    companion object {
        fun parse(json: JSONObject) = AppRelease(json.getLong("versionCode"), json.getString("versionName"),
            json.getString("apkName"), json.getString("url"), json.getString("sha256"), json.getLong("size"), json.optString("notes"))
    }
}

/** Requests only on explicit user action. Ready APKs survive process restarts. */
class AppUpdates(private val app: ArxivApp) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val folder get() = File(app.filesDir, "updates").apply { mkdirs() }
    private val pending get() = File(folder, "pending.json")
    var release by mutableStateOf<AppRelease?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var ready by mutableStateOf(false)
        private set
    var message by mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}，点击检查更新")
        private set
    fun notice(text: String) { message = text }
    private fun apk(info: AppRelease) = File(folder, "arxiv-${info.versionCode}.apk")
    private fun request(url: String) = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("User-Agent", "ArxivPhysics/${BuildConfig.VERSION_NAME}").build())
    private fun readJson(url: String): JSONObject = request(url).execute().use { response ->
        if (response.code == 403 || response.code == 429) error("GitHub 暂时限制请求，请稍后再检查")
        check(response.isSuccessful) { "检查更新失败 HTTP ${response.code}" }
        val body = checkNotNull(response.body)
        body.byteStream().use { input ->
            val bytes = input.readBytesLimited(256 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    suspend fun restore() {
        if (busy || release != null) return
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                if (!pending.exists()) return@runCatching null
                val info = AppRelease.parse(JSONObject(pending.readText()))
                if (!UpdatePolicy.newer(info.versionCode, BuildConfig.VERSION_CODE.toLong())) {
                    folder.listFiles()?.forEach { it.delete() }; return@runCatching null
                }
                verify(info, apk(info)); info
            }.getOrNull()
        }
        if (!busy && release == null && restored != null) {
            release = restored; ready = true; message = "${restored.versionName} 已下载并校验，可以安装"
        }
    }
    fun check() {
        if (busy) return
        busy = true; message = "正在检查 GitHub 最新正式版本"
        app.scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val remote = readJson("https://api.github.com/repos/${UpdatePolicy.REPO}/releases/latest")
                    check(!remote.optBoolean("draft") && !remote.optBoolean("prerelease")) { "没有可用正式版本" }
                    val assets = remote.getJSONArray("assets")
                    fun asset(name: String): JSONObject? = (0 until assets.length()).map { assets.getJSONObject(it) }.find { it.optString("name") == name }
                    val manifestUrl = checkNotNull(asset("update.json")) { "此版本没有更新清单" }.getString("browser_download_url")
                    check(UpdatePolicy.releaseAsset(manifestUrl)) { "更新清单地址不匹配" }
                    val manifest = readJson(manifestUrl)
                    val binary = checkNotNull(asset(manifest.getString("apkName"))) { "此版本没有安装包" }
                    val result = AppRelease(manifest.getLong("versionCode"), manifest.getString("versionName"), manifest.getString("apkName"),
                        binary.getString("browser_download_url"), manifest.getString("sha256"), manifest.getLong("size"), remote.optString("body").take(8000))
                    check(remote.getString("tag_name") == "v${result.versionName}") { "版本清单与 Release 不一致" }
                    check(result.size == binary.getLong("size")) { "安装包大小与清单不一致" }
                    validateInfo(result)
                    val digest = binary.optString("digest")
                    check(digest.isBlank() || digest == "sha256:${result.sha256}") { "安装包校验信息不一致" }
                    result
                }
                if (UpdatePolicy.newer(info.versionCode, BuildConfig.VERSION_CODE.toLong())) {
                    val verified = withContext(Dispatchers.IO) { runCatching { verify(info, apk(info)) }.isSuccess }
                    release = info; ready = verified
                    message = if (verified) "${info.versionName} 已下载并校验，可以安装" else "发现新版本 ${info.versionName}"
                } else { release = null; ready = false; message = "已是最新版本 ${BuildConfig.VERSION_NAME}" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "检查失败，请重试" }
            finally { busy = false }
        }
    }
    fun download(onReady: () -> Unit) {
        val info = release ?: return
        if (busy) return
        busy = true; ready = false; progress = 0f; message = "正在下载 ${info.versionName}"
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    validateInfo(info)
                    val temp = File(folder, "arxiv-${info.versionCode}.part.apk")
                    try {
                        request(info.url).execute().use { response ->
                            check(response.isSuccessful) { "下载更新失败 HTTP ${response.code}" }
                            val body = checkNotNull(response.body)
                            check(body.contentLength() == -1L || body.contentLength() == info.size) { "下载大小异常" }
                            body.byteStream().use { input -> temp.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024); var loaded = 0L; var reported = -1
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer); if (count < 0) break
                                    loaded += count; check(loaded <= info.size) { "下载内容超出清单大小" }
                                    output.write(buffer, 0, count)
                                    val percent = (loaded * 100 / info.size).toInt()
                                    if (percent != reported) { reported = percent; withContext(Dispatchers.Main) { progress = percent / 100f } }
                                }
                                check(loaded == info.size) { "更新下载不完整，请重试" }
                            } }
                        }
                        verify(info, temp)
                        check(temp.renameTo(apk(info))) { "更新包保存失败" }
                        val tempManifest = File(pending.path + ".part").apply { writeText(info.json().toString()) }
                        check(tempManifest.renameTo(pending)) { "更新清单保存失败" }
                    } finally { temp.delete() }
                }
                ready = true; message = "已完成校验，准备打开系统安装界面"
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "下载失败，请重试" }
            finally { busy = false }
        }
    }
    internal fun validateInfo(info: AppRelease) {
        require(info.versionCode > 0 && info.size in 1..UpdatePolicy.MAX_APK && UpdatePolicy.validHash(info.sha256) && UpdatePolicy.releaseAsset(info.url)) { "更新清单无效" }
    }
    internal fun verify(info: AppRelease, file: File) {
        validateInfo(info)
        check(file.exists() && file.length() == info.size) { "更新包大小不符" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == info.sha256) { "更新包 SHA-256 不符，请重新下载" }
        @Suppress("DEPRECATION") val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION") val archive = checkNotNull(app.packageManager.getPackageArchiveInfo(file.path, flags)) { "更新包不是有效 APK" }
        @Suppress("DEPRECATION") val current = app.packageManager.getPackageInfo(app.packageName, flags)
        @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(archive.packageName == app.packageName && version == info.versionCode && archive.versionName == info.versionName) { "更新包包名或版本不匹配" }
        check(UpdatePolicy.newer(version, BuildConfig.VERSION_CODE.toLong())) { "更新包版本不比当前版本新" }
        check(signers(archive) == signers(current) && signers(current).isNotEmpty()) { "更新包签名与当前应用不匹配" }
    }
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
    suspend fun installIntent(): Intent {
        val info = checkNotNull(release) { "没有可安装版本" }
        withContext(Dispatchers.IO) { verify(info, apk(info)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk(info))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新 APK", uri) }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; check(output.size() + n <= limit) { "更新信息过大" }; output.write(buffer, 0, n) }
        return output.toByteArray()
    }
}
