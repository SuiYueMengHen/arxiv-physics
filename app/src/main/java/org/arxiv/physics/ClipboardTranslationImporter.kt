package org.arxiv.physics

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object ClipboardImportPolicy {
    fun accepts(text: String?, previous: String?, freshCopy: Boolean = false): Boolean {
        if (text == null || (text == previous && !freshCopy)) return false
        val trimmed = text.trim()
        return trimmed.codePointCount(0, trimmed.length) > 500
    }
}

/** Active only in the foreground paper web page. Existing clipboard text is never imported. */
class ClipboardTranslationImporter(private val app: ArxivApp) {
    private val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var task: Job? = null
    private var generation = 0

    fun start(paper: Paper, onSaved: (Paper) -> Unit, onError: (String) -> Unit = {}) {
        stop()
        val run = generation
        var previous = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        var previousTimestamp = clipboard.primaryClipDescription?.timestamp
        var importing = false
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            val timestamp = clipboard.primaryClipDescription?.timestamp
            val accepted = ClipboardImportPolicy.accepts(text, previous, timestamp != null && timestamp != previousTimestamp)
            val old = previous
            previous = text
            previousTimestamp = timestamp
            if (accepted && !importing) {
                importing = true
                task = app.scope.launch {
                    try {
                        app.repository.saveMarkdown(paper, TranslationProtocol.clean(text!!))
                        app.updateJob(TranslationJob(paper.id, Stage.COMPLETE, 100, "剪贴板译文已保存，可离线阅读"))
                        if (run == generation) {
                            listener?.let(clipboard::removePrimaryClipChangedListener)
                            listener = null
                            onSaved(paper)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        importing = false; previous = old
                        onError("保存失败：${e.message}，请重新复制译文")
                    }
                }
            }
        }
        clipboard.addPrimaryClipChangedListener(listener!!)
    }

    fun stop() {
        generation++
        listener?.let(clipboard::removePrimaryClipChangedListener)
        listener = null
        task?.cancel(); task = null
    }
}
