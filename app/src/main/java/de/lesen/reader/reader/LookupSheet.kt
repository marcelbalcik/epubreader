package de.lesen.reader.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lesen.reader.R
import de.lesen.reader.dict.DictEntry
import de.lesen.reader.dict.LookupPath
import de.lesen.reader.dict.LookupResult

/**
 * The lookup sheet (spec section 8.4).
 *
 * It opens the instant a tap arrives, with a spinner, and fills in when the
 * lookup returns - compound splitting can take a few hundred index probes and
 * the sheet must not wait for it.
 *
 * The lookup path is always on screen. That is the point of showing it: the
 * owner wants to know when the app guessed rather than matched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(
    state: SheetState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveOne: (LookupResult) -> Unit,
    onLookUp: (String) -> Unit,
    pathLabel: (LookupPath) -> Int,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            when (state) {
                is SheetState.Loading -> LoadingBody(state.surface)
                is SheetState.Word -> WordBody(
                    result = state.result,
                    saved = state.saved,
                    onSave = onSave,
                    onLookUp = onLookUp,
                    pathLabel = pathLabel,
                )

                is SheetState.Selection -> SelectionBody(
                    state = state,
                    onSaveOne = onSaveOne,
                    pathLabel = pathLabel,
                )

                SheetState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun LoadingBody(surface: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(22.dp))
        Spacer(Modifier.height(0.dp))
        Text(
            surface,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun WordBody(
    result: LookupResult,
    saved: Boolean,
    onSave: () -> Unit,
    onLookUp: (String) -> Unit,
    pathLabel: (LookupPath) -> Int,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.surface, style = MaterialTheme.typography.titleMedium)
            PathChip(path = result.path, labelRes = pathLabel(result.path))
        }
        if (result.entries.isNotEmpty()) {
            Button(onClick = onSave, enabled = !saved) {
                Icon(
                    if (saved) Icons.Filled.Check else Icons.Filled.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Text(
                    stringResource(if (saved) R.string.saved_word else R.string.save_word),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (result.parts.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "${stringResource(R.string.compound_of)}: ${result.parts.joinToString("  |  ")}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (result.entries.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.no_match, result.surface),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (result.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.did_you_mean),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.suggestions.forEach { suggestion ->
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLookUp(suggestion) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        return
    }

    result.entries.forEachIndexed { index, entry ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 14.dp))
        EntryBody(entry)
    }
}

@Composable
private fun EntryBody(entry: DictEntry) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            entry.displayLemma,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            entry.pos,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp),
        )
    }

    val grammar = buildList {
        entry.plural?.let { add("pl. $it") }
        entry.ipa?.let { add(it) }
    }
    if (grammar.isNotEmpty()) {
        Text(
            grammar.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(8.dp))
    entry.glosses.forEachIndexed { index, gloss ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(
                "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(gloss, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SelectionBody(
    state: SheetState.Selection,
    onSaveOne: (LookupResult) -> Unit,
    pathLabel: (LookupPath) -> Int,
) {
    state.tooMany?.let { count ->
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.selection_too_long, count),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }

    state.results.forEachIndexed { index, result ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                val entry = result.entries.firstOrNull()
                Text(
                    entry?.displayLemma ?: result.surface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    entry?.glosses?.take(3)?.joinToString("; ")
                        ?: stringResource(R.string.no_match, result.surface),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PathChip(path = result.path, labelRes = pathLabel(result.path))
            }
            if (result.entries.isNotEmpty()) {
                TextButton(onClick = { onSaveOne(result) }) {
                    Text(stringResource(R.string.save_word))
                }
            }
        }
    }
}

/** The small label that says which of the seven steps produced this result. */
@Composable
private fun PathChip(path: LookupPath, @StringRes labelRes: Int) {
    val background = if (path.isGuess) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    Box(
        Modifier
            .padding(top = 4.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
