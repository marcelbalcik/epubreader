package de.lesen.reader.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 5's acceptance check is that the TSV imports into Anki without
 * column errors, which comes down to: three fields per line, no stray tabs, no
 * stray newlines.
 */
class AnkiExportTest {

    private data class Card(
        override val surface: String,
        override val lemma: String,
        override val glosses: String,
        override val grammar: String? = null,
        override val sentence: String? = null,
    ) : VocabCard

    @Test
    fun threeFieldsPerLine() {
        val tsv = AnkiExport.toTsv(
            listOf(
                Card("Häuser", "Haus", "house; building", "das, Häuser", "Die Häuser waren alt."),
                Card("ging", "gehen", "to go", null, "Er ging nach Hause."),
            )
        )
        val lines = tsv.trim().split("\n")
        assertEquals(2, lines.size)
        lines.forEach { assertEquals("wrong column count in <$it>", 3, it.split("\t").size) }
    }

    @Test
    fun fieldsAreLemmaMeaningSentence() {
        val tsv = AnkiExport.toTsv(
            listOf(Card("Häuser", "Haus", "house", "das, Häuser", "Die Häuser waren alt."))
        )
        val (lemma, meaning, sentence) = tsv.trim().split("\t")
        assertEquals("Haus", lemma)
        assertEquals("house; das, Häuser", meaning)
        assertEquals("Die <b>Häuser</b> waren alt.", sentence)
    }

    @Test
    fun boldsTheSurfaceForm() {
        assertEquals(
            "Er <b>ging</b> fort.",
            AnkiExport.highlighted(Card("ging", "gehen", "to go", null, "Er ging fort.")),
        )
    }

    @Test
    fun fallsBackToTheLemmaWhenTheSurfaceIsAbsent() {
        assertEquals(
            "Das <b>Haus</b> stand dort.",
            AnkiExport.highlighted(Card("Häuser", "Haus", "house", null, "Das Haus stand dort.")),
        )
    }

    @Test
    fun leavesTheSentenceAloneWhenNeitherAppears() {
        assertEquals(
            "Nichts davon hier.",
            AnkiExport.highlighted(Card("Haus", "Haus", "house", null, "Nichts davon hier.")),
        )
    }

    @Test
    fun emptySentenceStaysEmpty() {
        assertEquals("", AnkiExport.highlighted(Card("Haus", "Haus", "house")))
        val tsv = AnkiExport.toTsv(listOf(Card("Haus", "Haus", "house")))
        // trimEnd('\n'), not trim(): the trailing tab is the empty third field,
        // and Anki needs it there to keep the column count.
        assertEquals(3, tsv.trimEnd('\n').split("\t").size)
        assertTrue(tsv.endsWith("\t\n"))
    }

    @Test
    fun escapesTabsAndNewlinesThatWouldShiftColumns() {
        val tsv = AnkiExport.toTsv(
            listOf(
                Card(
                    surface = "Haus",
                    lemma = "Haus",
                    glosses = "house\ttabbed",
                    grammar = "das",
                    sentence = "Zwei\nZeilen mit Haus.",
                )
            )
        )
        assertEquals(1, tsv.trim().split("\n").size)
        assertEquals(3, tsv.trim().split("\t").size)
        assertTrue(tsv.contains("<br>"))
        assertTrue(tsv.contains("house tabbed"))
    }

    @Test
    fun handlesWindowsNewlines() {
        val tsv = AnkiExport.toTsv(
            listOf(Card("Haus", "Haus", "house", null, "Erste\r\nZweite Haus."))
        )
        assertEquals(1, tsv.trim().split("\n").size)
        assertTrue(tsv.contains("Erste<br>Zweite"))
    }

    @Test
    fun everyLineEndsWithExactlyOneNewline() {
        val tsv = AnkiExport.toTsv(List(3) { Card("Haus", "Haus", "house") })
        assertEquals(3, tsv.count { it == '\n' })
        assertTrue(tsv.endsWith("\n"))
    }

    @Test
    fun emptyListIsAnEmptyFile() {
        assertEquals("", AnkiExport.toTsv(emptyList()))
    }
}
