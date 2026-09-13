package de.lesen.reader.epub

import java.io.File

/** Character counts for the whole-book progress percentage (spec section 6). */
object TextCount {

    private val TAG_RE = Regex("<[^>]*>")
    private val SCRIPT_RE = Regex("(?is)<(script|style)\\b.*?</\\1>")
    private val ENTITY_RE = Regex("&[a-zA-Z#0-9]+;")
    private val WS_RE = Regex("\\s+")

    fun countChars(file: File): Int {
        if (!file.isFile) return 0
        return runCatching {
            val html = file.readText(Charsets.UTF_8)
            plainText(html).length
        }.getOrDefault(0)
    }

    fun plainText(html: String): String = html
        .replace(SCRIPT_RE, " ")
        .replace(TAG_RE, " ")
        .replace(ENTITY_RE, "x")     // one character per entity is close enough
        .replace(WS_RE, " ")
        .trim()
}
