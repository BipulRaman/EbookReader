package `in`.bipul.ebookreader.book

import java.io.Closeable
import java.io.InputStream

enum class BookFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
    TXT("txt", "text/plain");

    companion object {
        fun fromName(name: String?): BookFormat? = entries.firstOrNull { it.name == name }
    }
}

/** One reading-order document of a reflowable book. [size] (bytes) weighs it for book-wide progress. */
data class SpineItem(val path: String, val mediaType: String, val size: Long)

/** A table-of-contents entry: [chapter] is a spine index, [anchor] an optional element id in it. */
data class TocEntry(val title: String, val chapter: Int, val anchor: String?, val depth: Int)

/**
 * A reflowable book (EPUB, or a TXT file converted to XHTML chapters): a list of XHTML documents
 * in reading order plus the resources (images, CSS, fonts) they reference, addressed by path.
 */
interface ReflowableBook : Closeable {
    val title: String
    val author: String
    val spine: List<SpineItem>
    val toc: List<TocEntry>

    /** Opens the resource at [path] (relative to the book root, no leading slash), or null. */
    fun open(path: String): InputStream?

    fun mediaType(path: String): String? = spine.firstOrNull { it.path == path }?.mediaType

    fun chapterOf(path: String): Int = spine.indexOfFirst { it.path == path }

    /** The plain text of chapter [index], as the reader page sees it (used by search). */
    fun chapterText(index: Int): String {
        val bytes = open(spine[index].path)?.use { it.readBytes() } ?: return ""
        return HtmlText.bodyText(Xml.decode(bytes))
    }

    /** For every spine index, the title of the TOC entry it falls under (or null before the first). */
    fun chapterTitles(): List<String?> {
        val titles = arrayOfNulls<String>(spine.size)
        for (entry in toc) {
            if (entry.chapter in titles.indices && titles[entry.chapter] == null) titles[entry.chapter] = entry.title
        }
        var last: String? = null
        for (i in titles.indices) {
            if (titles[i] == null) titles[i] = last else last = titles[i]
        }
        return titles.toList()
    }
}
