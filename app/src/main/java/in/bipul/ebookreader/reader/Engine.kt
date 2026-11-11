package `in`.bipul.ebookreader.reader

import android.view.View
import `in`.bipul.ebookreader.library.Locator

/** Where the reader is: [chapter] (page index for PDFs), the page within it, and the text it shows. */
data class Position(
    val chapter: Int,
    val page: Int,
    val pageCount: Int,
    /** Offsets of the page's first character and of the next page's first character. */
    val start: Int,
    val end: Int,
    val chapterLength: Int,
    /** Book-wide progress at the start and at the end of this page, 0..1. */
    val progressStart: Float,
    val progressEnd: Float,
    val lastPage: Boolean,
) {
    val locator get() = Locator(chapter, start)
}

/** The page renderer: a WebView for EPUB/TXT, PdfRenderer pages for PDF. */
interface ReaderEngine {
    val view: View
    val position: Position?

    fun next()
    fun prev()
    fun goTo(locator: Locator)

    /** Jump to a book-wide fraction (the progress slider). */
    fun goToFraction(fraction: Float)

    /** The theme or text settings changed. */
    fun applySettings()

    /** Book-wide fraction to (chapter, fraction-within-chapter), for the slider's preview label. */
    fun chapterAt(fraction: Float): Int

    fun destroy()
}

/** What the engines report back to the reader screen. */
interface EngineHost {
    fun onReady()
    fun onPosition(position: Position)
    fun onMenuTap()
    fun onCornerTap()
    fun onEndOfBook()
    fun onError(message: String)
}
