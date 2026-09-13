package de.lesen.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lesen.reader.R
import de.lesen.reader.epub.EpubStructure
import de.lesen.reader.epub.TocEntry

/**
 * The flattened table of contents (spec section 8.3). The current chapter is
 * marked and scrolled to when the sheet opens; selecting an entry records the
 * old position so the reader can jump back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    structure: EpubStructure?,
    currentSpineIndex: Int,
    onSelect: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = structure?.toc.orEmpty()
    val currentHref = structure?.spine?.getOrNull(currentSpineIndex)?.href
    val listState = rememberLazyListState()

    val currentIndex = entries.indexOfLast { it.href == currentHref }
    LaunchedEffect(currentIndex) {
        if (currentIndex > 2) listState.scrollToItem(currentIndex - 2)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.toc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(entries) { index, entry ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isCurrent) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onSelect(entry) }
                            .padding(
                                start = (20 + entry.depth.coerceAtMost(4) * 16).dp,
                                end = 20.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                    )
                }
            }
        }
    }
}
