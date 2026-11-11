package `in`.bipul.ebookreader.reader

import android.net.Uri
import `in`.bipul.ebookreader.book.ReflowableBook
import `in`.bipul.ebookreader.book.SpineItem
import `in`.bipul.ebookreader.book.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class BookServerTest {
    private val book = object : ReflowableBook {
        override val title = "T"
        override val author = ""
        override val spine = listOf(SpineItem("OEBPS/ch 1.xhtml", "application/xhtml+xml", 10))
        override val toc = emptyList<TocEntry>()
        private val files = mapOf(
            "OEBPS/ch 1.xhtml" to "<?xml version='1.0' encoding='iso-8859-1'?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><script src=\"evil.js\"/><title>x</title></head><body><p>hi</p><script>alert(1)</script></body></html>",
            "OEBPS/img.png" to "PNG",
        )

        override fun open(path: String): InputStream? = files[path]?.byteInputStream()
        override fun close() = Unit
    }

    private val server = BookServer(book, RuntimeEnvironment.getApplication().assets) { "body{}" }

    @Test
    fun injectsReaderAssetsAndStripsBookScripts() {
        val html = BookServer.inject(book.open("OEBPS/ch 1.xhtml")!!.reader().readText())
        assertTrue(html.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertFalse(html.contains("evil.js"))
        assertFalse(html.contains("alert"))
        assertTrue(html.contains("<title>x</title><meta name=\"viewport\""))
        assertTrue(html.indexOf("<link id=\"rd-style\"") < html.indexOf("</head>"))
        assertTrue(html.indexOf("/reader/reader.js") < html.indexOf("</head>"))

        val noHead = BookServer.inject("<html><body><p>x</p></body></html>")
        assertTrue(noHead.startsWith("<html><head><meta name=\"viewport\""))

        val fixed = BookServer.inject("<html><head><meta name=\"viewport\" content=\"width=600, height=800\"/></head><body/></html>")
        assertFalse(fixed.contains("width=600"))
    }

    @Test
    fun servesOnlyTheBookAndReaderAssets() {
        val chapter = server.serve(Uri.parse(BookServer.chapterUrl("OEBPS/ch 1.xhtml", "g=1&at=o0")))
        assertEquals(200, chapter.statusCode)
        assertEquals("application/xhtml+xml", chapter.mimeType)
        assertTrue(chapter.responseHeaders["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(chapter.data.reader().readText().contains("/reader/reader.css"))

        assertEquals("image/png", server.serve(Uri.parse("${BookServer.ORIGIN}/book/OEBPS/img.png")).mimeType)
        assertEquals("body{}", server.serve(Uri.parse("${BookServer.ORIGIN}/reader/reader.css?v=3")).data.reader().readText())
        assertEquals(404, server.serve(Uri.parse("${BookServer.ORIGIN}/book/OEBPS/missing.png")).statusCode)
        assertEquals(404, server.serve(Uri.parse("https://example.com/book/OEBPS/img.png")).statusCode)
        assertEquals(404, server.serve(Uri.parse("${BookServer.ORIGIN}/reader/fonts/../../AndroidManifest.xml")).statusCode)
    }

    @Test
    fun cssReflectsSettings() {
        val css = ReaderCss.build(ReaderTheme.SEPIA, ReaderFont.SANS, 130, 1.85, 44, justify = false, twoColumns = true)
        assertTrue(css.contains("font-size: 130%"))
        assertTrue(css.contains("--rd-margin: 44"))
        assertTrue(css.contains("--rd-two: 1"))
        assertTrue(css.contains("line-height: 1.85 !important; font-family: sans-serif !important"))
        assertTrue(css.contains("text-align: start !important"))
        assertTrue(css.contains("#F6EEDC"))
        assertFalse(ReaderCss.build(ReaderTheme.WHITE, ReaderFont.PUBLISHER, 100, 1.5, 26, true, false).contains("font-family: serif"))
    }
}
