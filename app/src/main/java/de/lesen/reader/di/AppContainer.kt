package de.lesen.reader.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.lesen.reader.data.AppDatabase
import de.lesen.reader.data.SettingsStore
import de.lesen.reader.dict.android.DictRepository
import de.lesen.reader.epub.BookImporter
import de.lesen.reader.library.LibraryViewModel
import de.lesen.reader.reader.ReaderViewModel
import de.lesen.reader.vocab.VocabViewModel

/**
 * Manual dependency wiring (spec section 2: no DI framework).
 *
 * Four singletons and three ViewModel factories. Everything is created lazily,
 * so opening the app does not touch the dictionary or the database until
 * something actually asks.
 */
class AppContainer(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.open(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }
    val dict: DictRepository by lazy { DictRepository(context) }
    val importer: BookImporter by lazy { BookImporter(context, database.books()) }

    fun libraryFactory(): ViewModelProvider.Factory = factory { LibraryViewModel(this) }

    fun readerFactory(bookId: Long): ViewModelProvider.Factory =
        factory { ReaderViewModel(this, bookId) }

    fun vocabFactory(): ViewModelProvider.Factory = factory { VocabViewModel(this) }

    private inline fun <T : ViewModel> factory(
        crossinline builder: () -> T,
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = builder() as V
    }
}
