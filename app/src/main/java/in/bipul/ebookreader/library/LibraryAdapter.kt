package `in`.bipul.ebookreader.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.bipul.ebookreader.R
import `in`.bipul.ebookreader.ui.dp
import kotlin.math.roundToInt

/** The library grid: an optional "Continue reading" card and section header, then every book's cover. */
class LibraryAdapter(
    private val library: Library,
    private val onOpen: (BookEntry) -> Unit,
    private val onOptions: (BookEntry, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Continue(val book: BookEntry) : Row
        data class Header(val text: String) : Row
        data class Book(val book: BookEntry) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(books: List<BookEntry>, continueReading: BookEntry?, header: String?) {
        rows = buildList {
            continueReading?.let { add(Row.Continue(it)) }
            header?.let { add(Row.Header(it)) }
            books.forEach { add(Row.Book(it)) }
        }
        @Suppress("NotifyDataSetChanged") // the whole list is re-sorted/filtered at once
        notifyDataSetChanged()
    }

    fun spanLookup(spanCount: () -> Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Book) 1 else spanCount()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Continue -> 0
        is Row.Header -> 1
        is Row.Book -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_continue
            1 -> R.layout.item_header
            else -> R.layout.item_book
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = rows[position]) {
            is Row.Header -> (v as TextView).text = row.text
            is Row.Continue -> {
                val book = row.book
                loadCover(v.findViewById(R.id.cover), book)
                v.findViewById<TextView>(R.id.title).text = book.title
                v.findViewById<TextView>(R.id.author).apply {
                    text = book.author
                    visibility = if (book.author.isEmpty()) View.GONE else View.VISIBLE
                }
                v.findViewById<ProgressBar>(R.id.progress).progress = (book.progress * 1000).roundToInt()
                v.findViewById<TextView>(R.id.percent).text = v.context.getString(R.string.percent_read, percent(book))
                v.setOnClickListener { onOpen(book) }
                v.setOnLongClickListener { onOptions(book, it); true }
            }
            is Row.Book -> {
                val book = row.book
                val ctx = v.context
                loadCover(v.findViewById(R.id.cover), book)
                v.findViewById<TextView>(R.id.title).text = book.title
                v.findViewById<TextView>(R.id.format).text = book.format.name
                v.findViewById<TextView>(R.id.subtitle).text = when {
                    book.finished -> ctx.getString(R.string.finished)
                    book.lastOpenedAt > 0 -> ctx.getString(R.string.percent_read, percent(book))
                    else -> book.author.ifEmpty { ctx.getString(R.string.not_started) }
                }
                v.findViewById<ProgressBar>(R.id.progress).apply {
                    progress = (book.progress * 1000).roundToInt()
                    visibility = if (book.lastOpenedAt > 0 && !book.finished) View.VISIBLE else View.INVISIBLE
                }
                v.findViewById<TextView>(R.id.badge).apply {
                    when {
                        book.finished -> { text = ctx.getString(R.string.badge_done); visibility = View.VISIBLE }
                        book.lastOpenedAt == 0L -> { text = ctx.getString(R.string.badge_new); visibility = View.VISIBLE }
                        else -> visibility = View.GONE
                    }
                }
                v.contentDescription = listOf(book.title, book.author).filter { it.isNotEmpty() }.joinToString(", ")
                v.setOnClickListener { onOpen(book) }
                v.setOnLongClickListener { onOptions(book, it); true }
            }
        }
    }

    private fun percent(book: BookEntry) = (book.progress * 100).toInt().coerceIn(0, 100)

    private fun loadCover(view: ImageView, book: BookEntry) {
        val ctx = view.context
        Covers.load(view, library.coverFile(book.id), { PlaceholderCover(book.title, book.author, ctx.resources.displayMetrics.density) }, ctx.dp(240))
    }
}
