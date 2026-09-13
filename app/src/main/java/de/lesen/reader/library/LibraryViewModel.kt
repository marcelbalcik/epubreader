package de.lesen.reader.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lesen.reader.data.Book
import de.lesen.reader.data.BookWithProgress
import de.lesen.reader.di.AppContainer
import de.lesen.reader.dict.android.DictInstaller
import de.lesen.reader.epub.BookImporter
import de.lesen.reader.epub.ImportRejection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val books: StateFlow<List<BookWithProgress>> = container.database.books()
        .observeLibrary()
        .map { rows -> rows.map { it.toBookWithProgress() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dictState: StateFlow<DictInstaller.State> = container.dict.state

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** One-shot message for the snackbar: an import result or a rejection. */
    private val _message = MutableStateFlow<LibraryMessage?>(null)
    val message: StateFlow<LibraryMessage?> = _message.asStateFlow()

    init {
        // The dictionary is unpacked while the reader browses the library, so
        // the first tap on a word does not wait for a 100 MB gunzip.
        viewModelScope.launch { container.dict.ensureReady() }
    }

    fun import(uri: Uri?) {
        if (uri == null) return
        _importing.value = true
        viewModelScope.launch {
            when (val result = container.importer.import(uri)) {
                is BookImporter.Result.Imported ->
                    _message.value = LibraryMessage.Imported(result.title)

                is BookImporter.Result.AlreadyPresent ->
                    _message.value = LibraryMessage.AlreadyPresent(result.title)

                is BookImporter.Result.Refused ->
                    _message.value = LibraryMessage.Refused(result.rejection, result.detail)
            }
            _importing.value = false
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            container.importer.delete(book)
            _message.value = LibraryMessage.Deleted(book.title)
        }
    }

    fun retryDictionary() {
        viewModelScope.launch { container.dict.ensureReady() }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

sealed interface LibraryMessage {
    data class Imported(val title: String) : LibraryMessage
    data class AlreadyPresent(val title: String) : LibraryMessage
    data class Deleted(val title: String) : LibraryMessage
    data class Refused(val rejection: ImportRejection, val detail: String?) : LibraryMessage
}
