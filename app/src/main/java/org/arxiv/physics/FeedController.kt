package org.arxiv.physics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FeedController(private val app: ArxivApp) {
    var papers by mutableStateOf(emptyList<Paper>())
    var total by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stale by mutableStateOf(false)
    var fetched by mutableStateOf(0L)
    private var pageOffset = 0
    private var query = ""
    private var ascending = false
    private var request: Job? = null
    private var generation = 0
    val hasMore get() = pageOffset < total && papers.isNotEmpty()
    fun load(query: String, ascending: Boolean = false, more: Boolean = false, refresh: Boolean = false) {
        if (more && loading) return
        val changed = this.query != query || this.ascending != ascending
        request?.cancel()
        val run = ++generation
        this.query = query
        this.ascending = ascending
        if (changed || !more) { pageOffset = 0; if (changed) { papers = emptyList(); total = 0; fetched = 0 } }
        loading = true
        error = null
        request = app.scope.launch {
            try {
                val result = app.repository.feed(query, if (more) pageOffset else 0, ascending, refresh)
                if (run != generation) return@launch
                papers = (if (more) papers + result.papers else result.papers).distinctBy { it.id }
                pageOffset = (if (more) pageOffset else 0) + result.papers.size
                total = result.total; stale = result.stale; fetched = result.fetched
                if (result.papers.isEmpty()) total = pageOffset
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "加载失败，请检查网络" }
            finally { if (run == generation) loading = false }
        }
    }
    fun more() = load(query, ascending, more = true)
    fun refresh() = load(query, ascending, refresh = true)
}
