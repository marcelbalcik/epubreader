package de.lesen.reader.vocab

/**
 * The fields the exporter needs. VocabItem (a Room entity) implements this, so
 * the export logic carries no dependency on Room and can be unit-tested on its
 * own.
 */
interface VocabCard {
    val surface: String
    val lemma: String
    val glosses: String
    val grammar: String?
    val sentence: String?
}

/**
 * Anki-importable TSV (spec section 8.6):
 *
 *   lemma <TAB> glosses; article/plural <TAB> sentence with the word in <b>
 *
 * Pure Kotlin so the escaping is unit-testable: a stray tab or newline in a
 * gloss shifts every following column and Anki imports nonsense without
 * complaining, which is exactly the failure the acceptance check looks for.
 */
object AnkiExport {

    /** Anki treats a bare newline as a row break, so fields carry <br> instead. */
    private const val NEWLINE = "<br>"

    fun toTsv(items: List<VocabCard>): String = buildString {
        for (item in items) {
            append(field(item.lemma))
            append('\t')
            append(field(meaning(item)))
            append('\t')
            append(field(highlighted(item)))
            append('\n')
        }
    }

    fun meaning(item: VocabCard): String {
        val grammar = item.grammar?.takeIf { it.isNotBlank() }
        return if (grammar == null) item.glosses else "${item.glosses}; $grammar"
    }

    /**
     * The context sentence with the tapped word in bold. The surface form is
     * matched first; if the book inflected it beyond recognition the lemma is
     * tried, and if neither appears the sentence is exported unchanged rather
     * than mangled.
     */
    fun highlighted(item: VocabCard): String {
        val sentence = item.sentence?.takeIf { it.isNotBlank() } ?: return ""
        for (needle in listOfNotNull(item.surface, item.lemma).filter { it.isNotBlank() }) {
            val at = sentence.indexOf(needle, ignoreCase = true)
            if (at >= 0) {
                return sentence.substring(0, at) +
                    "<b>" + sentence.substring(at, at + needle.length) + "</b>" +
                    sentence.substring(at + needle.length)
            }
        }
        return sentence
    }

    private fun field(value: String): String = value
        .replace("\r\n", NEWLINE)
        .replace('\r', '\n')
        .replace("\n", NEWLINE)
        .replace('\t', ' ')
        .trim()
}
