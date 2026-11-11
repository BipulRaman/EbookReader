package `in`.bipul.ebookreader.library

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val id: Long, val chapter: Int, val offset: Int, val label: String, val progress: Float, val createdAt: Long)

/** A highlight over characters [start, end) of a chapter's text (same offsets reader.js uses). */
data class Highlight(
    val id: Long,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String,
    val progress: Float,
    val createdAt: Long,
)

/** A book's bookmarks, highlights and notes, stored next to it as `annotations.json`. */
class Annotations(dir: File) {
    private val file = AtomicFile(File(dir, "annotations.json"))
    val bookmarks = ArrayList<Bookmark>()
    val highlights = ArrayList<Highlight>()

    init {
        runCatching {
            val o = JSONObject(String(file.readFully(), Charsets.UTF_8))
            o.optJSONArray("bookmarks")?.let { a ->
                for (i in 0 until a.length()) {
                    val b = a.getJSONObject(i)
                    bookmarks.add(Bookmark(b.getLong("id"), b.getInt("chapter"), b.getInt("offset"), b.optString("label"), b.optDouble("progress", 0.0).toFloat(), b.optLong("createdAt")))
                }
            }
            o.optJSONArray("highlights")?.let { a ->
                for (i in 0 until a.length()) {
                    val h = a.getJSONObject(i)
                    highlights.add(
                        Highlight(
                            h.getLong("id"), h.getInt("chapter"), h.getInt("start"), h.getInt("end"), h.optString("text"),
                            h.optInt("color"), h.optString("note"), h.optDouble("progress", 0.0).toFloat(), h.optLong("createdAt"),
                        ),
                    )
                }
            }
        }
        sort()
    }

    fun newId(): Long {
        val used = (bookmarks.map { it.id } + highlights.map { it.id }).toSet()
        var id = System.currentTimeMillis()
        while (id in used) id++
        return id
    }

    fun sort() {
        bookmarks.sortWith(compareBy({ it.chapter }, { it.offset }))
        highlights.sortWith(compareBy({ it.chapter }, { it.start }))
    }

    fun save() {
        sort()
        val o = JSONObject()
        o.put("bookmarks", JSONArray().apply {
            bookmarks.forEach {
                put(JSONObject().put("id", it.id).put("chapter", it.chapter).put("offset", it.offset).put("label", it.label).put("progress", it.progress.toDouble()).put("createdAt", it.createdAt))
            }
        })
        o.put("highlights", JSONArray().apply {
            highlights.forEach {
                put(
                    JSONObject().put("id", it.id).put("chapter", it.chapter).put("start", it.start).put("end", it.end).put("text", it.text)
                        .put("color", it.color).put("note", it.note).put("progress", it.progress.toDouble()).put("createdAt", it.createdAt),
                )
            }
        })
        val out = file.startWrite()
        try {
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    /** The bookmark inside [chapter] at an offset in [start, end), if any (a page's range). */
    fun bookmarkIn(chapter: Int, start: Int, end: Int): Bookmark? =
        bookmarks.firstOrNull { it.chapter == chapter && it.offset >= start && (it.offset < end || start == end && it.offset == start) }
}
