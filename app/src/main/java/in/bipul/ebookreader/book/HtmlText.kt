package `in`.bipul.ebookreader.book

/**
 * Plain text of XHTML chapters, for search. It must see the same characters as the reader page's
 * script (reader.js), which walks the text nodes under `<body>`: search hits are addressed as "the
 * n-th match in chapter c" and the page finds that same n-th match in its DOM.
 */
object HtmlText {
    private val BODY_OPEN = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_CLOSE = Regex("</body\\s*>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val SCRIPT_STYLE = Regex("<(script|style)\\b[^>]*?(/>|>[\\s\\S]*?</\\1\\s*>)", RegexOption.IGNORE_CASE)
    private val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val TAG = Regex("<[^>]*>")

    fun bodyText(html: String): String {
        val open = BODY_OPEN.find(html)
        val start = open?.range?.last?.plus(1) ?: 0
        val end = BODY_CLOSE.find(html, start)?.range?.first ?: html.length
        return text(html.substring(start, end))
    }

    /** Text of an XHTML fragment (markup removed, entities decoded). */
    fun text(fragment: String): String {
        var s = COMMENT.replace(fragment, "")
        s = SCRIPT_STYLE.replace(s, "")
        s = CDATA.replace(s) { Xml.escape(it.groupValues[1]) }
        s = TAG.replace(s, "")
        return Xml.decodeEntities(s)
    }

    /**
     * Character offset (as reader.js counts them) of the element with this id in a chapter, or -1.
     * Also returns the chapter's text length.
     */
    fun anchorOffsets(html: String, ids: Collection<String>): Pair<Map<String, Int>, Int> {
        val open = BODY_OPEN.find(html)
        val start = open?.range?.last?.plus(1) ?: 0
        val end = BODY_CLOSE.find(html, start)?.range?.first ?: html.length
        val body = html.substring(start, end)
        val found = HashMap<String, Int>()
        for (id in ids) {
            val m = Regex("\\s(?:id|name)\\s*=\\s*[\"']" + Regex.escape(id) + "[\"']").find(body) ?: continue
            val tagStart = body.lastIndexOf('<', m.range.first)
            if (tagStart >= 0) found[id] = text(body.substring(0, tagStart)).length
        }
        return found to text(body).length
    }

    /** Whitespace runs collapsed to one space and soft hyphens dropped (same rule as reader.js). */
    fun normalize(text: String): String = normalizeMapped(text, null)

    private fun normalizeMapped(text: String, map: IntArray?): String {
        val sb = StringBuilder(text.length)
        var space = false
        for ((i, c) in text.withIndex()) {
            if (c == '\u00AD') continue
            if (c.isWhitespace() || c == '\u00A0') {
                if (!space) {
                    map?.set(sb.length, i)
                    sb.append(' ')
                }
                space = true
            } else {
                map?.set(sb.length, i)
                sb.append(c)
                space = false
            }
        }
        return sb.toString()
    }

    fun lower(text: String): String {
        val chars = CharArray(text.length) { Character.toLowerCase(text[it]) }
        return String(chars)
    }

    /** [offset] is where the match starts in the original (un-normalised) text. */
    data class Match(val occurrence: Int, val snippet: String, val matchStart: Int, val matchLength: Int, val offset: Int = 0)

    /** Non-overlapping, case-insensitive matches of [query] in [text], with a snippet around each. */
    fun find(text: String, query: String, limit: Int = Int.MAX_VALUE): List<Match> {
        val q = lower(normalize(query).trim())
        if (q.isEmpty()) return emptyList()
        val map = IntArray(text.length + 1)
        val cased = normalizeMapped(text, map)
        val hay = lower(cased)
        val out = ArrayList<Match>()
        var from = 0
        var n = 0
        while (out.size < limit) {
            val i = hay.indexOf(q, from)
            if (i < 0) break
            val s = maxOf(0, i - 40)
            val e = minOf(cased.length, i + q.length + 60)
            val prefix = if (s > 0) "…" else ""
            val snippet = prefix + cased.substring(s, e).trim() + if (e < cased.length) "…" else ""
            val leading = cased.substring(s, i).length - cased.substring(s, i).trimStart().length
            out.add(Match(n, snippet, prefix.length + (i - s) - leading, q.length, map[i]))
            n++
            from = i + q.length
        }
        return out
    }
}
