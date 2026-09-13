package de.lesen.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Test

/** An href resolved wrongly is a blank chapter or a missing image. */
class EpubPathsTest {

    @Test
    fun resolvesAgainstTheOpfDirectory() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubPaths.resolve("OEBPS", "text/ch1.xhtml"))
        assertEquals("ch1.xhtml", EpubPaths.resolve("", "ch1.xhtml"))
    }

    @Test
    fun collapsesParentSegments() {
        assertEquals("OEBPS/images/x.png", EpubPaths.resolve("OEBPS/text", "../images/x.png"))
        assertEquals("x.png", EpubPaths.resolve("OEBPS", "../x.png"))
        // A book cannot climb above the content root, however many ".." it uses.
        assertEquals("x.png", EpubPaths.resolve("OEBPS", "../../../x.png"))
    }

    @Test
    fun treatsAnAbsoluteHrefAsContentRootRelative() {
        assertEquals("OEBPS/ch1.xhtml", EpubPaths.resolve("text", "/OEBPS/ch1.xhtml"))
    }

    @Test
    fun dropsCurrentDirectorySegments() {
        assertEquals("OEBPS/ch1.xhtml", EpubPaths.resolve("OEBPS", "./ch1.xhtml"))
        assertEquals("a/b", EpubPaths.normalise("a//b/"))
    }

    @Test
    fun percentDecodesWithoutTouchingPlus() {
        assertEquals("chapter 1.xhtml", EpubPaths.percentDecode("chapter%201.xhtml"))
        assertEquals("Häuser.xhtml", EpubPaths.percentDecode("H%C3%A4user.xhtml"))
        // URLDecoder would turn this into "chapter 1.xhtml", which is a 404.
        assertEquals("chapter+1.xhtml", EpubPaths.percentDecode("chapter+1.xhtml"))
        assertEquals("100%", EpubPaths.percentDecode("100%"))
    }

    @Test
    fun splitsFragments() {
        assertEquals("text/ch1.xhtml" to "sec2", EpubPaths.splitFragment("text/ch1.xhtml#sec2"))
        assertEquals("text/ch1.xhtml" to null, EpubPaths.splitFragment("text/ch1.xhtml"))
    }

    @Test
    fun dirOf() {
        assertEquals("OEBPS/text", EpubPaths.dirOf("OEBPS/text/ch1.xhtml"))
        assertEquals("", EpubPaths.dirOf("content.opf"))
    }
}
