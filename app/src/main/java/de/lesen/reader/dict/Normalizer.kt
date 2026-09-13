package de.lesen.reader.dict

import java.util.Locale
import java.text.Normalizer as JNormalizer

/**
 * Surface-form normalisation (spec 4.3).
 *
 * This is the Kotlin half of a two-implementation contract; the Python half is
 * tools/dictbuild/lesen_norm.py and MUST behave identically for every input,
 * because the same function normalises both the stored `form_norm` column and
 * every query string. Both are tested against the same fixture file,
 * tools/dictbuild/fixtures/norm_cases.tsv.
 *
 * Steps, in order:
 *  1. Unicode NFC.
 *  2. Strip soft hyphens and zero-width / invisible formatting characters.
 *  3. Normalise the apostrophe family to U+0027.
 *  4. Lowercase (Locale.GERMAN, which adds no special casing over root but says
 *     what we mean).
 *  5. NFC again, then strip combining marks.
 *
 * Step 5 runs after lowercasing as well as before it, because lowercasing can
 * introduce a combining mark: U+0130 (capital I with dot above) lowercases to
 * "i" + U+0307 in both Java and Python. Doing it on both sides keeps the two
 * implementations bit-identical for pathological input.
 *
 * Deliberately NOT done here: no sharp-s to ss folding, no umlaut to ae/oe/ue
 * folding. The primary index stays exact; those are query-time fallback
 * variants (spec 4.4.2).
 */
object Normalizer {

    private val INVISIBLE = intArrayOf(
        0x00AD, // SOFT HYPHEN
        0x200B, // ZERO WIDTH SPACE
        0x200C, // ZERO WIDTH NON-JOINER
        0x200D, // ZERO WIDTH JOINER
        0x200E, // LEFT-TO-RIGHT MARK
        0x200F, // RIGHT-TO-LEFT MARK
        0x2060, // WORD JOINER
        0xFEFF, // ZERO WIDTH NO-BREAK SPACE / BOM
        0x061C, // ARABIC LETTER MARK
        0x180E, // MONGOLIAN VOWEL SEPARATOR
    )

    private val APOSTROPHES = intArrayOf(
        0x2019, // RIGHT SINGLE QUOTATION MARK
        0x2018, // LEFT SINGLE QUOTATION MARK
        0x02BC, // MODIFIER LETTER APOSTROPHE
        0x02B9, // MODIFIER LETTER PRIME
        0x2032, // PRIME
        0x00B4, // ACUTE ACCENT
        0x0060, // GRAVE ACCENT
        0xFF07, // FULLWIDTH APOSTROPHE
    )

    fun norm(input: String): String {
        if (input.isEmpty()) return ""
        var s = JNormalizer.normalize(input, JNormalizer.Form.NFC)
        s = mapAndStrip(s)
        s = stripMarks(s)
        s = s.lowercase(Locale.GERMAN)
        s = JNormalizer.normalize(s, JNormalizer.Form.NFC)
        return stripMarks(s)
    }

    /** Word characters of the German tap target; kept in sync with reader.js. */
    fun isWordChar(cp: Int): Boolean =
        Character.isLetter(cp) || cp == '\''.code || cp == '-'.code

    private fun mapAndStrip(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                INVISIBLE.contains(cp) -> Unit
                APOSTROPHES.contains(cp) -> sb.append('\'')
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun stripMarks(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                -> Unit

                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }
}
