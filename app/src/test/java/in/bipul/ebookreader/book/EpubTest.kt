package `in`.bipul.ebookreader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class EpubTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(files: Map<String, String>): File {
        val file = tmp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
            }
        }
        return file
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private fun chapter(title: String) = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1 id="top">$title</h1><p>Text&nbsp;of $title.</p></body></html>"""

    @Test
    fun readsEpub3MetadataSpineNavAndCover() {
        val opf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>A Tale</dc:title><dc:creator>Ann Author</dc:creator><dc:creator>Bob Writer</dc:creator><dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = """<?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head><body>
              <nav epub:type="landmarks"><ol><li><a href="Text/ch2.xhtml">Not the TOC</a></li></ol></nav>
              <nav epub:type="toc"><ol>
                <li><a href="Text/ch%201.xhtml">One&nbsp;<span>Start</span></a>
                  <ol><li><a href="Text/ch%201.xhtml#top">Section 1.1</a></li></ol></li>
                <li><a href="Text/ch2.xhtml">Two</a></li>
              </ol></nav></body></html>"""
        val file = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/Text/ch 1.xhtml" to chapter("One"),
                "OEBPS/Text/ch2.xhtml" to chapter("Two"),
                "OEBPS/Images/cover.jpg" to "jpeg",
                "OEBPS/style.css" to "p{}",
            ),
        )
        EpubBook.open(file).use { book ->
            assertEquals("A Tale", book.title)
            assertEquals("Ann Author, Bob Writer", book.author)
            assertEquals(listOf("OEBPS/Text/ch 1.xhtml", "OEBPS/Text/ch2.xhtml"), book.spine.map { it.path })
            assertEquals(
                listOf(TocEntry("One Start", 0, null, 0), TocEntry("Section 1.1", 0, "top", 1), TocEntry("Two", 1, null, 0)),
                book.toc,
            )
            assertEquals("OEBPS/Images/cover.jpg", book.coverPath)
            assertEquals("text/css", book.mediaType("OEBPS/style.css"))
            assertEquals("OneText\u00A0of One.", book.chapterText(0))
            assertEquals(listOf("One Start", "Two"), book.chapterTitles())
        }
    }

    @Test
    fun fallsBackToNcxAndMetaCover() {
        val opf = """<?xml version="1.0"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
              <opf:metadata><dc:title>Old Book</dc:title><opf:meta name="cover" content="cov"/></opf:metadata>
              <opf:manifest>
                <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <opf:item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <opf:item id="cov" href="c.png" media-type="image/png"/>
              </opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="a"/></opf:spine>
            </opf:package>"""
        val ncx = """<?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><docTitle><text>Ignored</text></docTitle><navMap>
              <navPoint id="p1"><navLabel><text>Part I</text></navLabel><content src="a.html"/>
                <navPoint id="p2"><navLabel><text>Chapter 1</text></navLabel><content src="a.html#c1"/></navPoint>
              </navPoint></navMap></ncx>"""
        val file = epub(
            mapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf"),
                "content.opf" to opf,
                "toc.ncx" to ncx,
                "a.html" to chapter("A"),
                "c.png" to "png",
            ),
        )
        EpubBook.open(file).use { book ->
            assertEquals("Old Book", book.title)
            assertEquals("", book.author)
            assertEquals(listOf(TocEntry("Part I", 0, null, 0), TocEntry("Chapter 1", 0, "c1", 1)), book.toc)
            assertEquals("c.png", book.coverPath)
        }
    }

    @Test
    fun rejectsDrmButNotFontObfuscation() {
        val opf = """<package xmlns="http://www.idpf.org/2007/opf"><metadata/><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>"""
        fun encryption(algorithm: String) = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/></enc:EncryptedData></encryption>"""
        val base = mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/a.xhtml" to chapter("A"))

        val fonts = epub(base + ("META-INF/encryption.xml" to encryption("http://www.idpf.org/2008/embedding")))
        EpubBook.open(fonts).use { assertNull(it.coverPath) }
        fonts.delete()

        val drm = epub(base + ("META-INF/encryption.xml" to encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")))
        try {
            EpubBook.open(drm).close()
            fail("DRM-protected book opened")
        } catch (e: BookException) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test
    fun resolvesRelativeEncodedHrefs() {
        assertEquals("OEBPS/Images/a b.png" to null, Xml.resolve("OEBPS/Text", "../Images/a%20b.png"))
        assertEquals("OEBPS/Text/c.xhtml" to "n1", Xml.resolve("OEBPS/Text", "./c.xhtml#n1"))
        assertEquals("x+y.xhtml" to null, Xml.resolve("", "x+y.xhtml"))
        assertEquals("" to "top", Xml.resolve("OEBPS", "#top"))
    }
}
