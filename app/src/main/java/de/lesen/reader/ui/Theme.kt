package de.lesen.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import de.lesen.reader.reader.ReaderTheme

/**
 * The app chrome's palette. It follows the reading theme rather than the system
 * one, so that opening the settings sheet in dark mode does not flash a white
 * surface over the page.
 */
private val Ink = Color(0xFF1A1A1A)
private val Paper = Color(0xFFFDFDFB)
private val SepiaPaper = Color(0xFFF4ECD8)
private val SepiaInk = Color(0xFF3A2F22)
private val NightPaper = Color(0xFF121212)
private val NightInk = Color(0xFFD8D8D8)
private val Accent = Color(0xFF7A5C2E)
private val AccentDark = Color(0xFFD7B98A)

@Composable
fun LesenTheme(
    readerTheme: ReaderTheme? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (readerTheme) {
        ReaderTheme.DARK -> true
        ReaderTheme.LIGHT, ReaderTheme.SEPIA -> false
        null -> isSystemInDarkTheme()
    }

    val colors = when {
        dark -> darkColorScheme(
            primary = AccentDark,
            onPrimary = NightPaper,
            secondary = AccentDark,
            background = NightPaper,
            onBackground = NightInk,
            surface = Color(0xFF1C1C1C),
            onSurface = NightInk,
            surfaceVariant = Color(0xFF262626),
            onSurfaceVariant = Color(0xFFB4B4B4),
            outline = Color(0xFF4A4A4A),
        )

        readerTheme == ReaderTheme.SEPIA -> lightColorScheme(
            primary = Accent,
            onPrimary = SepiaPaper,
            secondary = Accent,
            background = SepiaPaper,
            onBackground = SepiaInk,
            surface = Color(0xFFFAF3E3),
            onSurface = SepiaInk,
            surfaceVariant = Color(0xFFEADFC6),
            onSurfaceVariant = Color(0xFF5C4B34),
            outline = Color(0xFFB9A57F),
        )

        else -> lightColorScheme(
            primary = Accent,
            onPrimary = Paper,
            secondary = Accent,
            background = Paper,
            onBackground = Ink,
            surface = Color(0xFFFFFFFF),
            onSurface = Ink,
            surfaceVariant = Color(0xFFEFEFEA),
            onSurfaceVariant = Color(0xFF5A5A5A),
            outline = Color(0xFFC7C7C0),
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}
