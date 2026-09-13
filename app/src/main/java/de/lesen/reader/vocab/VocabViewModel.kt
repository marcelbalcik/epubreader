package de.lesen.reader.vocab

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lesen.reader.data.VocabItem
import de.lesen.reader.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VocabViewModel(private val container: AppContainer) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<VocabItem>> = _query
        .flatMapLatest { container.database.vocab().observe(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(text: String) {
        _query.value = text
    }

    fun delete(item: VocabItem) {
        viewModelScope.launch { container.database.vocab().delete(item) }
    }

    /** Writes the TSV into the document the reader picked via SAF. */
    fun export(context: Context, target: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = container.database.vocab().all()
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        out.write(AnkiExport.toTsv(all).toByteArray(Charsets.UTF_8))
                    } ?: error("could not open the chosen file for writing")
                    all.size
                }
            }
            _exportResult.value = result.fold(
                onSuccess = { ExportResult.Done(it) },
                onFailure = { ExportResult.Failed(it.message ?: "unknown error") },
            )
        }
    }

    fun consumeExportResult() {
        _exportResult.value = null
    }

    sealed interface ExportResult {
        data class Done(val count: Int) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }
}
