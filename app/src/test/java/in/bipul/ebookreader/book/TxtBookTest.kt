package `in`.bipul.ebookreader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TxtBookTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun decodesUtf8AndFallsBackToWindows1252() {
        assertEquals("café — ok", TxtBook.decode("café — ok".toByteArray(Charsets.UTF_8)))
        assertEquals("café “x”", TxtBook.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x93.toByte(), 0x78, 0x94.toByte())))
        assertEquals("bom", TxtBook.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom".toByteArray()))
    }

    @Test
    fun readsGutenbergHeader() {
        val meta = TxtBook.meta("The Project Gutenberg eBook\n\nTitle: Alice's Adventures\nAuthor: Lewis Carroll\n\nRelease date: x")
        assertEquals("Alice's Adventures", meta.title)
        assertEquals("Lewis Carroll", meta.author)
    }

    @Test
    fun splitsChaptersAtHeadingsAndUnwrapsParagraphs() {
        val text = """
            Preface words that
            wrap over lines.

            CHAPTER I.
            Down the Rabbit-Hole

            Alice was beginning to get very tired of sitting by her sister
            on the bank, & of having nothing to do.

            Part of the reason was <boredom>.

            CHAPTER II.

            The Pool of Tears.
        """.trimIndent()
        val dir = File(tmp.root, "text")
        val book = TxtBook.convert(text, "Alice", "Lewis Carroll", dir)

        assertEquals(3, book.spine.size)
        assertEquals(listOf("CHAPTER I. Down the Rabbit-Hole", "CHAPTER II."), book.toc.map { it.title })
        assertEquals(listOf(1, 2), book.toc.map { it.chapter })
        assertEquals("h0", book.toc[0].anchor)
        assertEquals("Preface words that wrap over lines.", book.chapterText(0).trim())
        val ch1 = book.chapterText(1)
        assertTrue(ch1.contains("sitting by her sister on the bank, & of having nothing to do."))
        // A sentence starting with "Part of" is body text, not a heading.
        assertTrue(ch1.contains("Part of the reason was <boredom>."))
        val html = File(dir, book.spine[1].path).readText()
        assertTrue(html.contains("&lt;boredom&gt;"))
        assertTrue(html.contains("<h2 id=\"h0\">"))

        val reopened = TxtBook.open(dir)
        assertEquals("Alice", reopened.title)
        assertEquals(book.spine, reopened.spine)
        assertFalse(reopened.open("../secret") != null)
    }

    @Test
    fun splitsLongTextWithoutHeadings() {
        val para = "word ".repeat(200).trim()
        val text = (1..400).joinToString("\n\n") { para }
        val book = TxtBook.convert(text, "Long", "", File(tmp.root, "long"))
        assertTrue(book.spine.size > 5)
        assertTrue(book.toc.isEmpty())
    }

    @Test
    fun underscoresBecomeItalics() {
        assertEquals("I'm sure <i>I</i> shan't", TxtBook.inline("I'm sure _I_ shan't"))
        assertEquals("(<i>with love</i>).", TxtBook.inline("(_with love_)."))
        assertEquals("snake_case_name and _ alone _", TxtBook.inline("snake_case_name and _ alone _"))
    }
}
