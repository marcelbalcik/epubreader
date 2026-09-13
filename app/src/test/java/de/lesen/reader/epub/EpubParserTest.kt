package de.lesen.reader.epub

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Milestone 2, minus the rendering: three structurally different EPUBs are
 * unpacked and parsed, and every href the reader will ask for is checked to
 * resolve to a file that actually exists - which is what "no missing images or
 * blank chapters" comes down to before the WebView gets involved.
 *
 * On the device the parser is handed android.util.Xml's pull parser; here it
 * gets kxml2's, which is the same XmlPullParser interface.
 */
class EpubParserTest {

    private lateinit var tmp: File
    private lateinit var parser: EpubParser
    private val warnings = mutableListOf<String>()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("lesen-epub-test").toFile()
        val factory = XmlPullParserFactory.newInstance()
        parser = EpubParser(
            newPullParser = { factory.newPullParser() },
            onWarning = { warnings.add(it) },
        )
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun unpack(epub: File, name: String): File {
        val content = File(tmp, "$name/content")
        ZipExtract.extract(epub, content)
        return content
    }

    /** Everything the reader will fetch has to be on disk where the OPF says. */
    private fun assertEveryHrefResolves(structure: EpubStructure, root: File) {
        for (item in structure.manifest) {
            assertTrue(
                "manifest item ${item.id} -> ${item.href} is missing on disk",
                File(root, item.href).isFile,
            )
        }
        for ((index, item) in structure.spine.withIndex()) {
            assertTrue(
                "spine item $index -> ${item.href} is missing on disk",
                File(root, item.href).isFile,
            )
            assertTrue(
                "spine item $index -> ${item.href} is empty",
                File(root, item.href).length() > 0,
            )
        }
        for (entry in structure.toc) {
            assertTrue(
                "TOC entry '${entry.label}' -> ${entry.href} is missing on disk",
                File(root, entry.href).isFile,
            )
        }
    }

    // ---- EPUB 2 with NCX --------------------------------------------------

    @Test
    fun parsesEpub2WithNcx() {
        val root = unpack(EpubFixtures.epub2WithNcx(tmp), "epub2")
        val structure = parser.parse(root)

        assertEquals("OEBPS/content.opf", structure.opfPath)
        assertEquals("Die Häuser", structure.metadata.title)
        assertEquals("Anna Autorin", structure.metadata.creator)
        assertEquals("de", structure.metadata.language)
        assertEquals("OEBPS/images/cover.png", structure.metadata.coverHref)

        // linear="no" is excluded, and the order is the spine's order.
        assertEquals(
            listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"),
            structure.spine.map { it.href },
        )

        assertEquals(
            listOf("Erstes Kapitel", "Ein Unterabschnitt", "Zweites Kapitel"),
            structure.toc.map { it.label },
        )
        assertEquals(listOf(0, 1, 0), structure.toc.map { it.depth })
        assertEquals("OEBPS/text/ch1.xhtml", structure.toc[1].href)
        assertEquals("teil2", structure.toc[1].fragment)
        assertNull(structure.toc[0].fragment)

        assertEquals(0, structure.spineIndexOf("OEBPS/text/ch1.xhtml"))
        assertEquals(1, structure.spineIndexOf("OEBPS/text/ch2.xhtml#anything"))
        assertEquals(-1, structure.spineIndexOf("OEBPS/text/ads.xhtml"))

        assertEveryHrefResolves(structure, root)
    }

    // ---- EPUB 3 with nav --------------------------------------------------

    @Test
    fun parsesEpub3WithNav() {
        val root = unpack(EpubFixtures.epub3WithNav(tmp), "epub3")
        val structure = parser.parse(root)

        assertEquals("content.opf", structure.opfPath)
        assertEquals("Der zweite Band", structure.metadata.title)
        assertEquals("de-DE", structure.metadata.language)
        // properties="cover-image", with no EPUB 2 <meta name="cover"> present.
        assertEquals("cover.png", structure.metadata.coverHref)

        // The percent-encoded href has to be decoded, or the file is a 404.
        assertEquals(
            listOf("kapitel 1.xhtml", "deep/kapitel2.xhtml"),
            structure.spine.map { it.href },
        )

        // The toc nav is used and the landmarks nav is ignored.
        assertEquals(
            listOf("Kapitel eins", "Mitte", "Kapitel zwei"),
            structure.toc.map { it.label },
        )
        assertEquals(listOf(0, 1, 0), structure.toc.map { it.depth })
        assertEquals("mitte", structure.toc[1].fragment)
        assertEquals("deep/kapitel2.xhtml", structure.toc[2].href)

        assertEveryHrefResolves(structure, root)
    }

    // ---- heavy inline CSS, images, no TOC at all --------------------------

    @Test
    fun parsesTheMessyBookAndFallsBackToTheSpine() {
        val root = unpack(EpubFixtures.messyWithInlineCss(tmp), "messy")
        val structure = parser.parse(root)

        assertEquals("OPS/book.opf", structure.opfPath)
        assertEquals("Ohne Inhaltsverzeichnis", structure.metadata.title)
        assertNull("no creator in this book", structure.metadata.creator)

        assertEquals(
            listOf("OPS/xhtml/seite1.xhtml", "OPS/xhtml/seite2.xhtml"),
            structure.spine.map { it.href },
        )

        // No nav, no NCX: the TOC sheet must still list something.
        assertEquals(2, structure.toc.size)
        assertEquals(listOf("Chapter 1", "Chapter 2"), structure.toc.map { it.label })
        assertTrue(warnings.any { it.contains("no nav or ncx") })

        // The image is reached with "../" from the chapter; the manifest href
        // must already be content-root relative.
        assertTrue(structure.manifest.any { it.href == "OPS/img/tafel.png" })
        assertEveryHrefResolves(structure, root)

        // The latin-1 chapter has to survive the round trip through ReaderHtml.
        val latin1 = File(root, "OPS/xhtml/seite2.xhtml").readBytes()
        val decoded = de.lesen.reader.reader.ReaderHtml.decode(latin1)
        assertTrue("umlauts were mangled: $decoded", decoded.contains("Grüße aus München"))
        assertTrue(decoded.contains("naß"))
    }

    @Test
    fun charactersAreCountedForTheProgressBar() {
        val root = unpack(EpubFixtures.messyWithInlineCss(tmp), "messy-count")
        val structure = parser.parse(root)
        val counts = structure.spine.map { TextCount.countChars(File(root, it.href)) }

        assertTrue("every chapter should count for something: $counts", counts.all { it > 0 })
        // The inline <style> block must not be counted as prose.
        assertTrue("counts look like markup was included: $counts", counts.all { it < 120 })
        assertTrue(TextCount.plainText("<style>p{color:red}</style><p>Hallo</p>").trim() == "Hallo")
    }

    // ---- refusals ---------------------------------------------------------

    @Test
    fun refusesEncryptedBooks() {
        val rejection = rejectionOf { unpack(EpubFixtures.encrypted(tmp), "drm") }
        assertEquals(ImportRejection.ENCRYPTED, rejection)
    }

    @Test
    fun refusesAZipThatIsNotAnEpub() {
        val rejection = rejectionOf { unpack(EpubFixtures.notAnEpub(tmp), "plain") }
        assertEquals(ImportRejection.NO_CONTAINER, rejection)
    }

    @Test
    fun refusesSomethingThatIsNotAZip() {
        val rejection = rejectionOf { unpack(EpubFixtures.notAZip(tmp), "nonsense") }
        assertEquals(ImportRejection.NOT_A_ZIP, rejection)
    }

    @Test
    fun refusesAnEntryThatEscapesTheTargetDirectory() {
        val target = File(tmp, "slip/content")
        val rejection = rejectionOf { ZipExtract.extract(EpubFixtures.zipSlip(tmp), target) }
        assertEquals(ImportRejection.UNSAFE_ENTRY, rejection)
        assertTrue(
            "the escaping entry was written anyway",
            !File(tmp, "escaped.txt").exists() && !File(tmp.parentFile, "escaped.txt").exists(),
        )
    }

    @Test
    fun zipSlipGuardCoversTheObviousShapes() {
        val root = File(tmp, "guard").apply { mkdirs() }
        for (name in listOf(
            "../outside.txt",
            "a/../../outside.txt",
            "a/b/../../../outside.txt",
            "../",
        )) {
            try {
                ZipExtract.safeDestination(root, name)
                fail("accepted an escaping entry name: $name")
            } catch (e: EpubException) {
                assertEquals(ImportRejection.UNSAFE_ENTRY, e.rejection)
            }
        }
        // An absolute entry name is malformed rather than hostile: File(root, "/x")
        // resolves to root/x, so it lands inside the book's own folder and is
        // allowed through. What matters is that it cannot escape.
        val absolute = ZipExtract.safeDestination(root, "/absolute.txt")
        assertTrue(
            "an absolute entry name escaped the root: $absolute",
            absolute.path.startsWith(root.canonicalFile.path + File.separator),
        )

        // ... while ordinary names still resolve inside the root.
        assertEquals(
            File(root, "OEBPS/text/ch1.xhtml").canonicalFile,
            ZipExtract.safeDestination(root, "OEBPS/text/ch1.xhtml"),
        )
        assertEquals(
            File(root, "a/b.txt").canonicalFile,
            ZipExtract.safeDestination(root, "a/./b.txt"),
        )
    }

    @Test
    fun refusesABookWithNoChapters() {
        // A valid container and OPF, but an empty spine: nothing to read.
        val content = File(tmp, "empty/content").apply { mkdirs() }
        File(content, "META-INF").mkdirs()
        File(content, "META-INF/container.xml").writeText(
            """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
               <rootfiles><rootfile full-path="content.opf"/></rootfiles></container>"""
        )
        File(content, "content.opf").writeText(
            """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf">
               <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Leer</dc:title></metadata>
               <manifest/><spine/></package>"""
        )
        assertEquals(ImportRejection.EMPTY_SPINE, rejectionOf { parser.parse(content) })
    }

    @Test
    fun refusesAContentDirectoryWithNoContainer() {
        val content = File(tmp, "bare/content").apply { mkdirs() }
        assertEquals(ImportRejection.NO_CONTAINER, rejectionOf { parser.parse(content) })
    }

    @Test
    fun untitledBooksStillGetAName() {
        val content = File(tmp, "untitled/content").apply { mkdirs() }
        File(content, "META-INF").mkdirs()
        File(content, "META-INF/container.xml").writeText(
            """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
               <rootfiles><rootfile full-path="content.opf"/></rootfiles></container>"""
        )
        File(content, "ch1.xhtml").writeText("<html><body><p>x</p></body></html>")
        File(content, "content.opf").writeText(
            """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf">
               <metadata/>
               <manifest><item id="c" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
               <spine><itemref idref="c"/></spine></package>"""
        )
        val structure = parser.parse(content)
        assertEquals("Untitled", structure.metadata.title)
        assertNotNull(structure.spine.firstOrNull())
    }

    private fun rejectionOf(block: () -> Unit): ImportRejection? = try {
        block()
        null
    } catch (e: EpubException) {
        e.rejection
    }
}
