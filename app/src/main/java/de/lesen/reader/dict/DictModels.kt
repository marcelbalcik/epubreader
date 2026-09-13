package de.lesen.reader.dict

/** One headword row of `entry`, joined with the `form` row that matched it. */
data class DictEntry(
    val id: Long,
    val lemma: String,
    val pos: String,
    val gender: String?,   // m / f / n / mf / mn / fn / mfn / null
    val plural: String?,
    val ipa: String?,
    val glosses: List<String>,
    val isLemmaForm: Boolean = false,
) {
    /** "der" / "die" / "das" for nouns whose gender is unambiguous. */
    val article: String?
        get() = when (gender) {
            "m" -> "der"
            "f" -> "die"
            "n" -> "das"
            else -> null
        }

    /** The headword as the lookup sheet shows it: "das Haus", "gehen". */
    val displayLemma: String
        get() = if (pos == "noun") listOfNotNull(article, lemma).joinToString(" ") else lemma
}

/**
 * Which of the seven steps of spec 4.4 produced the result. Shown in the UI as
 * a small label: the owner wants to know when the app is guessing.
 */
enum class LookupPath {
    EXACT,      // 4.4.1 form_norm hit
    VARIANT,    // 4.4.2 sharp-s/umlaut variants, 4.4.3 de-hyphenation
    HEURISTIC,  // 4.4.4 participle ge-infix, 4.4.5 suffix stripping
    COMPOUND,   // 4.4.6 right-to-left compound split
    FUZZY,      // 4.4.7 prefix search: suggestions only, nothing resolved
    NONE;       // nothing at all

    val isGuess: Boolean get() = this == HEURISTIC || this == COMPOUND || this == FUZZY
}

data class LookupResult(
    /** The word as tapped, before normalisation. */
    val surface: String,
    /** The form that actually hit the index, or null when nothing resolved. */
    val matchedForm: String?,
    val entries: List<DictEntry>,
    val path: LookupPath,
    /** For compounds: the decomposition, head last. Empty otherwise. */
    val parts: List<String> = emptyList(),
    /** For FUZZY: "did you mean" forms, at most 15. */
    val suggestions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        fun miss(surface: String, suggestions: List<String> = emptyList()) = LookupResult(
            surface = surface,
            matchedForm = null,
            entries = emptyList(),
            path = if (suggestions.isEmpty()) LookupPath.NONE else LookupPath.FUZZY,
            suggestions = suggestions,
        )
    }
}

/**
 * The three queries the lookup algorithm needs. Implemented against Android's
 * read-only SQLiteDatabase in production (dict/android/SqliteDictSource.kt) and
 * against JDBC in the unit tests, which is why the algorithm itself carries no
 * Android dependency and runs on the desktop JVM.
 */
interface DictSource {
    fun entriesFor(formNorm: String): List<DictEntry>
    fun prefixSearch(formNorm: String, limit: Int): List<String>
    fun meta(key: String): String?
}

/**
 * The `glosses` column is a JSON array of strings. Parsing it here rather than
 * with org.json keeps the core free of Android, and the column shape is one we
 * produce ourselves in build_dict.py.
 */
object Glosses {
    fun parse(json: String): List<String> {
        val out = ArrayList<String>(4)
        val sb = StringBuilder()
        var i = 0
        var inString = false
        while (i < json.length) {
            val c = json[i]
            when {
                inString && c == '\\' && i + 1 < json.length -> {
                    when (val esc = json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> if (i + 5 < json.length) {
                            json.substring(i + 2, i + 6).toIntOrNull(16)
                                ?.let { sb.append(it.toChar()) }
                            i += 4
                        }

                        else -> sb.append(esc)
                    }
                    i += 2
                    continue
                }

                c == '"' -> {
                    if (inString) {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                    inString = !inString
                }

                inString -> sb.append(c)
            }
            i++
        }
        return out
    }

    fun encode(glosses: List<String>): String = glosses.joinToString(
        prefix = "[", postfix = "]", separator = ","
    ) { g ->
        buildString {
            append('"')
            for (ch in g) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(ch)
            }
            append('"')
        }
    }
}
