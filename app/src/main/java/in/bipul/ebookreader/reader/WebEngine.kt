package `in`.bipul.ebookreader.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import `in`.bipul.ebookreader.BuildConfig
import `in`.bipul.ebookreader.book.ReflowableBook
import `in`.bipul.ebookreader.book.TocEntry
import `in`.bipul.ebookreader.book.Xml
import `in`.bipul.ebookreader.library.Highlight
import `in`.bipul.ebookreader.library.Locator
import org.json.JSONArray
import org.json.JSONObject

/** A text selection in the current chapter: characters [start, end). */
data class TextSelection(val chapter: Int, val start: Int, val end: Int, val text: String)

/** A tapped link: inside the book (with its chapter document path) or out to the web. */
data class BookLink(val href: String, val internal: Boolean, val path: String?, val anchor: String?, val note: String?)

/** Callbacks only the reflowable (EPUB/TXT) engine makes. */
interface WebHost : EngineHost {
    fun onHighlightTap(id: Long)
    fun onLink(link: BookLink)
    fun onImage(path: String)

    /** phase 0 = start, 1 = move (delta is the drag as a fraction of the page height), 2 = end. */
    fun onBrightnessDrag(phase: Int, delta: Float)
    fun onSelectionAction(action: Int, selection: TextSelection)
}

/** Shows EPUB and TXT books: one chapter at a time in [ReaderWebView], paginated by reader.js. */
class WebEngine(
    private val activity: Activity,
    val book: ReflowableBook,
    private val settings: ReaderSettings,
    private val host: WebHost,
    selectionActions: List<Pair<Int, String>>,
    private val highlightsFor: (Int) -> List<Highlight>,
) : ReaderEngine {
    private val main = Handler(Looper.getMainLooper())
    private val container = FrameLayout(activity)
    private val web = ReaderWebView(activity, selectionActions)
    private val overlay = ImageView(activity)
    private val server = BookServer(book, activity.assets) { ReaderCss.build(settings) }

    @Volatile private var generation = 0
    @Volatile private var highlightsJson = "[]"
    private var ready = false
    private var styleVersion = 0
    private var transition = 0
    private var firstLoad = true
    private val revealTimeout = Runnable { reveal() }

    var chapter = 0
        private set
    override var position: Position? = null
        private set
    override val view: View get() = container

    private val weights = book.spine.map { it.size.coerceAtLeast(1).toDouble() }
    private val cumulative = DoubleArray(weights.size + 1).also { c -> weights.forEachIndexed { i, w -> c[i + 1] = c[i] + w } }
    private val totalWeight = cumulative.last()

    init {
        container.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.visibility = View.GONE
        overlay.scaleType = ImageView.ScaleType.FIT_XY
        container.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.setBackgroundColor(settings.theme.background)
        web.addJavascriptInterface(Bridge(), "RDNative")
        web.onSelectionAction = { action, mode -> readSelection(action, mode) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                server.serve(request.url)

            // Pages never navigate on their own; reader.js routes link taps through the bridge.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                host.onError("renderer")
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) Log.d("reader.js", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
    }

    // ------------------------------------------------------------------ navigation

    fun start(at: Locator?) {
        val target = at?.takeIf { it.chapter in book.spine.indices } ?: Locator(0, 0)
        load(target.chapter, at = "o${target.offset}")
    }

    override fun next() {
        if (ready) eval("rd.next()")
    }

    override fun prev() {
        if (ready) eval("rd.prev()")
    }

    override fun goTo(locator: Locator) {
        if (locator.chapter !in book.spine.indices) return
        if (locator.chapter == chapter && ready) eval("rd.goOffset(${locator.offset})") else load(locator.chapter, at = "o${locator.offset}")
    }

    override fun goToFraction(fraction: Float) {
        val (ch, within) = locate(fraction)
        if (ch == chapter && ready) eval("rd.goFraction($within)") else load(ch, at = "f$within")
    }

    override fun chapterAt(fraction: Float) = locate(fraction).first

    /** Book-wide fraction where chapter [ch] starts (for the contents list). */
    fun chapterStart(ch: Int): Float = (cumulative[ch.coerceIn(0, weights.size - 1)] / totalWeight).toFloat()

    /** Book-wide fraction of a point [within] (0..1 of its text) chapter [ch]. */
    fun bookFraction(ch: Int, within: Double): Float = fraction(ch.coerceIn(0, weights.size - 1), within)

    /** Rough count of characters left in the book, scaling bytes by the current chapter's text density. */
    fun charsLeftInBook(p: Position): Double {
        val density = if (p.chapterLength > 0) p.chapterLength / weights[p.chapter] else 0.6
        return totalWeight * (1.0 - p.progressEnd) * density
    }

    fun goToToc(entry: TocEntry) = goToAnchor(entry.chapter, entry.anchor)

    fun goToAnchor(target: Int, anchor: String?) {
        if (target !in book.spine.indices) return
        if (target == chapter && ready) {
            eval(if (anchor != null) "rd.goAnchor(${JSONObject.quote(anchor)})" else "rd.goPage(0)")
        } else {
            load(target, at = if (anchor == null) "o0" else null, anchor = anchor)
        }
    }

    fun goToSearchHit(target: Int, query: String, occurrence: Int) {
        if (target == chapter && ready) eval("rd.goFound(${JSONObject.quote(query)}, $occurrence)") else load(target, search = query to occurrence)
    }

    /** Chapter index of a `/book/...` path reported by a link, or -1. */
    fun chapterOfPath(path: String): Int = book.chapterOf(Xml.normalize(path.removePrefix(BookServer.BOOK_PREFIX)))

    private fun locate(fraction: Float): Pair<Int, Double> {
        val target = fraction.coerceIn(0f, 1f) * totalWeight
        var ch = 0
        while (ch < weights.size - 1 && cumulative[ch + 1] <= target) ch++
        val within = ((target - cumulative[ch]) / weights[ch]).coerceIn(0.0, 0.999)
        return ch to within
    }

    private fun load(target: Int, at: String? = null, anchor: String? = null, search: Pair<String, Int>? = null, direction: Int = 0) {
        val go = {
            chapter = target
            ready = false
            generation++
            highlightsJson = highlightsToJson(highlightsFor(target))
            val q = StringBuilder("g=$generation&c=$target")
            if (target > 0) q.append("&prev=1")
            if (target < book.spine.size - 1) q.append("&next=1")
            if (!settings.pageAnimation) q.append("&anim=0")
            at?.let { q.append("&at=").append(Uri.encode(it)) }
            search?.let { q.append("&q=").append(Uri.encode(it.first)).append("&n=").append(it.second) }
            val url = BookServer.chapterUrl(book.spine[target].path, q.toString()) + (anchor?.let { "#" + Uri.encode(it) } ?: "")
            main.removeCallbacks(revealTimeout)
            main.postDelayed(revealTimeout, 6000)
            web.loadUrl(url)
        }
        transition = direction
        if (!firstLoad && web.width > 0 && web.height > 0) cover()
        firstLoad = false
        go()
    }

    /** Freezes the current page on screen while the next chapter loads (no blank flash). */
    private fun cover() {
        val bitmap = runCatching {
            Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(settings.theme.background)
                web.draw(c)
            }
        }.getOrNull() ?: return
        overlay.animate().cancel()
        overlay.translationX = 0f
        overlay.setImageBitmap(bitmap)
        overlay.visibility = View.VISIBLE
    }

    private fun reveal() {
        main.removeCallbacks(revealTimeout)
        web.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            web.translationX = 0f
            return
        }
        val dir = transition
        if (dir == 0 || !settings.pageAnimation || container.width == 0) {
            web.translationX = 0f
            overlay.visibility = View.GONE
            overlay.setImageDrawable(null)
            return
        }
        val w = container.width.toFloat()
        val interpolator = DecelerateInterpolator(1.6f)
        web.translationX = dir * w
        web.animate().translationX(0f).setDuration(PAGE_MS).setInterpolator(interpolator).start()
        overlay.animate().translationX(-dir * w).setDuration(PAGE_MS).setInterpolator(interpolator).withEndAction {
            overlay.visibility = View.GONE
            overlay.translationX = 0f
            overlay.setImageDrawable(null)
        }.start()
    }

    private fun onEdge(dir: Int) {
        val target = chapter + dir
        when {
            target in book.spine.indices -> load(target, at = if (dir > 0) "o0" else "end", direction = dir)
            dir > 0 -> host.onEndOfBook()
        }
    }

    // ------------------------------------------------------------------ settings, highlights

    override fun applySettings() {
        web.setBackgroundColor(settings.theme.background)
        container.setBackgroundColor(settings.theme.background)
        if (ready) {
            eval("rd.setAnimate(${settings.pageAnimation})")
            eval("rd.setStyle(${++styleVersion})")
        }
    }

    fun refreshHighlights() {
        highlightsJson = highlightsToJson(highlightsFor(chapter))
    }

    fun addHighlight(h: Highlight) {
        refreshHighlights()
        if (h.chapter == chapter && ready) eval("rd.addHighlight(${JSONObject.quote(highlightJson(h).toString())})")
    }

    fun updateHighlight(h: Highlight) {
        refreshHighlights()
        if (h.chapter == chapter && ready) eval("rd.updateHighlight(${JSONObject.quote(highlightJson(h).toString())})")
    }

    fun removeHighlight(h: Highlight) {
        refreshHighlights()
        if (h.chapter == chapter && ready) eval("rd.removeHighlight('${h.id}')")
    }

    fun clearSelection() = eval("rd.clearSelection()")

    /** Text from [offset] in the current chapter (bookmark labels). */
    fun textAt(offset: Int, length: Int, callback: (String) -> Unit) {
        if (!ready) return callback("")
        web.evaluateJavascript("rd.textAt($offset, $length)") { callback(decodeJsString(it)) }
    }

    private fun readSelection(action: Int, mode: ActionMode) {
        val ch = chapter
        web.evaluateJavascript("rd.selection()") { result ->
            mode.finish()
            val json = decodeJsString(result)
            if (json.isEmpty()) return@evaluateJavascript
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@evaluateJavascript
            host.onSelectionAction(action, TextSelection(ch, o.getInt("s"), o.getInt("e"), o.optString("text")))
        }
    }

    private fun highlightJson(h: Highlight) = JSONObject().put("id", h.id.toString()).put("s", h.start).put("e", h.end).put("c", h.color).put("n", h.note.isNotEmpty())

    private fun highlightsToJson(list: List<Highlight>) = JSONArray().apply { list.forEach { put(highlightJson(it)) } }.toString()

    // ------------------------------------------------------------------ position

    private fun fraction(ch: Int, within: Double) = ((cumulative[ch] + weights[ch] * within.coerceIn(0.0, 1.0)) / totalWeight).toFloat()

    private fun onState(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val page = o.getInt("page")
        val count = o.getInt("count").coerceAtLeast(1)
        val start = o.getInt("start")
        val end = o.getInt("end")
        val total = o.getInt("total")
        val lastOfChapter = page >= count - 1
        val startWithin = if (total > 0) start.toDouble() / total else page.toDouble() / count
        val endWithin = if (lastOfChapter) 1.0 else if (total > 0) end.toDouble() / total else (page + 1.0) / count
        val p = Position(
            chapter = chapter,
            page = page,
            pageCount = count,
            start = start,
            end = end,
            chapterLength = total,
            progressStart = fraction(chapter, startWithin),
            progressEnd = fraction(chapter, endWithin),
            lastPage = lastOfChapter && chapter == book.spine.size - 1,
        )
        position = p
        host.onPosition(p)
    }

    private fun eval(js: String) = web.evaluateJavascript(js, null)

    override fun destroy() {
        main.removeCallbacksAndMessages(null)
        web.stopLoading()
        web.removeJavascriptInterface("RDNative")
        web.destroy()
    }

    private inner class Bridge {
        private fun post(g: String, block: () -> Unit) {
            val gen = g.toIntOrNull() ?: return
            main.post { if (gen == generation) block() }
        }

        @JavascriptInterface fun ready(g: String, json: String) = post(g) {
            ready = true
            onState(json)
            web.postVisualStateCallback(generation.toLong(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) = reveal()
            })
            host.onReady()
        }

        @JavascriptInterface fun page(g: String, json: String) = post(g) { onState(json) }

        @JavascriptInterface fun edge(g: String, dir: Int, @Suppress("UNUSED_PARAMETER") has: Int) = post(g) { onEdge(dir) }

        @JavascriptInterface fun tap(g: String, zone: String) = post(g) {
            if (zone == "corner") host.onCornerTap() else host.onMenuTap()
        }

        @JavascriptInterface fun link(g: String, json: String) = post(g) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@post
            host.onLink(
                BookLink(
                    href = o.optString("href"),
                    internal = o.optBoolean("internal"),
                    path = o.optString("path").takeIf { o.has("path") && !o.isNull("path") },
                    anchor = o.optString("anchor").takeIf { o.has("anchor") && !o.isNull("anchor") },
                    note = o.optString("note").takeIf { o.has("note") && !o.isNull("note") },
                ),
            )
        }

        @JavascriptInterface fun highlight(g: String, id: String) = post(g) { id.toLongOrNull()?.let(host::onHighlightTap) }

        @JavascriptInterface fun image(g: String, src: String) = post(g) {
            val uri = Uri.parse(src)
            if (uri.host == BookServer.HOST) host.onImage(Xml.normalize(uri.path.orEmpty().removePrefix(BookServer.BOOK_PREFIX)))
        }

        @JavascriptInterface fun brightness(g: String, phase: Int, delta: Double) = post(g) { host.onBrightnessDrag(phase, delta.toFloat()) }

        @JavascriptInterface fun highlights(@Suppress("UNUSED_PARAMETER") g: String): String = highlightsJson
    }

    companion object {
        const val PAGE_MS = 260L

        /** evaluateJavascript hands results back JSON-encoded ("\"text\"" or null). */
        fun decodeJsString(result: String?): String {
            if (result == null || result == "null") return ""
            return runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
        }
    }
}
