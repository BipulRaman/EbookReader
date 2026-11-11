package `in`.bipul.ebookreader.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import `in`.bipul.ebookreader.book.BookException
import `in`.bipul.ebookreader.book.BookFormat
import `in`.bipul.ebookreader.book.EpubBook
import `in`.bipul.ebookreader.book.TxtBook
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Adds a book from a content URI: copies it into private storage, reads its metadata and cover. */
object Importer {
    private const val COVER_MAX_HEIGHT = 720

    class Result(val entry: BookEntry, val alreadyInLibrary: Boolean)

    fun import(context: Context, uri: Uri): Result {
        val library = Library.get(context)
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val tmp = File.createTempFile("import", ".tmp", library.booksDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri) ?: throw BookException("Couldn't open the file")
            DigestInputStream(input, digest).use { inp -> FileOutputStream(tmp).use { inp.copyTo(it, 64 * 1024) } }
            if (tmp.length() == 0L) throw BookException("The file is empty")
            val id = digest.digest().take(8).joinToString("") { "%02x".format(it) }

            library.get(id)?.let { return Result(it, alreadyInLibrary = true) }

            val format = detectFormat(tmp, name, resolver.getType(uri))
                ?: throw BookException("Unsupported file. EPUB, PDF and TXT books can be added.")
            val dir = library.bookDir(id)
            dir.deleteRecursively()
            dir.mkdirs()
            val bookFile = File(dir, "book.${format.extension}")
            if (!tmp.renameTo(bookFile)) {
                tmp.copyTo(bookFile, overwrite = true)
            }
            try {
                val fallbackTitle = titleFromFileName(name)
                val (title, author) = when (format) {
                    BookFormat.EPUB -> readEpub(bookFile, library.coverFile(id), fallbackTitle)
                    BookFormat.PDF -> readPdf(bookFile, library.coverFile(id), fallbackTitle)
                    BookFormat.TXT -> readTxt(bookFile, File(dir, "text"), fallbackTitle)
                }
                val entry = BookEntry(
                    id = id,
                    format = format,
                    title = title,
                    author = author,
                    fileSize = bookFile.length(),
                    addedAt = System.currentTimeMillis(),
                )
                library.add(entry)
                return Result(entry, alreadyInLibrary = false)
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e as? BookException ?: BookException("This book could not be read")
            }
        } finally {
            tmp.delete()
        }
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Identifies the format by content first (magic bytes), then by name and MIME type. */
    fun detectFormat(file: File, name: String?, mime: String?): BookFormat? {
        val head = ByteArray(64)
        val n = file.inputStream().use { it.read(head) }
        val ascii = String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1)
        if (ascii.startsWith("%PDF")) return BookFormat.PDF
        if (ascii.startsWith("PK\u0003\u0004")) {
            // EPUBs are ZIPs; most store "mimetype" first, but some tools don't, so also trust the name.
            if (ascii.contains("application/epub+zip") || name?.endsWith(".epub", true) == true || mime == BookFormat.EPUB.mimeType) {
                return BookFormat.EPUB
            }
            return null
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        if (ext == "txt" || ext == "text" || mime == "text/plain") return BookFormat.TXT
        if (mime?.startsWith("text/") == true && looksLikeText(head, n)) return BookFormat.TXT
        return null
    }

    private fun looksLikeText(head: ByteArray, n: Int): Boolean = (0 until n).none { head[it] == 0.toByte() } || (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte())

    fun titleFromFileName(name: String?): String {
        val base = name?.substringBeforeLast('.')?.replace(Regex("[_]+"), " ")?.trim().orEmpty()
        return base.ifEmpty { "Untitled" }
    }

    private fun readEpub(file: File, coverOut: File, fallbackTitle: String): Pair<String, String> {
        EpubBook.open(file).use { book ->
            book.coverPath?.let { path ->
                runCatching {
                    val bytes = book.open(path)?.use { it.readBytes() } ?: return@runCatching
                    saveCover(decodeSampled(bytes), coverOut)
                }
            }
            return book.title.ifBlank { fallbackTitle } to book.author
        }
    }

    private fun readPdf(file: File, coverOut: File, fallbackTitle: String): Pair<String, String> {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) throw BookException("This PDF has no pages")
                    renderer.openPage(0).use { page ->
                        val height = COVER_MAX_HEIGHT
                        val width = (height * page.width.toFloat() / page.height).toInt().coerceIn(1, height * 2)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        saveCover(bitmap, coverOut)
                    }
                }
            }
        } catch (e: SecurityException) {
            throw BookException("This PDF is password-protected and can't be opened")
        }
        return fallbackTitle to ""
    }

    private fun readTxt(file: File, outDir: File, fallbackTitle: String): Pair<String, String> {
        val text = TxtBook.decode(file.readBytes())
        val meta = TxtBook.meta(text)
        val title = meta.title ?: fallbackTitle
        val author = meta.author.orEmpty()
        TxtBook.convert(text, title, author, outDir)
        return title to author
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= COVER_MAX_HEIGHT) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun saveCover(bitmap: Bitmap?, out: File) {
        bitmap ?: return
        val scaled = if (bitmap.height > COVER_MAX_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * COVER_MAX_HEIGHT.toFloat() / bitmap.height).toInt().coerceAtLeast(1), COVER_MAX_HEIGHT, true)
        } else {
            bitmap
        }
        // Covers with transparency would turn black as JPEG; flatten them onto white.
        val opaque = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                android.graphics.Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            }
        } else {
            scaled
        }
        FileOutputStream(out).use { opaque.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }
}
