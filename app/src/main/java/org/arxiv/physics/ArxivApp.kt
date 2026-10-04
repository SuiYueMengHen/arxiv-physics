package org.arxiv.physics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ArxivApp : Application() {
    lateinit var store: Store
    lateinit var repository: Repository
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val web by lazy { DeepSeekWeb(this) }
    val updates by lazy { AppUpdates(this) }
    var jobs by mutableStateOf(emptyList<TranslationJob>())
    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    fun setTheme(mode: ThemeMode) { themeMode = mode; prefs.edit().putString("theme", mode.name).apply() }
    var subscriptions by mutableStateOf(setOf("quant-ph", "hep-th", "cond-mat.str-el"))
    override fun onCreate() {
        super.onCreate()
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        store = Store(this)
        repository = Repository(this, store)
        subscriptions = prefs.getStringSet("subscriptions", subscriptions)!!.toSet()
        store.jobs().filter { it.stage != Stage.COMPLETE }.forEach {
            val paper = store.paper(it.id)
            if (paper != null && repository.pdf(paper).exists()) {
                val translated = repository.markdown(paper).exists()
                store.job(it.copy(stage = if (translated) Stage.COMPLETE else Stage.DOWNLOADED,
                    progress = 100, message = if (translated) "本地译文已保存" else "本地文件已下载"))
            } else if (it.stage in setOf(Stage.QUEUED, Stage.DOWNLOADING, Stage.UPLOADING, Stage.TRANSLATING)) {
                store.job(it.copy(stage = Stage.INTERRUPTED, message = "下载未完成，可重试"))
            }
        }
        reloadJobs()
    }
    fun reloadJobs() { jobs = store.jobs() }
    fun updateJob(job: TranslationJob) { store.job(job); reloadJobs() }
    fun recordDownloaded(paper: Paper) {
        val translated = repository.markdown(paper).exists()
        updateJob(TranslationJob(paper.id, if (translated) Stage.COMPLETE else Stage.DOWNLOADED,
            100, if (translated) "本地译文已保存" else "本地文件已下载"))
    }
    fun subscribe(ids: Set<String>) {
        subscriptions = ids
        prefs.edit().putStringSet("subscriptions", ids).apply()
    }
}
