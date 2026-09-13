package de.lesen.reader.epub

/** One reachable chapter, in spine order. `linear="no"` items are excluded. */
data class SpineItem(
    val idref: String,
    /** Path relative to the extracted content root, percent-decoded. */
    val href: String,
    val mediaType: String?,
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val properties: String?,
)

/** Flattened table of contents entry (spec section 5). */
data class TocEntry(
    val depth: Int,
    val label: String,
    /** Path relative to the content root, without the fragment. */
    val href: String,
    /** The `#fragment`, without the hash, or null. */
    val fragment: String?,
) {
    val spineHref: String get() = href
}

data class EpubMetadata(
    val title: String,
    val creator: String?,
    val language: String?,
    val coverHref: String?,
)

data class EpubStructure(
    /** OPF path relative to the content root. */
    val opfPath: String,
    val metadata: EpubMetadata,
    val manifest: List<ManifestItem>,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
) {
    fun spineIndexOf(href: String): Int {
        val clean = href.substringBefore('#')
        return spine.indexOfFirst { it.href == clean }
    }
}

/** Why an import was refused. Each maps to a message the reader can act on. */
enum class ImportRejection {
    NOT_A_ZIP,
    NO_CONTAINER,
    NO_OPF,
    ENCRYPTED,
    UNSAFE_ENTRY,
    EMPTY_SPINE,
    IO,
}

class EpubException(val rejection: ImportRejection, message: String? = null) :
    Exception(message ?: rejection.name)
