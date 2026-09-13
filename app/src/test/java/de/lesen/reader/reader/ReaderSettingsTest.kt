package de.lesen.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings payload is what reader.js turns into CSS custom properties, so a
 * value out of range or a malformed JSON string is a broken page, not an error.
 */
class ReaderSettingsTest {

    @Test
    fun clampsToTheSpecRanges() {
        val tiny = ReaderSettings(fontSize = 4, lineHeight = 0.2f, margin = 0).sane()
        assertEquals(14, tiny.fontSize)
        assertEquals(1.2f, tiny.lineHeight, 0.001f)
        assertEquals(8, tiny.margin)

        val huge = ReaderSettings(fontSize = 99, lineHeight = 9f, margin = 400).sane()
        assertEquals(28, huge.fontSize)
        assertEquals(2.0f, huge.lineHeight, 0.001f)
        assertEquals(48, huge.margin)
    }

    @Test
    fun defaultsAreInsideTheRanges() {
        val defaults = ReaderSettings()
        assertEquals(defaults, defaults.sane())
        assertTrue(defaults.fontSize in ReaderSettings.FONT_SIZE_RANGE)
        assertTrue(defaults.margin in ReaderSettings.MARGIN_RANGE)
    }

    @Test
    fun jsonCarriesEveryPropertyReaderJsReads() {
        val json = ReaderSettings().toJson()
        for (key in listOf(
            "fontSize", "lineHeight", "margin", "fontFamily", "theme",
            "bg", "fg", "link", "hit", "forceColors",
        )) {
            assertTrue("$key missing from $json", json.contains("\"$key\":"))
        }
    }

    @Test
    fun jsonEscapesTheFontStack() {
        // The CSS font stack is quoted ("Literata", Georgia, serif) and would
        // otherwise close the JSON string early.
        val json = ReaderSettings(font = ReaderFont.SERIF).toJson()
        assertTrue(json.contains("\\\"Literata\\\""))
        assertEquals(
            "balanced quotes in $json",
            0,
            countUnescapedQuotes(json) % 2,
        )
    }

    @Test
    fun clampedValuesReachTheJson() {
        val json = ReaderSettings(fontSize = 99, margin = -5).toJson()
        assertTrue(json.contains("\"fontSize\":28"))
        assertTrue(json.contains("\"margin\":8"))
    }

    @Test
    fun lineHeightFormatsAsCssAccepts() {
        assertEquals("1.55", ReaderSettings.formatLineHeight(1.55f))
        assertEquals("1.5", ReaderSettings.formatLineHeight(1.5f))
        assertEquals("2.0", ReaderSettings.formatLineHeight(2.0f))
        assertEquals("1.33", ReaderSettings.formatLineHeight(1.333333f))
    }

    @Test
    fun darkAndSepiaForceTheBooksColours() {
        // Many EPUBs hardcode black on white; without this the dark theme shows
        // black text on #121212.
        assertTrue(ReaderTheme.DARK.forceColors)
        assertTrue(ReaderTheme.SEPIA.forceColors)
        assertTrue(!ReaderTheme.LIGHT.forceColors)
        assertEquals("#121212", ReaderTheme.DARK.bg)
        assertEquals("#D8D8D8", ReaderTheme.DARK.fg)
        assertEquals("#FDFDFB", ReaderTheme.LIGHT.bg)
        assertEquals("#1A1A1A", ReaderTheme.LIGHT.fg)
        assertEquals("#F4ECD8", ReaderTheme.SEPIA.bg)
        assertEquals("#3A2F22", ReaderTheme.SEPIA.fg)
    }

    private fun countUnescapedQuotes(s: String): Int {
        var count = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\') {
                i += 2
                continue
            }
            if (s[i] == '"') count++
            i++
        }
        return count
    }
}
