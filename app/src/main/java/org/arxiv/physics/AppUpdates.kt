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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FileOutputStream
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
    fun httpsUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    } == true
    fun resumeOffset(code: Int, range: String?, offset: Long, size: Long): Long {
        if (code == 200) return 0
        require(code == 206 && offset > 0 && range == "bytes $offset-${size - 1}/$size") { "服务器续传范围异常" }
        return offset
    }
    fun select(results: List<AppRelease>): AppRelease {
        val newest = results.maxByOrNull { it.versionCode }
            ?: error("更新源暂时无法连接；请换网络重试，或设置国内镜像。已下载的更新仍可安装")
        check(results.filter { it.versionCode == newest.versionCode }.all {
            it.sha256 == newest.sha256 && it.size == newest.size && it.versionName == newest.versionName
        }) { "更新源清单不一致，请稍后重试" }
        // Prefer the configured mirror only when its content matches the newest version.
        return results.firstOrNull { it.versionCode == newest.versionCode && it.mirrorUrl.isNotEmpty() } ?: newest
    }
    fun validHash(hash: String) = Regex("[a-f0-9]{64}").matches(hash)
}

data class AppRelease(val versionCode: Long, val versionName: String, val apkName: String,
    val url: String, val sha256: String, val size: Long, val notes: String, val mirrorUrl: String = "") {
    fun json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("apkName", apkName).put("url", url).put("sha256", sha256).put("size", size).put("notes", notes).put("mirrorUrl", mirrorUrl)
    companion object {
        fun parse(json: JSONObject) = AppRelease(json.getLong("versionCode"), json.getString("versionName"),
            json.getString("apkName"), json.getString("url"), json.getString("sha256"), json.getLong("size"), json.optString("notes"), json.optString("mirrorUrl"))
    }
}

/** Requests only on explicit user action. Ready APKs survive process restarts. */
class AppUpdates(private val app: ArxivApp) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
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
    private val network = UpdateNetwork()
    var mirror by mutableStateOf(app.prefs.getString("updateMirror", "").orEmpty())
        private set
    fun saveMirror(value: String) {
        val url = value.trim()
        require(url.isEmpty() || UpdatePolicy.httpsUrl(url)) { "请输入 HTTPS 更新清单地址" }
        mirror = url; app.prefs.edit().putString("updateMirror", url).apply()
        notice(if (url.isEmpty()) "已使用默认更新源" else "镜像已保存，下次检查时生效")
    }
    private suspend fun latest(): AppRelease = coroutineScope {
        val sources = listOf(
            "https://github.com/${UpdatePolicy.REPO}/releases/latest/download/update.json",
            "https://cdn.jsdelivr.net/gh/${UpdatePolicy.REPO}@main/updates/latest.json"
        ) + listOfNotNull(mirror.takeIf { it.isNotBlank() })
        val results = sources.map { source -> async {
            try {
                val json = JSONObject(network.manifest(source))
                val version = json.getString("versionName")
                val name = json.getString("apkName")
                require(Regex("[0-9]+(\\.[0-9]+){2}").matches(version) && name == "arxiv-physics-v$version.apk") { "清单版本无效" }
                val official = "https://github.com/${UpdatePolicy.REPO}/releases/download/v$version/$name"
                val mirrorApk = if (source == mirror) source.toHttpUrlOrNull()!!.resolve(name)!!.toString() else ""
                AppRelease(json.getLong("versionCode"), version, name, official, json.getString("sha256"),
                    json.getLong("size"), json.optString("notes").take(8000), mirrorApk).also { validateInfo(it) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } }.awaitAll().filterNotNull()
        UpdatePolicy.select(results)
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
        busy = true; message = "正在检查更新（并行备用源，最多约 8 秒）"
        app.scope.launch {
            try {
                val info = latest()
                val known = release
                if (known != null && known.versionCode > info.versionCode) {
                    message = "更新源暂未同步；保留已发现的 ${known.versionName}"
                    return@launch
                }
                if (UpdatePolicy.newer(info.versionCode, BuildConfig.VERSION_CODE.toLong())) {
                    val verified = withContext(Dispatchers.IO) { runCatching { verify(info, apk(info)) }.isSuccess }
                    release = info; ready = verified
                    message = if (verified) "${info.versionName} 已下载并校验，可以安装" else "发现新版本 ${info.versionName}"
                } else { release = null; ready = false; message = "可用更新源未发现新版本，当前 ${BuildConfig.VERSION_NAME}（CDN 可能有缓存延迟）" }
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
                    // The content hash identifies partial data across retries and source switches.
                    val temp = File(folder, "${info.sha256}.part.apk")
                    folder.listFiles()?.filter { it.name.endsWith(".part.apk") && it != temp }?.forEach { it.delete() }
                    if (temp.length() > info.size) temp.delete()
                    var failure: Exception? = null
                    for (url in (listOf(info.mirrorUrl).filter { it.isNotEmpty() } + info.url).distinct()) {
                        try {
                            if (temp.length() != info.size) {
                                val offset = temp.length()
                                val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
                                    .header("User-Agent", "ArxivPhysics/${BuildConfig.VERSION_NAME}")
                                if (offset > 0) builder.header("Range", "bytes=$offset-")
                                client.newCall(builder.build()).execute().use { response ->
                                    check(response.isSuccessful) { "下载更新失败 HTTP ${response.code}" }
                                    val start = UpdatePolicy.resumeOffset(response.code, response.header("Content-Range"), offset, info.size)
                                    val body = checkNotNull(response.body)
                                    check(body.contentLength() == -1L || body.contentLength() == info.size - start) { "下载大小异常" }
                                    body.byteStream().use { input -> FileOutputStream(temp, start > 0).use { output ->
                                        val buffer = ByteArray(64 * 1024); var loaded = start; var reported = -1
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val count = input.read(buffer); if (count < 0) break
                                            check(loaded + count <= info.size) { "下载内容超出清单大小" }
                                            output.write(buffer, 0, count); loaded += count
                                            val percent = (loaded * 100 / info.size).toInt()
                                            if (percent != reported) { reported = percent; withContext(Dispatchers.Main) { progress = percent / 100f } }
                                        }
                                        check(loaded == info.size) { "更新下载中断，可点击重试继续下载" }
                                    } }
                                }
                            }
                            failure = null; break
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = e }
                    }
                    failure?.let { throw it }
                    try {
                        verify(info, temp)
                        check(temp.renameTo(apk(info))) { "更新包保存失败" }
                        val tempManifest = File(pending.path + ".part").apply { writeText(info.json().toString()) }
                        check(tempManifest.renameTo(pending)) { "更新清单保存失败" }
                    } catch (e: Exception) { temp.delete(); throw e }
                }
                ready = true; message = "已完成校验，准备打开系统安装界面"
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "下载失败，请重试" }
            finally { busy = false }
        }
    }
    internal fun validateInfo(info: AppRelease) {
        require(info.versionCode > 0 && info.size in 1..UpdatePolicy.MAX_APK && UpdatePolicy.validHash(info.sha256) && UpdatePolicy.releaseAsset(info.url) && (info.mirrorUrl.isEmpty() || UpdatePolicy.httpsUrl(info.mirrorUrl))) { "更新清单无效" }
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
}
