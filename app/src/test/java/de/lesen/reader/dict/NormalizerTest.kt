package de.lesen.reader.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin half of the normalisation parity test (spec 4.3).
 *
 * It reads the very same file as the Python half (tools/dictbuild/test_norm.py):
 * tools/dictbuild/fixtures/norm_cases.tsv, located through the
 * lesen.normFixture system property that app/build.gradle.kts sets, or off the
 * classpath when a harness puts it there. One fixture, not two copies that
 * drift: if these implementations ever disagree, the dictionary silently
 * half-works, which is the worst failure mode this app has.
 */
class NormalizerTest {

    private data class Case(val line: Int, val input: String, val expected: String)

    private fun cases(): List<Case> {
        val fromProperty = System.getProperty("lesen.normFixture")
            ?.let { java.io.File(it) }
            ?.takeIf { it.isFile }
        val stream = fromProperty?.inputStream()
            ?: javaClass.classLoader?.getResourceAsStream(FIXTURE)
            ?: error(
                "cannot find $FIXTURE - set the lesen.normFixture system property " +
                    "(app/build.gradle.kts does) or put the file on the test classpath"
            )
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.withIndex().mapNotNull { (i, raw) ->
                val line = raw.trimEnd('\n')
                if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
                val parts = line.split("\t")
                require(parts.size == 2) { "$FIXTURE:${i + 1}: expected exactly one tab" }
                Case(i + 1, unescape(parts[0]), unescape(parts[1]))
            }.toList()
        }
    }

    /** Mirrors the Python harness, which decodes \\uXXXX escapes in the fixture. */
    private fun unescape(s: String): String {
        if (!s.contains("\\u")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 5 < s.length + 1 && s.getOrNull(i + 1) == 'u') {
                val hex = s.substring(i + 2, minOf(i + 6, s.length))
                val cp = hex.toIntOrNull(16)
                if (cp != null && hex.length == 4) {
                    sb.append(cp.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    @Test
    fun matchesFixture() {
        val cases = cases()
        assertTrue(
            "fixture should cover ~40 tricky words, found ${cases.size}",
            cases.size >= 40,
        )
        for ((line, input, expected) in cases) {
            assertEquals("$FIXTURE:$line for input <$input>", expected, Normalizer.norm(input))
        }
    }

    @Test
    fun emptyStringIsTotal() {
        assertEquals("", Normalizer.norm(""))
        assertEquals("", Normalizer.norm("­​"))
    }

    @Test
    fun isIdempotent() {
        for ((_, input, _) in cases()) {
            val once = Normalizer.norm(input)
            assertEquals(once, Normalizer.norm(once))
        }
    }

    @Test
    fun doesNotFoldSharpSOrUmlauts() {
        // Folding belongs at query time (spec 4.4.2), not in the index.
        assertEquals("straße", Normalizer.norm("Straße"))
        assertEquals("bücher", Normalizer.norm("Bücher"))
    }

    @Test
    fun caseFoldingEdges() {
        assertEquals("ß", Normalizer.norm("ẞ"))   // capital sharp s
        assertEquals("i", Normalizer.norm("İ"))   // I with dot above
    }

    private companion object {
        const val FIXTURE = "norm_cases.tsv"
    }
}
