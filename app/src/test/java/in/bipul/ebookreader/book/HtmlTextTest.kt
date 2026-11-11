package `in`.bipul.ebookreader.book

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun bodyTextKeepsOnlyRenderedText() {
        val html = """<?xml version="1.0"?><html><head><title>Not this</title><style>p{}</style></head>
            <body class="x"><!-- comment --><p>Fish &amp; chips&#8212;<b>hot</b></p><script>var a = "<p>";</script><p>caf&eacute;&#x21;</p><![CDATA[1 < 2]]></body></html>"""
        assertEquals("Fish & chips\u2014hotcafé!1 < 2", HtmlText.bodyText(html))
    }

    @Test
    fun findsNonOverlappingMatchesAcrossWhitespaceAndCase() {
        val text = "It was the best of times,\n  it was the WORST of times; it was\u00A0the age"
        val hits = HtmlText.find(text, "it  was the", 10)
        assertEquals(listOf(0, 1, 2), hits.map { it.occurrence })
        val second = hits[1]
        assertEquals("it was the", second.snippet.substring(second.matchStart, second.matchStart + second.matchLength).lowercase())
        assertEquals(1, HtmlText.find("aaaa", "aa", 1).size)
        assertEquals(2, HtmlText.find("aaaa", "aa").size)
        assertEquals(1, HtmlText.find("co\u00ADoperate", "cooperate").size)
        assertEquals(listOf(7, 19), HtmlText.find("one\n\n  two   three two", "two").map { it.offset })
    }

    @Test
    fun anchorOffsetsCountTextBeforeEachChapterHeading() {
        val html = "<html><body><h2 id=\"c1\">One</h2><p>Some &amp; text</p><h2 class=\"x\" id='c2'>Two</h2><p>End</p></body></html>"
        val (offsets, length) = HtmlText.anchorOffsets(html, listOf("c1", "c2", "missing"))
        assertEquals(mapOf("c1" to 0, "c2" to "OneSome & text".length), offsets)
        assertEquals("OneSome & textTwoEnd".length, length)
    }
}
