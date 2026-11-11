package `in`.bipul.ebookreader.book

/**
 * Where each table-of-contents entry starts, as (chapter, character offset). Many books pack
 * several chapters into one file, so the file alone isn't precise enough to say which chapter
 * the reader is in or how far into the book a chapter starts.
 */
class TocIndex private constructor(
    val entries: List<TocEntry>,
    private val offsets: IntArray,
    private val lengths: Map<Int, Int>,
) {
    /** Index of the entry the position (chapter, offset) falls under, or -1 before the first. */
    fun indexAt(chapter: Int, offset: Int): Int {
        var best = -1
        for (i in entries.indices) {
            val e = entries[i]
            val before = e.chapter < chapter || (e.chapter == chapter && offsets[i] <= offset)
            if (!before) continue
            if (best < 0 || entries[best].chapter < e.chapter || (entries[best].chapter == e.chapter && offsets[best] <= offsets[i])) best = i
        }
        return best
    }

    fun titleAt(chapter: Int, offset: Int): String? = indexAt(chapter, offset).takeIf { it >= 0 }?.let { entries[it].title }

    fun offset(index: Int): Int = offsets[index]

    /** How far into its chapter's text entry [index] starts, 0..1. */
    fun fractionInChapter(index: Int): Double {
        val length = lengths[entries[index].chapter] ?: return 0.0
        return if (length > 0) offsets[index].toDouble() / length else 0.0
    }

    companion object {
        fun build(book: ReflowableBook): TocIndex {
            val entries = book.toc
            val offsets = IntArray(entries.size)
            val lengths = HashMap<Int, Int>()
            entries.withIndex().filter { it.value.anchor != null }.groupBy { it.value.chapter }.forEach { (chapter, list) ->
                val html = runCatching { book.open(book.spine[chapter].path)?.use { Xml.decode(it.readBytes()) } }.getOrNull() ?: return@forEach
                val (found, length) = HtmlText.anchorOffsets(html, list.mapNotNull { it.value.anchor })
                lengths[chapter] = length
                list.forEach { (i, e) -> offsets[i] = found[e.anchor] ?: 0 }
            }
            return TocIndex(entries, offsets, lengths)
        }

        fun empty(book: ReflowableBook) = TocIndex(book.toc, IntArray(book.toc.size), emptyMap())
    }
}
