package de.lesen.reader.vocab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lesen.reader.R

/**
 * Saved words (spec section 8.6): searchable, deletable, and exportable as an
 * Anki-importable TSV through ACTION_CREATE_DOCUMENT.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(viewModel: VocabViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsState()
    val query by viewModel.query.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val creator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri -> if (uri != null) viewModel.export(context, uri) }

    LaunchedEffect(exportResult) {
        when (val result = exportResult) {
            is VocabViewModel.ExportResult.Done -> snackbar.showSnackbar(
                context.getString(R.string.export_done, result.count)
            )

            is VocabViewModel.ExportResult.Failed -> snackbar.showSnackbar(
                context.getString(R.string.export_failed, result.reason)
            )

            null -> return@LaunchedEffect
        }
        viewModel.consumeExportResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vocab)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { creator.launch("lesen-vokabeln.tsv") }) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.export_tsv),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::search,
                label = { Text(stringResource(R.string.vocab_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.vocab_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.lemma,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    AnkiExport.meaning(item),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                item.sentence?.let { sentence ->
                                    Text(
                                        sentence,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.delete(item) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}
