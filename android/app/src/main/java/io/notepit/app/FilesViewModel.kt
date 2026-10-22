package io.notepit.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Mutations outlive a screen recreation; only application-scoped repository tasks are retained. */
class FilesViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var completed by mutableIntStateOf(0)
        private set
    private var label = ""

    fun execute(label: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        this.label = label
        progress = label
        error = null
        viewModelScope.launch {
            try { task() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure }
            finally { busy = false; progress = ""; completed++ }
        }
    }

    fun reportProgress(done: Int, total: Int) {
        viewModelScope.launch { progress = "${label.removeSuffix("…")} $done of $total…" }
    }

    fun dismissError() { error = null }
}
