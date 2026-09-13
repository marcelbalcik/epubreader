package de.lesen.reader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lesen.reader.R

/**
 * The settings sheet (spec section 8.5). Every change goes straight to
 * SharedPreferences and to reader.js as CSS custom properties, so the page
 * re-paginates and keeps its place instead of reloading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenVocab: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(16.dp))
            Label(stringResource(R.string.font_family))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChip(R.string.font_serif, ReaderFont.SERIF, settings, onChange)
                FontChip(R.string.font_sans, ReaderFont.SANS, settings, onChange)
                FontChip(R.string.font_system, ReaderFont.SYSTEM, settings, onChange)
            }

            Spacer(Modifier.height(16.dp))
            Label("${stringResource(R.string.font_size)}: ${settings.fontSize} px")
            Slider(
                value = settings.fontSize.toFloat(),
                onValueChange = { value ->
                    onChange { it.copy(fontSize = value.toInt()) }
                },
                valueRange = ReaderSettings.FONT_SIZE_RANGE.first.toFloat()..
                    ReaderSettings.FONT_SIZE_RANGE.last.toFloat(),
                steps = ReaderSettings.FONT_SIZE_RANGE.count() - 2,
            )

            Label(
                "${stringResource(R.string.line_height)}: " +
                    ReaderSettings.formatLineHeight(settings.lineHeight)
            )
            Slider(
                value = settings.lineHeight,
                onValueChange = { value -> onChange { it.copy(lineHeight = value) } },
                valueRange = ReaderSettings.LINE_HEIGHT_MIN..ReaderSettings.LINE_HEIGHT_MAX,
                steps = 15,
            )

            Label("${stringResource(R.string.margin)}: ${settings.margin} px")
            Slider(
                value = settings.margin.toFloat(),
                onValueChange = { value -> onChange { it.copy(margin = value.toInt()) } },
                valueRange = ReaderSettings.MARGIN_RANGE.first.toFloat()..
                    ReaderSettings.MARGIN_RANGE.last.toFloat(),
                steps = 9,
            )

            Spacer(Modifier.height(8.dp))
            Label(stringResource(R.string.theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChip(R.string.theme_light, ReaderTheme.LIGHT, settings, onChange)
                ThemeChip(R.string.theme_sepia, ReaderTheme.SEPIA, settings, onChange)
                ThemeChip(R.string.theme_dark, ReaderTheme.DARK, settings, onChange)
            }

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                label = stringResource(R.string.keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = { value -> onChange { it.copy(keepScreenOn = value) } },
            )
            ToggleRow(
                label = stringResource(R.string.volume_keys),
                checked = settings.volumeKeysTurnPages,
                onCheckedChange = { value ->
                    onChange { it.copy(volumeKeysTurnPages = value) }
                },
            )

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenVocab) { Text(stringResource(R.string.vocab)) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontChip(
    labelRes: Int,
    font: ReaderFont,
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    FilterChip(
        selected = settings.font == font,
        onClick = { onChange { it.copy(font = font) } },
        label = { Text(stringResource(labelRes)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChip(
    labelRes: Int,
    theme: ReaderTheme,
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    FilterChip(
        selected = settings.theme == theme,
        onClick = { onChange { it.copy(theme = theme) } },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
