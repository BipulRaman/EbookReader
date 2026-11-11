package `in`.bipul.ebookreader.library

import `in`.bipul.ebookreader.book.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun locatorRoundTrips() {
        assertEquals(Locator(3, 1200), Locator.parse(Locator(3, 1200).toString()))
        assertNull(Locator.parse("3"))
        assertNull(Locator.parse("-1:4"))
        assertNull(Locator.parse(null))
    }

    @Test
    fun entryJsonRoundTrips() {
        val e = BookEntry("abc", BookFormat.PDF, "T", "A", 42, 1, 2, 0.5f, Locator(7, 0), finished = true)
        assertEquals(e, BookEntry.fromJson(e.toJson()))
        assertEquals(null, BookEntry.fromJson(e.toJson().put("format", "DOC")))
    }

    @Test
    fun annotationsPersistAndFindBookmarksOnAPage() {
        val dir = tmp.newFolder()
        val a = Annotations(dir)
        a.bookmarks.add(Bookmark(1, 2, 500, "label", 0.3f, 9))
        a.highlights.add(Highlight(2, 1, 10, 20, "text", 1, "note", 0.1f, 9))
        a.save()
        val b = Annotations(dir)
        assertEquals(a.bookmarks, b.bookmarks)
        assertEquals(a.highlights, b.highlights)
        assertEquals(1L, b.bookmarkIn(2, 400, 501)?.id)
        assertNull(b.bookmarkIn(2, 501, 900))
        assertNull(b.bookmarkIn(1, 400, 900))
    }

    @Test
    fun detectsFormatsByContent() {
        fun file(bytes: ByteArray) = File(tmp.root, "f${bytes.size}${bytes.hashCode()}").apply { writeBytes(bytes) }
        assertEquals(BookFormat.PDF, Importer.detectFormat(file("%PDF-1.7 ...".toByteArray()), "x.bin", null))
        val zipHead = "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1) + ByteArray(26) + "mimetypeapplication/epub+zip".toByteArray()
        assertEquals(BookFormat.EPUB, Importer.detectFormat(file(zipHead), "download", "application/octet-stream"))
        assertNull(Importer.detectFormat(file("PK\u0003\u0004other".toByteArray(Charsets.ISO_8859_1)), "a.zip", null))
        assertEquals(BookFormat.TXT, Importer.detectFormat(file("Once upon a time".toByteArray()), "story.txt", null))
        assertNull(Importer.detectFormat(file(byteArrayOf(1, 0, 2, 0)), "data.bin", "application/octet-stream"))
        assertEquals("My Book", Importer.titleFromFileName("My_Book.epub"))
        assertEquals("Untitled", Importer.titleFromFileName(null))
    }
}
