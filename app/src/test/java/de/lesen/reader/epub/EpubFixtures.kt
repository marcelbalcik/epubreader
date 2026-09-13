package de.lesen.reader.epub

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Three structurally different EPUBs, written as real zip archives, plus the
 * malformed ones the importer has to refuse.
 *
 * Milestone 2's acceptance check is "three structurally different EPUBs (one
 * EPUB 2 with NCX, one EPUB 3 with nav, one with heavy inline CSS and images)
 * all render without missing images or blank chapters". Rendering needs a
 * device; everything up to it - unpacking, the OPF, the spine, the TOC, and
 * whether every href actually resolves to a file - is checked here.
 */
object EpubFixtures {

    private val CONTAINER = { opf: String ->
        """<?xml version="1.0" encoding="UTF-8"?>
        |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        |  <rootfiles><rootfile full-path="$opf" media-type="application/oebps-package+xml"/></rootfiles>
        |</container>
        """.trimMargin()
    }

    private fun chapter(title: String, body: String, css: String? = null) =
        """<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE html>
        |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title>
        |${css?.let { "<link rel=\"stylesheet\" type=\"text/css\" href=\"$it\"/>" } ?: ""}
        |</head><body>$body</body></html>
        """.trimMargin()

    private fun pngBytes(): ByteArray {
        // A 1x1 transparent PNG. Real bytes, so "the file exists" means something.
        val base64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAABjYuMvAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        return java.util.Base64.getDecoder().decode(base64)
    }

    private fun zip(target: File, entries: List<Pair<String, ByteArray>>): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { out ->
            // The mimetype entry comes first and uncompressed, as EPUB requires.
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return target
    }

    private fun text(s: String) = s.toByteArray(Charsets.UTF_8)

    /** EPUB 2: OPF in OEBPS/, toc.ncx with nested navPoints, meta cover, linear="no". */
    fun epub2WithNcx(dir: File): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
        |<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
        |  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        |    <dc:title>Die Häuser</dc:title>
        |    <dc:creator opf:role="aut" xmlns:opf="http://www.idpf.org/2007/opf">Anna Autorin</dc:creator>
        |    <dc:language>de</dc:language>
        |    <meta name="cover" content="cover-img"/>
        |  </metadata>
        |  <manifest>
        |    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        |    <item id="cover-img" href="images/cover.png" media-type="image/png"/>
        |    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="ads" href="text/ads.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="css" href="styles/book.css" media-type="text/css"/>
        |  </manifest>
        |  <spine toc="ncx">
        |    <itemref idref="ch1"/>
        |    <itemref idref="ch2"/>
        |    <itemref idref="ads" linear="no"/>
        |  </spine>
        |</package>
        """.trimMargin()

        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
        |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
        |  <head><meta name="dtb:uid" content="x"/></head>
        |  <docTitle><text>Die Häuser</text></docTitle>
        |  <navMap>
        |    <navPoint id="n1" playOrder="1">
        |      <navLabel><text>Erstes Kapitel</text></navLabel>
        |      <content src="text/ch1.xhtml"/>
        |      <navPoint id="n1a" playOrder="2">
        |        <navLabel><text>Ein Unterabschnitt</text></navLabel>
        |        <content src="text/ch1.xhtml#teil2"/>
        |      </navPoint>
        |    </navPoint>
        |    <navPoint id="n2" playOrder="3">
        |      <navLabel><text>Zweites Kapitel</text></navLabel>
        |      <content src="text/ch2.xhtml"/>
        |    </navPoint>
        |  </navMap>
        |</ncx>
        """.trimMargin()

        return zip(
            File(dir, "epub2.epub"),
            listOf(
                "mimetype" to text("application/epub+zip"),
                "META-INF/container.xml" to text(CONTAINER("OEBPS/content.opf")),
                "OEBPS/content.opf" to text(opf),
                "OEBPS/toc.ncx" to text(ncx),
                "OEBPS/images/cover.png" to pngBytes(),
                "OEBPS/styles/book.css" to text("p { text-indent: 1em; }"),
                "OEBPS/text/ch1.xhtml" to text(
                    chapter(
                        "Erstes Kapitel",
                        "<h1>Erstes Kapitel</h1><p>Die Häuser standen im Regen.</p>" +
                            "<h2 id=\"teil2\">Ein Unterabschnitt</h2><p>Sie ging fort.</p>" +
                            "<img src=\"../images/cover.png\" alt=\"\"/>",
                        css = "../styles/book.css",
                    )
                ),
                "OEBPS/text/ch2.xhtml" to text(
                    chapter("Zweites Kapitel", "<p>Das schönste Zimmer war leer.</p>")
                ),
                "OEBPS/text/ads.xhtml" to text(chapter("Werbung", "<p>Mehr Bücher!</p>")),
            ),
        )
    }

    /** EPUB 3: OPF at the root, nav.xhtml with epub:type="toc", a percent-encoded href. */
    fun epub3WithNav(dir: File): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
        |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
        |  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        |    <dc:title>Der zweite Band</dc:title>
        |    <dc:creator>Bertolt Beispiel</dc:creator>
        |    <dc:language>de-DE</dc:language>
        |  </metadata>
        |  <manifest>
        |    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        |    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
        |    <item id="c1" href="kapitel%201.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="c2" href="deep/kapitel2.xhtml" media-type="application/xhtml+xml"/>
        |  </manifest>
        |  <spine>
        |    <itemref idref="c1"/>
        |    <itemref idref="c2"/>
        |  </spine>
        |</package>
        """.trimMargin()

        val nav = """<?xml version="1.0" encoding="UTF-8"?>
        |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        |<head><title>Inhalt</title></head><body>
        |  <nav epub:type="landmarks"><ol><li><a href="kapitel%201.xhtml">Anfang</a></li></ol></nav>
        |  <nav epub:type="toc">
        |    <h1>Inhalt</h1>
        |    <ol>
        |      <li><a href="kapitel%201.xhtml">Kapitel eins</a>
        |        <ol><li><a href="kapitel%201.xhtml#mitte">Mitte</a></li></ol>
        |      </li>
        |      <li><a href="deep/kapitel2.xhtml">Kapitel zwei</a></li>
        |    </ol>
        |  </nav>
        |</body></html>
        """.trimMargin()

        return zip(
            File(dir, "epub3.epub"),
            listOf(
                "mimetype" to text("application/epub+zip"),
                "META-INF/container.xml" to text(CONTAINER("content.opf")),
                "content.opf" to text(opf),
                "nav.xhtml" to text(nav),
                "cover.png" to pngBytes(),
                "kapitel 1.xhtml" to text(
                    chapter(
                        "Kapitel eins",
                        "<p>Ein Kinderbuch lag dort.</p><p id=\"mitte\">Mitte.</p>",
                    )
                ),
                "deep/kapitel2.xhtml" to text(chapter("Kapitel zwei", "<p>Ende.</p>")),
            ),
        )
    }

    /**
     * Heavy inline CSS, images in a sibling directory reached with "../", no nav
     * and no NCX at all, and a chapter in ISO-8859-1.
     */
    fun messyWithInlineCss(dir: File): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
        |<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
        |  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        |    <dc:title>Ohne Inhaltsverzeichnis</dc:title>
        |    <dc:language>de</dc:language>
        |  </metadata>
        |  <manifest>
        |    <item id="p1" href="xhtml/seite1.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="p2" href="xhtml/seite2.xhtml" media-type="application/xhtml+xml"/>
        |    <item id="i1" href="img/tafel.png" media-type="image/png"/>
        |    <item id="i2" href="img/cover.png" media-type="image/png"/>
        |  </manifest>
        |  <spine><itemref idref="p1"/><itemref idref="p2"/></spine>
        |</package>
        """.trimMargin()

        val inlineCss = """
        |<style type="text/css">
        |  body { background: #fff !important; color: #000 !important; font-size: 9pt; }
        |  p.first { color: #333; text-align: justify; }
        |  img.plate { width: 100%; height: auto; }
        |</style>
        """.trimMargin()

        val latin1Chapter =
            """<?xml version="1.0" encoding="ISO-8859-1"?>
            |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Seite 2</title>$inlineCss</head>
            |<body><p>Grüße aus München, die Straße war naß.</p></body></html>
            """.trimMargin()

        return zip(
            File(dir, "messy.epub"),
            listOf(
                "mimetype" to text("application/epub+zip"),
                "META-INF/container.xml" to text(CONTAINER("OPS/book.opf")),
                "OPS/book.opf" to text(opf),
                "OPS/xhtml/seite1.xhtml" to text(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    |<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Seite 1</title>$inlineCss</head>
                    |<body><p class="first">Erste Seite.</p>
                    |<img class="plate" src="../img/tafel.png" alt=""/></body></html>
                    """.trimMargin()
                ),
                "OPS/xhtml/seite2.xhtml" to latin1Chapter.toByteArray(Charsets.ISO_8859_1),
                "OPS/img/tafel.png" to pngBytes(),
                "OPS/img/cover.png" to pngBytes(),
            ),
        )
    }

    /** DRM-encrypted: has to be refused, not rendered as mojibake. */
    fun encrypted(dir: File): File = zip(
        File(dir, "drm.epub"),
        listOf(
            "META-INF/container.xml" to text(CONTAINER("content.opf")),
            "META-INF/encryption.xml" to text(
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>"""
            ),
            "content.opf" to text("<package/>"),
        ),
    )

    /** A zip that is not an EPUB: no container.xml. */
    fun notAnEpub(dir: File): File = zip(
        File(dir, "plain.zip"),
        listOf("readme.txt" to text("just a zip")),
    )

    /** An archive with an entry that tries to escape the target directory. */
    fun zipSlip(dir: File): File = zip(
        File(dir, "slip.epub"),
        listOf(
            "META-INF/container.xml" to text(CONTAINER("content.opf")),
            "content.opf" to text("<package/>"),
            "../../escaped.txt" to text("should never be written"),
        ),
    )

    /** Not a zip at all. */
    fun notAZip(dir: File): File {
        val file = File(dir, "nonsense.epub")
        file.writeBytes(ByteArrayOutputStream().apply { write("not a zip".toByteArray()) }
            .toByteArray())
        return file
    }
}
