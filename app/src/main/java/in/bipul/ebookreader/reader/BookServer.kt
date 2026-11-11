package `in`.bipul.ebookreader.reader

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import `in`.bipul.ebookreader.book.ReflowableBook
import `in`.bipul.ebookreader.book.Xml
import java.io.ByteArrayInputStream

/**
 * Serves a book to the reader's WebView from `https://appassets.androidplatform.net/` (a host
 * reserved for apps, never on the network). `/book/<path>` reads straight from the book;
 * `/reader/...` serves the reader's own script, stylesheet and fonts. Nothing else loads: the
 * app has no internet permission, scripts in books are stripped, and a CSP allows only our own.
 */
class BookServer(
    private val book: ReflowableBook,
    private val assets: AssetManager,
    private val css: () -> String,
) {
    fun serve(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != HOST) return notFound()
        val path = uri.path.orEmpty()
        return try {
            when {
                path == "/reader/reader.css" -> ok("text/css", css().toByteArray(), noStore = true)
                path == "/reader/reader.js" -> ok("text/javascript", assets.open("reader/reader.js").use { it.readBytes() })
                path.startsWith("/reader/fonts/") && !path.contains("..") -> {
                    WebResourceResponse("font/ttf", null, 200, "OK", headers(), assets.open(path.removePrefix("/")))
                }
                path.startsWith(BOOK_PREFIX) -> serveBook(Xml.normalize(path.removePrefix(BOOK_PREFIX)))
                else -> notFound()
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun serveBook(path: String): WebResourceResponse {
        val chapter = book.chapterOf(path)
        val type = book.mediaType(path) ?: mimeFromExtension(path)
        if (chapter >= 0 && (type.contains("html") || type.contains("xml"))) {
            val bytes = book.open(path)?.use { it.readBytes() } ?: return notFound()
            val html = inject(Xml.decode(bytes))
            // XHTML must be parsed as XML (self-closing <a id="x"/> breaks the HTML parser).
            val mime = if (type == "text/html") "text/html" else "application/xhtml+xml"
            return ok(mime, html.toByteArray(Charsets.UTF_8))
        }
        val stream = book.open(path) ?: return notFound()
        return WebResourceResponse(type, null, 200, "OK", headers(), stream)
    }

    private fun ok(mime: String, bytes: ByteArray, noStore: Boolean = false) =
        WebResourceResponse(mime, "utf-8", 200, "OK", headers(noStore), ByteArrayInputStream(bytes))

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(), ByteArrayInputStream(ByteArray(0)))

    private fun headers(noStore: Boolean = false): Map<String, String> = buildMap {
        put("Content-Security-Policy", CSP)
        put("Cache-Control", if (noStore) "no-store" else "max-age=600")
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val BOOK_PREFIX = "/book/"

        private const val CSP = "default-src 'self' data: blob:; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'"

        private val SCRIPT = Regex("<script\\b[^>]*?(/>|>[\\s\\S]*?</script\\s*>)", RegexOption.IGNORE_CASE)
        private val VIEWPORT = Regex("<meta\\b[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE)
        private val HEAD_CLOSE = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        private val BODY_OPEN = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_OPEN = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val XML_DECL = Regex("^\\s*<\\?xml[^>]*\\?>")

        /** The book's chapter document with its scripts removed and the reader's CSS + JS added. */
        fun inject(source: String): String {
            var html = SCRIPT.replace(source, "")
            // A page is always exactly one screen at scale 1, also across rotations.
            html = VIEWPORT.replace(html, "")
            // Inline event handlers (onclick=...) need no stripping: the CSP blocks inline script.
            // The text is re-encoded as UTF-8, so the declaration has to say so.
            html = XML_DECL.replaceFirst(html, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            val tags = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/>" +
                "<link id=\"rd-style\" rel=\"stylesheet\" type=\"text/css\" href=\"/reader/reader.css\"/>" +
                "<script type=\"text/javascript\" src=\"/reader/reader.js\"></script>"
            HEAD_CLOSE.find(html)?.let { return html.replaceRange(it.range.first, it.range.first, tags) }
            BODY_OPEN.find(html)?.let { return html.replaceRange(it.range.first, it.range.first, "<head>$tags</head>") }
            HTML_OPEN.find(html)?.let { return html.replaceRange(it.range.last + 1, it.range.last + 1, "<head>$tags</head>") }
            return tags + html
        }

        fun chapterUrl(path: String, query: String): String {
            val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
            return "$ORIGIN$BOOK_PREFIX$encoded?$query"
        }

        fun mimeFromExtension(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "xhtml", "xht", "xml" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "mp4", "m4v" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
