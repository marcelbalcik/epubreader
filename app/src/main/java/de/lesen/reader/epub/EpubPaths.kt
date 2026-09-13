package de.lesen.reader.epub

/**
 * Path arithmetic for EPUB hrefs (spec section 5).
 *
 * Every path in the app is relative to the extracted content root, so the OPF,
 * the TOC, the spine and the WebView asset loader all speak one addressing
 * scheme. Kept free of Android imports so it can be unit-tested: an href
 * resolved wrongly is a blank chapter or a missing image.
 */
object EpubPaths {

    /** Resolves an EPUB-relative href against a directory, collapsing "..". */
    fun resolve(baseDir: String, href: String): String {
        if (href.isEmpty()) return normalise(baseDir)
        if (href.startsWith("/")) return normalise(href.removePrefix("/"))
        val combined = if (baseDir.isEmpty()) href else "$baseDir/$href"
        return normalise(combined)
    }

    fun normalise(path: String): String {
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    /**
     * Percent-decodes an href. Not URLDecoder: that turns "+" into a space,
     * which corrupts real file names like "chapter+1.xhtml".
     */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }

    /** Splits "text/ch1.xhtml#frag" into the path and the fragment. */
    fun splitFragment(href: String): Pair<String, String?> {
        val path = href.substringBefore('#')
        val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }
        return path to fragment
    }
}
