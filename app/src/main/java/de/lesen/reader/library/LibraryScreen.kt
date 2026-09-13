package de.lesen.reader.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lesen.reader.R
import de.lesen.reader.data.Book
import de.lesen.reader.data.BookWithProgress
import de.lesen.reader.dict.android.DictInstaller
import de.lesen.reader.epub.ImportRejection
import java.io.File

/**
 * The library (spec section 8.1): covers, title and author, a progress bar,
 * sorted by last opened. The FAB imports through SAF; a long press deletes,
 * extracted directory and all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenVocab: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val dictState by viewModel.dictState.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    // MIME first, */* as the fallback: a fair number of file providers hand out
    // EPUBs as application/octet-stream (spec section 5).
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.import(uri) }

    val importFailedText = stringResource(R.string.import_failed)
    val encryptedText = stringResource(R.string.import_encrypted)
    val notEpubText = stringResource(R.string.import_not_epub)
    val unsafeText = stringResource(R.string.import_unsafe)
    val noSpineText = stringResource(R.string.import_no_spine)

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val text = when (current) {
            is LibraryMessage.Imported -> current.title
            is LibraryMessage.AlreadyPresent -> "${current.title} is already in the library"
            is LibraryMessage.Deleted -> "Deleted ${current.title}"
            is LibraryMessage.Refused -> when (current.rejection) {
                ImportRejection.ENCRYPTED -> encryptedText
                ImportRejection.NO_CONTAINER, ImportRejection.NOT_A_ZIP,
                ImportRejection.NO_OPF -> notEpubText

                ImportRejection.UNSAFE_ENTRY -> unsafeText
                ImportRejection.EMPTY_SPINE -> noSpineText
                ImportRejection.IO -> "$importFailedText: ${current.detail ?: ""}"
            }
        }
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenVocab) {
                        Icon(Icons.Filled.Style, contentDescription = stringResource(R.string.vocab))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picker.launch(arrayOf("application/epub+zip", "*/*")) },
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.import_book))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            DictionaryBanner(dictState, onRetry = viewModel::retryDictionary)

            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.library_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(books, key = { it.book.id }) { entry ->
                        BookCard(
                            entry = entry,
                            onClick = { onOpenBook(entry.book.id) },
                            onLongClick = { pendingDelete = entry.book },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_book_title)) },
            text = { Text(stringResource(R.string.delete_book_body, book.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(book)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DictionaryBanner(state: DictInstaller.State, onRetry: () -> Unit) {
    when (state) {
        is DictInstaller.State.Installing -> Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.dict_installing), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.dict_installing_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is DictInstaller.State.Failed -> Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp)
        ) {
            val title = when (state.reason) {
                DictInstaller.Reason.MISSING_ASSET -> stringResource(R.string.dict_missing)
                DictInstaller.Reason.CHECKSUM -> stringResource(R.string.dict_corrupt)
                DictInstaller.Reason.IO -> stringResource(R.string.dict_corrupt)
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.reason == DictInstaller.Reason.MISSING_ASSET) {
                    stringResource(R.string.dict_missing_body)
                } else {
                    state.detail.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.reason != DictInstaller.Reason.MISSING_ASSET) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }

        else -> Unit
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCard(entry: BookWithProgress, onClick: () -> Unit, onLongClick: () -> Unit) {
    val book = entry.book
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.66f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val cover = remember(book.coverPath) { loadCover(book.coverPath) }
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        book.title.take(24),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (entry.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.progress_percent, entry.progressPercent.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Covers were decoded to a small PNG at import, so this is cheap. */
private fun loadCover(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isNullOrEmpty()) return null
    val file = File(path)
    if (!file.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()
}
