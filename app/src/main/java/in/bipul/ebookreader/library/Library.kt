package `in`.bipul.ebookreader.library

import android.content.Context
import android.util.AtomicFile
import `in`.bipul.ebookreader.book.BookFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A reading position: a chapter (spine index; the page index for PDFs) and a character offset in it. */
data class Locator(val chapter: Int, val offset: Int) {
    override fun toString() = "$chapter:$offset"

    companion object {
        fun parse(s: String?): Locator? {
            val parts = s?.split(':') ?: return null
            if (parts.size != 2) return null
            val chapter = parts[0].toIntOrNull() ?: return null
            val offset = parts[1].toIntOrNull() ?: return null
            return if (chapter >= 0 && offset >= 0) Locator(chapter, offset) else null
        }
    }
}

data class BookEntry(
    /** First 16 hex digits of the file's SHA-256: re-adding the same file finds the same book. */
    val id: String,
    val format: BookFormat,
    val title: String,
    val author: String,
    val fileSize: Long,
    val addedAt: Long,
    val lastOpenedAt: Long = 0,
    /** Book-wide progress, 0..1. */
    val progress: Float = 0f,
    val position: Locator? = null,
    val finished: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("format", format.name)
        .put("title", title)
        .put("author", author)
        .put("fileSize", fileSize)
        .put("addedAt", addedAt)
        .put("lastOpenedAt", lastOpenedAt)
        .put("progress", progress.toDouble())
        .put("position", position?.toString())
        .put("finished", finished)

    companion object {
        fun fromJson(o: JSONObject): BookEntry? {
            val format = BookFormat.fromName(o.optString("format")) ?: return null
            return BookEntry(
                id = o.getString("id"),
                format = format,
                title = o.optString("title"),
                author = o.optString("author"),
                fileSize = o.optLong("fileSize"),
                addedAt = o.optLong("addedAt"),
                lastOpenedAt = o.optLong("lastOpenedAt"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                position = Locator.parse(o.optString("position").takeIf { o.has("position") && !o.isNull("position") }),
                finished = o.optBoolean("finished"),
            )
        }
    }
}

/**
 * The library: every imported book's metadata in one small JSON file. Each book's files live in
 * `files/books/<id>/` (the book, its cover, its annotations, and converted text for TXT books).
 */
class Library private constructor(private val root: File) {
    private val file = AtomicFile(File(root, "library.json"))
    private val books = LinkedHashMap<String, BookEntry>()
    private val listeners = ArrayList<() -> Unit>()

    init {
        load()
    }

    val booksDir: File get() = File(root, "books").apply { mkdirs() }

    fun bookDir(id: String) = File(booksDir, id)

    fun bookFile(entry: BookEntry) = File(bookDir(entry.id), "book.${entry.format.extension}")

    fun coverFile(id: String) = File(bookDir(id), "cover.jpg")

    @Synchronized fun all(): List<BookEntry> = books.values.toList()

    @Synchronized fun get(id: String): BookEntry? = books[id]

    fun add(entry: BookEntry) {
        synchronized(this) {
            books[entry.id] = entry
            save()
        }
        notifyChanged()
    }

    fun update(id: String, notify: Boolean = true, change: (BookEntry) -> BookEntry) {
        synchronized(this) {
            val current = books[id] ?: return
            books[id] = change(current)
            save()
        }
        if (notify) notifyChanged()
    }

    fun remove(id: String) {
        synchronized(this) {
            books.remove(id) ?: return
            save()
        }
        bookDir(id).deleteRecursively()
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }

    private fun load() {
        val text = runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrNull() ?: return
        val array = runCatching { JSONObject(text).getJSONArray("books") }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i)?.let(BookEntry::fromJson) ?: continue
            books[entry.id] = entry
        }
    }

    private fun save() {
        val array = JSONArray()
        books.values.forEach { array.put(it.toJson()) }
        val bytes = JSONObject().put("version", 1).put("books", array).toString().toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    companion object {
        @Volatile private var instance: Library? = null

        fun get(context: Context): Library = instance ?: synchronized(this) {
            instance ?: Library(context.applicationContext.filesDir).also { instance = it }
        }
    }
}
