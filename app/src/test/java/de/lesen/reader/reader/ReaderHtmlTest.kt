package de.lesen.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injection has to land in every shape of chapter a real EPUB ships: a
 * missing stylesheet means no pagination, which is a silent failure.
 */
class ReaderHtmlTest {

    @Test
    fun injectsAtTheEndOfHead() {
        val html = """
            <html><head><title>Kapitel 1</title>
            <link rel="stylesheet" href="style.css"/></head>
            <body><p>Hallo</p></body></html>
        """.trimIndent()
        val out = ReaderHtml.inject(html)

        assertTrue(out.contains("reader/reader.css"))
        assertTrue(out.contains("reader/reader.js"))
        // Ours must come after the book's own stylesheet, or it loses the cascade.
        assertTrue(out.indexOf("style.css") < out.indexOf("reader/reader.css"))
        assertTrue(out.indexOf("reader/reader.css") < out.indexOf("</head>"))
    }

    @Test
    fun injectsWithUppercaseAndSpacedTags() {
        val out = ReaderHtml.inject("<HTML><HEAD></HEAD ><BODY>x</BODY></HTML>")
        assertTrue(out.contains("reader/reader.css"))
        assertEquals(1, Regex("reader/reader\\.css").findAll(out).count())
    }

    @Test
    fun buildsAHeadWhenTheChapterHasNone() {
        val out = ReaderHtml.inject("<html><body><p>Hallo</p></body></html>")
        assertTrue(out.contains("<head>"))
        assertTrue(out.contains("reader/reader.js"))
        assertTrue(out.indexOf("reader/reader.js") < out.indexOf("<body>"))
    }

    @Test
    fun wrapsABareFragment() {
        val out = ReaderHtml.inject("<p>Nur ein Absatz</p>")
        assertTrue(out.startsWith("<html><head>"))
        assertTrue(out.contains("Nur ein Absatz"))
    }

    @Test
    fun keepsTheDoctypeAndXmlDeclaration() {
        val html = """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE html><html><head></head><body>x</body></html>
        """.trimMargin()
        val out = ReaderHtml.inject(html)
        assertTrue(out.startsWith("<?xml"))
        assertTrue(out.contains("<!DOCTYPE html>"))
    }

    @Test
    fun addsAViewportSoTheColumnsMatchTheScreen() {
        val out = ReaderHtml.inject("<html><head></head><body/></html>")
        assertTrue(out.contains("width=device-width"))
    }

    @Test
    fun decodesUtf8ByDefault() {
        val bytes = "<html><head></head><body>Häuser</body></html>".toByteArray(Charsets.UTF_8)
        assertTrue(ReaderHtml.decode(bytes).contains("Häuser"))
    }

    @Test
    fun decodesTheCharsetTheChapterDeclares() {
        // An EPUB 2 chapter in latin-1: decoding it as UTF-8 destroys the umlaut.
        val html = """<?xml version="1.0" encoding="ISO-8859-1"?><html><body>Häuser</body></html>"""
        val bytes = html.toByteArray(Charsets.ISO_8859_1)
        assertTrue(ReaderHtml.decode(bytes).contains("Häuser"))
        assertTrue(String(bytes, Charsets.UTF_8).contains("�"))
    }

    @Test
    fun decodesAMetaCharset() {
        val html = """<html><head><meta charset="windows-1252"></head><body>Grüße</body></html>"""
        val bytes = html.toByteArray(charset("windows-1252"))
        assertTrue(ReaderHtml.decode(bytes).contains("Grüße"))
    }

    @Test
    fun survivesAnUnknownCharset() {
        val html = """<?xml version="1.0" encoding="x-made-up"?><html><body>ok</body></html>"""
        assertTrue(ReaderHtml.decode(html.toByteArray(Charsets.UTF_8)).contains("ok"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/xhtml+xml", ReaderHtml.mimeOf("ch1.xhtml"))
        assertEquals("application/xhtml+xml", ReaderHtml.mimeOf("CH1.HTML"))
        assertEquals("text/css", ReaderHtml.mimeOf("style.css"))
        assertEquals("image/jpeg", ReaderHtml.mimeOf("cover.JPG"))
        assertEquals("image/svg+xml", ReaderHtml.mimeOf("figure.svg"))
        assertEquals("font/ttf", ReaderHtml.mimeOf("Literata.ttf"))
        assertEquals("application/xml", ReaderHtml.mimeOf("toc.ncx"))
        assertEquals("application/octet-stream", ReaderHtml.mimeOf("mystery.bin"))
        assertEquals("application/octet-stream", ReaderHtml.mimeOf("noextension"))
        assertTrue(ReaderHtml.isDocument(ReaderHtml.mimeOf("ch1.xhtml")))
        assertTrue(!ReaderHtml.isDocument(ReaderHtml.mimeOf("style.css")))
    }
}
