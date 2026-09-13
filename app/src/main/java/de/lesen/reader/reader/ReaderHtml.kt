package de.lesen.reader.reader

import java.nio.charset.Charset

/**
 * The string half of serving a chapter: MIME guessing, charset sniffing and the
 * CSS/JS injection (spec section 6).
 *
 * Kept free of Android imports so it can be unit-tested on the desktop JVM -
 * getting the injection wrong means no stylesheet, which means no pagination,
 * which is not a subtle failure but is a silent one.
 */
object ReaderHtml {

    const val ASSET_PREFIX = "/lesen/"

    val INJECTION: String = listOf(
        """<meta name="viewport" content="width=device-width, initial-scale=1.0, """ +
            """maximum-scale=1.0, user-scalable=no" />""",
        """<link rel="stylesheet" type="text/css" href="${ASSET_PREFIX}reader/reader.css" />""",
        """<script src="${ASSET_PREFIX}reader/reader.js" defer="defer"></script>""",
    ).joinToString("\n")

    private val HEAD_END_RE = Regex("(?i)</head\\s*>")
    private val HTML_OPEN_RE = Regex("(?i)<html[^>]*>")
    private val BODY_OPEN_RE = Regex("(?i)<body[^>]*>")
    private val CHARSET_RE = Regex("""(?i)(?:encoding=|charset=)\s*["']?([A-Za-z0-9_:.+-]+)""")

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xhtml", "xhtm", "html", "htm" -> "application/xhtml+xml"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "json" -> "application/json"
        "xml", "opf", "ncx" -> "application/xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt", "tsv" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "mp4", "m4v" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun isDocument(mime: String): Boolean =
        mime == "text/html" || mime == "application/xhtml+xml"

    /**
     * Decodes a chapter with the charset it declares. Plenty of EPUB 2 books are
     * ISO-8859-1 or windows-1252, and decoding those as UTF-8 turns every umlaut
     * into a replacement character.
     */
    fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = CHARSET_RE.find(head)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"', '\'')
        val charset = declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /**
     * Injects the stylesheet and the script into a chapter.
     *
     * The link goes last in <head>, after the book's own stylesheets, so it wins
     * the cascade wherever specificity ties. The script is deferred: it runs when
     * the chapter is parsed, before first paint.
     *
     * Books that ship no <head>, or no <html> at all, still have to render, so
     * there are three fallbacks below rather than one assumption.
     */
    fun inject(html: String): String {
        HEAD_END_RE.find(html)?.let { match ->
            return html.substring(0, match.range.first) + INJECTION +
                html.substring(match.range.first)
        }
        HTML_OPEN_RE.find(html)?.let { match ->
            val at = match.range.last + 1
            return html.substring(0, at) + "<head>" + INJECTION + "</head>" + html.substring(at)
        }
        BODY_OPEN_RE.find(html)?.let { match ->
            return html.substring(0, match.range.first) + "<head>" + INJECTION + "</head>" +
                html.substring(match.range.first)
        }
        return "<html><head>$INJECTION</head><body>$html</body></html>"
    }
}
