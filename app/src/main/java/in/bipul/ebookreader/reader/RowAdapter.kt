package `in`.bipul.ebookreader.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import `in`.bipul.ebookreader.R
import `in`.bipul.ebookreader.ui.dp

/** One entry in the reader's lists (contents, bookmarks, notes, search results). */
class Row(
    val title: CharSequence,
    val subtitle: CharSequence? = null,
    val note: CharSequence? = null,
    val meta: String? = null,
    val depth: Int = 0,
    val current: Boolean = false,
    val barColor: Int? = null,
    val onClick: () -> Unit = {},
    val onLongClick: (() -> Unit)? = null,
)

class RowAdapter(private val rows: List<Row>, private val theme: ReaderTheme) : BaseAdapter() {
    override fun getCount() = rows.size

    override fun getItem(position: Int) = rows[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        val row = rows[position]
        val ctx = v.context
        val title = v.findViewById<TextView>(R.id.row_title)
        val subtitle = v.findViewById<TextView>(R.id.row_subtitle)
        val note = v.findViewById<TextView>(R.id.row_note)
        val meta = v.findViewById<TextView>(R.id.row_meta)
        val bar = v.findViewById<View>(R.id.row_bar)

        title.text = row.title
        title.setTextColor(if (row.current) theme.link else theme.text)
        title.paint.isFakeBoldText = row.current
        subtitle.text = row.subtitle
        subtitle.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        subtitle.setTextColor(theme.secondary)
        note.text = row.note
        note.visibility = if (row.note.isNullOrEmpty()) View.GONE else View.VISIBLE
        note.setTextColor(theme.text)
        meta.text = row.meta
        meta.visibility = if (row.meta == null) View.GONE else View.VISIBLE
        meta.setTextColor(theme.secondary)
        bar.visibility = if (row.barColor != null) View.VISIBLE else View.GONE
        row.barColor?.let(bar::setBackgroundColor)
        v.setPaddingRelative(ctx.dp(20) + ctx.dp(18) * row.depth.coerceAtMost(4), v.paddingTop, v.paddingEnd, v.paddingBottom)
        v.setOnClickListener { row.onClick() }
        if (row.onLongClick != null) v.setOnLongClickListener { row.onLongClick.invoke(); true } else v.setOnLongClickListener(null)
        return v
    }
}
