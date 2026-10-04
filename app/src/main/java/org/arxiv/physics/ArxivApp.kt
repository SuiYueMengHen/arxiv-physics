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
        store.jobs().filter { it.stage in setOf(Stage.QUEUED, Stage.DOWNLOADING, Stage.UPLOADING, Stage.TRANSLATING) }.forEach {
            store.job(it.copy(stage = Stage.INTERRUPTED, message = "旧任务已停止，现使用前台网页翻译。已保存文件保留。"))
        }
        reloadJobs()
    }
    fun reloadJobs() { jobs = store.jobs() }
    fun updateJob(job: TranslationJob) { store.job(job); reloadJobs() }
    fun subscribe(ids: Set<String>) {
        subscriptions = ids
        prefs.edit().putStringSet("subscriptions", ids).apply()
    }
}
