package de.lesen.reader.epub

import android.util.Log
import android.util.Xml
import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Hand-rolled EPUB structure parser (spec section 2/5): java.util.zip did the
 * extraction, this reads the result with XmlPullParser.
 *
 * Order of business:
 *   META-INF/container.xml  ->  OPF path
 *   OPF                     ->  metadata, manifest, spine (linear="no" dropped)
 *   nav.xhtml (EPUB 3)      ->  TOC, falling back to toc.ncx (EPUB 2)
 *
 * Everything is expressed as paths relative to the extracted content root, so
 * the WebView asset loader and the reader agree on one addressing scheme.
 */
object EpubParser {

    private const val TAG = "EpubParser"
    private const val OPS_NS = "http://www.idpf.org/2007/ops"
    private const val NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
    private const val CONTAINER = "META-INF/container.xml"

    fun parse(contentRoot: File): EpubStructure {
        val opfPath = readContainer(contentRoot)
        val opfFile = File(contentRoot, opfPath)
        if (!opfFile.isFile) throw EpubException(ImportRejection.NO_OPF, opfPath)

        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = readOpf(opfFile, opfDir)

        if (opf.spine.isEmpty()) throw EpubException(ImportRejection.EMPTY_SPINE)

        val toc = readToc(contentRoot, opf)
        return opf.copy(toc = toc, opfPath = opfPath)
    }

    // ---- container.xml ----------------------------------------------------

    private fun readContainer(contentRoot: File): String {
        val container = File(contentRoot, CONTAINER)
        if (!container.isFile) throw EpubException(ImportRejection.NO_CONTAINER)
        container.inputStream().use { input ->
            val p = newParser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                    val path = p.getAttributeValue(null, "full-path")
                    if (!path.isNullOrBlank()) return normalisePath(percentDecode(path))
                }
            }
        }
        throw EpubException(ImportRejection.NO_OPF, "container.xml names no rootfile")
    }

    // ---- OPF --------------------------------------------------------------

    private fun readOpf(opfFile: File, opfDir: String): EpubStructure {
        var title: String? = null
        var creator: String? = null
        var language: String? = null
        var coverMetaId: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spineRefs = ArrayList<Pair<String, Boolean>>()   // idref to linear

        opfFile.inputStream().use { input ->
            val p = newParser(input)
            var section = ""
            var pendingText: StringBuilder? = null
            var pendingTag = ""

            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                when (event) {
                    XmlPullParser.START_TAG -> when (val name = p.name) {
                        "metadata", "manifest", "spine" -> section = name
                        "title", "creator", "language" -> if (section == "metadata") {
                            pendingTag = name
                            pendingText = StringBuilder()
                        }

                        "meta" -> if (section == "metadata") {
                            // EPUB 2 cover pointer: <meta name="cover" content="id" />
                            if (p.getAttributeValue(null, "name") == "cover") {
                                coverMetaId = p.getAttributeValue(null, "content")
                            }
                        }

                        "item" -> if (section == "manifest") {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val href = p.getAttributeValue(null, "href") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifest[id] = ManifestItem(
                                    id = id,
                                    href = resolve(opfDir, percentDecode(href)),
                                    mediaType = p.getAttributeValue(null, "media-type"),
                                    properties = p.getAttributeValue(null, "properties"),
                                )
                            }
                        }

                        "itemref" -> if (section == "spine") {
                            val idref = p.getAttributeValue(null, "idref")
                            val linear = p.getAttributeValue(null, "linear") != "no"
                            if (!idref.isNullOrEmpty()) spineRefs.add(idref to linear)
                        }
                    }

                    XmlPullParser.TEXT -> pendingText?.append(p.text)

                    XmlPullParser.END_TAG -> {
                        if (pendingText != null && p.name == pendingTag) {
                            val value = pendingText.toString().trim()
                            when (pendingTag) {
                                "title" -> if (title == null && value.isNotEmpty()) title = value
                                "creator" -> if (creator == null && value.isNotEmpty()) {
                                    creator = value
                                }

                                "language" -> if (language == null && value.isNotEmpty()) {
                                    language = value
                                }
                            }
                            pendingText = null
                            pendingTag = ""
                        }
                    }
                }
            }
        }

        val spine = spineRefs
            .filter { (_, linear) -> linear }
            .mapNotNull { (idref, _) ->
                manifest[idref]?.let { SpineItem(idref, it.href, it.mediaType) }
            }

        val cover = manifest[coverMetaId]?.href
            ?: manifest.values.firstOrNull {
                it.properties?.contains("cover-image") == true
            }?.href
            ?: manifest.values.firstOrNull {
                it.mediaType?.startsWith("image/") == true &&
                    it.href.substringAfterLast('/').contains("cover", ignoreCase = true)
            }?.href

        return EpubStructure(
            opfPath = "",
            metadata = EpubMetadata(
                title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
                creator = creator,
                language = language,
                coverHref = cover,
            ),
            manifest = manifest.values.toList(),
            spine = spine,
            toc = emptyList(),
        )
    }

    // ---- TOC --------------------------------------------------------------

    private fun readToc(contentRoot: File, opf: EpubStructure): List<TocEntry> {
        // EPUB 3 first: the manifest item with properties="nav".
        val navItem = opf.manifest.firstOrNull { it.properties?.contains("nav") == true }
        if (navItem != null) {
            val file = File(contentRoot, navItem.href)
            if (file.isFile) {
                runCatching { readNav(file, navItem.href.substringBeforeLast('/', "")) }
                    .onSuccess { if (it.isNotEmpty()) return it }
                    .onFailure { Log.w(TAG, "nav.xhtml unreadable, trying NCX", it) }
            }
        }

        // EPUB 2: the spine's toc attribute usually points at it; the manifest
        // media type is the reliable way to find it either way.
        val ncxItem = opf.manifest.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml" ||
                it.href.endsWith(".ncx", ignoreCase = true)
        }
        if (ncxItem != null) {
            val file = File(contentRoot, ncxItem.href)
            if (file.isFile) {
                runCatching { readNcx(file, ncxItem.href.substringBeforeLast('/', "")) }
                    .onSuccess { if (it.isNotEmpty()) return it }
                    .onFailure { Log.w(TAG, "toc.ncx unreadable", it) }
            }
        }

        // Last resort: the spine itself, so the TOC sheet is never empty.
        return opf.spine.mapIndexed { i, item ->
            TocEntry(0, "Chapter ${i + 1}", item.href, null)
        }.also { Log.w(TAG, "no nav or ncx: falling back to ${it.size} spine entries") }
    }

    /** EPUB 3 nav: `<nav epub:type="toc">` containing nested `<ol><li><a>`. */
    private fun readNav(navFile: File, navDir: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        navFile.inputStream().use { input ->
            val p = newParser(input)
            var inToc = false
            var navDepth = 0
            var olDepth = 0
            var href: String? = null
            val label = StringBuilder()
            var inAnchor = false

            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "nav" -> {
                            val type = p.getAttributeValue(OPS_NS, "type")
                                ?: p.getAttributeValue(null, "type")
                                ?: p.getAttributeValue(null, "role")
                            if (!inToc && (type == "toc" || type == "doc-toc")) {
                                inToc = true
                                navDepth = 1
                            } else if (inToc) {
                                navDepth++
                            }
                        }

                        "ol" -> if (inToc) olDepth++

                        "a" -> if (inToc) {
                            inAnchor = true
                            label.setLength(0)
                            href = p.getAttributeValue(null, "href")
                        }

                        "span" -> if (inToc) {
                            // A nav item without a link (a group heading).
                            inAnchor = true
                            label.setLength(0)
                            href = null
                        }
                    }

                    XmlPullParser.TEXT -> if (inAnchor) label.append(p.text)

                    XmlPullParser.END_TAG -> when (p.name) {
                        "a", "span" -> if (inToc && inAnchor) {
                            inAnchor = false
                            val text = label.toString().replace(Regex("\\s+"), " ").trim()
                            val target = href
                            if (text.isNotEmpty() && !target.isNullOrBlank()) {
                                out.add(
                                    tocEntry(
                                        depth = (olDepth - 1).coerceAtLeast(0),
                                        label = text,
                                        rawHref = target,
                                        baseDir = navDir,
                                    )
                                )
                            }
                        }

                        "ol" -> if (inToc) olDepth--

                        "nav" -> if (inToc) {
                            navDepth--
                            if (navDepth <= 0) return out
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * EPUB 2 NCX: `<navMap>` of nested `<navPoint>`. The DTD puts `<navLabel>`
     * before `<content>`, so the label for a point is always known by the time
     * its href arrives; nesting depth comes from the navPoint stack.
     */
    private fun readNcx(ncxFile: File, ncxDir: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        ncxFile.inputStream().use { input ->
            val p = newParser(input)
            var inNavMap = false
            var inText = false
            val labels = ArrayList<String>()      // one slot per open navPoint
            val label = StringBuilder()

            while (true) {
                val event = p.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "navMap" -> inNavMap = true
                        "navPoint" -> if (inNavMap) labels.add("")
                        "text" -> if (inNavMap) {
                            inText = true
                            label.setLength(0)
                        }

                        "content" -> if (inNavMap && labels.isNotEmpty()) {
                            val src = p.getAttributeValue(null, "src")
                                ?: p.getAttributeValue(NCX_NS, "src")
                            val text = labels.last()
                            if (!src.isNullOrBlank() && text.isNotEmpty()) {
                                out.add(tocEntry(labels.size - 1, text, src, ncxDir))
                            }
                        }
                    }

                    XmlPullParser.TEXT -> if (inText) label.append(p.text)

                    XmlPullParser.END_TAG -> when (p.name) {
                        "text" -> if (inText) {
                            inText = false
                            if (labels.isNotEmpty() && labels.last().isEmpty()) {
                                labels[labels.size - 1] =
                                    label.toString().replace(Regex("\\s+"), " ").trim()
                            }
                        }

                        "navPoint" -> if (inNavMap && labels.isNotEmpty()) {
                            labels.removeAt(labels.size - 1)
                        }

                        "navMap" -> if (inNavMap) return out
                    }
                }
            }
        }
        return out
    }

    private fun tocEntry(depth: Int, label: String, rawHref: String, baseDir: String): TocEntry {
        val decoded = percentDecode(rawHref)
        val fragment = decoded.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val path = resolve(baseDir, decoded.substringBefore('#'))
        return TocEntry(depth, label, path, fragment)
    }

    // ---- paths (see EpubPaths, which is unit-tested) -----------------------

    private fun resolve(baseDir: String, href: String) = EpubPaths.resolve(baseDir, href)

    private fun normalisePath(path: String) = EpubPaths.normalise(path)

    private fun percentDecode(s: String) = EpubPaths.percentDecode(s)

    private fun newParser(input: java.io.InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // Many real EPUBs contain undeclared HTML entities (&nbsp;) in their
        // nav documents; a strict parser would abort the whole TOC over one.
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        try {
            setInput(input, null)
        } catch (e: XmlPullParserException) {
            throw EpubException(ImportRejection.IO, e.message)
        }
    }
}
