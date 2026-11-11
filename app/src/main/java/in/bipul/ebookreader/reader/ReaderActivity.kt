package `in`.bipul.ebookreader.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import `in`.bipul.ebookreader.R
import `in`.bipul.ebookreader.book.BookFormat
import `in`.bipul.ebookreader.book.EpubBook
import `in`.bipul.ebookreader.book.HtmlText
import `in`.bipul.ebookreader.book.ReflowableBook
import `in`.bipul.ebookreader.book.TocIndex
import `in`.bipul.ebookreader.book.TxtBook
import `in`.bipul.ebookreader.library.Annotations
import `in`.bipul.ebookreader.library.BookEntry
import `in`.bipul.ebookreader.library.Bookmark
import `in`.bipul.ebookreader.library.Highlight
import `in`.bipul.ebookreader.library.Library
import `in`.bipul.ebookreader.library.Locator
import `in`.bipul.ebookreader.ui.BackHandler
import `in`.bipul.ebookreader.ui.Insets
import `in`.bipul.ebookreader.ui.dp
import `in`.bipul.ebookreader.ui.setLightSystemBars
import `in`.bipul.ebookreader.ui.setSystemBarsVisible
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The reading screen. The page fills the screen with a quiet header (chapter) and footer
 * (time left / page, percent); a tap in the middle brings up the menus: progress scrubber,
 * contents & notes, display settings, light (brightness + warmth), search and bookmarks.
 */
@Suppress("DEPRECATION") // Switch: the framework widget, as the app has no AppCompat/Material dependency.
class ReaderActivity : Activity(), WebHost {
    private lateinit var library: Library
    private lateinit var entry: BookEntry
    private lateinit var settings: ReaderSettings
    private lateinit var annotations: Annotations
    private var engine: ReaderEngine? = null
    private var web: WebEngine? = null
    private var reflowable: ReflowableBook? = null
    private var chapterTitles: List<String?> = emptyList()
    @Volatile private var tocIndex: TocIndex? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val searcher = Executors.newSingleThreadExecutor()
    private lateinit var filter: ScreenFilterView

    private lateinit var root: FrameLayout
    private lateinit var pageArea: View
    private lateinit var engineHost: FrameLayout
    private lateinit var headerTitle: TextView
    private lateinit var ribbon: ImageView
    private lateinit var footer: View
    private lateinit var footerLeft: TextView
    private lateinit var footerRight: TextView
    private lateinit var loading: ProgressBar
    private lateinit var backPill: TextView
    private lateinit var brightnessHint: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var menuBookmark: ImageButton
    private lateinit var menuChapter: TextView
    private lateinit var menuPage: TextView
    private lateinit var seek: SeekBar
    private lateinit var scrim: View
    private lateinit var textSheet: ScrollView
    private lateinit var lightSheet: View
    private lateinit var contentsPanel: View
    private lateinit var contentsList: ListView
    private lateinit var contentsEmpty: TextView
    private lateinit var searchPanel: View
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: ListView
    private lateinit var brightnessBar: SeekBar
    private lateinit var systemBrightness: Switch
    private lateinit var warmthBar: SeekBar

    private var menuVisible = false
    private var sheet: View? = null
    private var panel: View? = null
    private var position: Position? = null
    private var lastTurnAt = 0L
    private var speed = 25f
    private var jumpBack: Pair<Locator, Float>? = null
    private var pagesSinceJump = 0
    private var seeking = false
    private var skipSpeedSample = true
    private var syncingLight = false
    private var liveBrightness = -1f
    private var dragStartBrightness = 0f
    private var searchToken = 0
    private var contentsTab = 0
    private var searchRows = ArrayList<Row>()

    private val back = BackHandler(this, { panel != null || sheet != null || menuVisible }) { onBack() }
    private val saveRunnable = Runnable { save(notify = false) }
    private val screenOffRunnable = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private val hideBrightnessHint = Runnable { brightnessHint.visibility = View.GONE }
    private val showLoading = Runnable { loading.visibility = View.VISIBLE }

    private val theme get() = settings.theme
    private val isPdf get() = entry.format == BookFormat.PDF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library.get(this)
        entry = intent.getStringExtra(EXTRA_BOOK_ID)?.let(library::get) ?: run {
            finish()
            return
        }
        settings = ReaderSettings(this)
        annotations = Annotations(library.bookDir(entry.id))
        speed = settings.readingSpeed
        liveBrightness = settings.brightness
        setContentView(R.layout.activity_reader)
        bindViews()
        filter = ScreenFilterView(this)
        // Above every elevated view (menus, sheets, panels), so the warm light covers them too.
        filter.elevation = dp(100f)
        filter.outlineProvider = null
        root.addView(filter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setUpInsets()
        setUpMenus()
        setUpTextSheet()
        setUpLightSheet()
        setUpContents()
        setUpSearch()
        applyTheme()
        applyLight()
        window.setSystemBarsVisible(false, !theme.dark)
        pokeScreen()
        openBook()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        pageArea = findViewById(R.id.page_area)
        engineHost = findViewById(R.id.engine_host)
        headerTitle = findViewById(R.id.header_title)
        ribbon = findViewById(R.id.ribbon)
        footer = findViewById(R.id.footer)
        footerLeft = findViewById(R.id.footer_left)
        footerRight = findViewById(R.id.footer_right)
        loading = findViewById(R.id.loading)
        backPill = findViewById(R.id.back_pill)
        brightnessHint = findViewById(R.id.brightness_hint)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        menuBookmark = findViewById(R.id.menu_bookmark)
        menuChapter = findViewById(R.id.menu_chapter)
        menuPage = findViewById(R.id.menu_page)
        seek = findViewById(R.id.seek)
        scrim = findViewById(R.id.scrim)
        textSheet = findViewById(R.id.text_sheet)
        lightSheet = findViewById(R.id.light_sheet)
        contentsPanel = findViewById(R.id.contents_panel)
        contentsList = findViewById(R.id.contents_list)
        contentsEmpty = findViewById(R.id.contents_empty)
        searchPanel = findViewById(R.id.search_panel)
        searchInput = findViewById(R.id.search_input)
        searchStatus = findViewById(R.id.search_status)
        searchResults = findViewById(R.id.search_results)
        brightnessBar = findViewById(R.id.brightness)
        systemBrightness = findViewById(R.id.switch_system_brightness)
        warmthBar = findViewById(R.id.warmth)
    }

    // ------------------------------------------------------------------ opening

    private fun openBook() {
        main.postDelayed(showLoading, 300)
        library.update(entry.id, notify = false) { it.copy(lastOpenedAt = System.currentTimeMillis()) }
        if (isPdf) {
            try {
                val e = PdfEngine(this, library.bookFile(entry), settings, this)
                attach(e)
                e.start(entry.position)
            } catch (e: Exception) {
                fail(e)
            }
            return
        }
        worker.execute {
            val result = runCatching {
                when (entry.format) {
                    BookFormat.EPUB -> EpubBook.open(library.bookFile(entry))
                    else -> TxtBook.open(File(library.bookDir(entry.id), "text"))
                }
            }
            main.post {
                if (isDestroyed) {
                    result.getOrNull()?.close()
                    return@post
                }
                result.onSuccess { book ->
                    reflowable = book
                    chapterTitles = book.chapterTitles()
                    tocIndex = TocIndex.empty(book)
                    worker.execute {
                        val index = TocIndex.build(book)
                        main.post {
                            if (isDestroyed) return@post
                            tocIndex = index
                            updateStatus()
                        }
                    }
                    val actions = listOf(
                        ACTION_HIGHLIGHT to getString(R.string.highlight),
                        ACTION_NOTE to getString(R.string.note),
                        ACTION_LOOKUP to getString(R.string.look_up),
                    )
                    val e = WebEngine(this, book, settings, this, actions, ::highlightsFor)
                    web = e
                    attach(e)
                    e.start(entry.position)
                }.onFailure(::fail)
            }
        }
    }

    private fun attach(e: ReaderEngine) {
        engine = e
        engineHost.addView(e.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findViewById<View>(R.id.menu_search).visibility = if (isPdf) View.GONE else View.VISIBLE
        findViewById<View>(R.id.text_options).visibility = if (isPdf) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tab_contents).visibility = if (isPdf) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tab_notes).visibility = if (isPdf) View.GONE else View.VISIBLE
    }

    private fun fail(e: Throwable) {
        main.removeCallbacks(showLoading)
        val message = (e as? java.io.IOException)?.message ?: getString(R.string.open_failed)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun highlightsFor(chapter: Int): List<Highlight> = annotations.highlights.filter { it.chapter == chapter }

    // ------------------------------------------------------------------ engine callbacks

    override fun onReady() {
        main.removeCallbacks(showLoading)
        loading.visibility = View.GONE
    }

    override fun onPosition(position: Position) {
        val prev = this.position
        this.position = position
        trackReadingSpeed(prev, position)
        if (jumpBack != null && prev != null && (prev.chapter != position.chapter || prev.page != position.page)) {
            if (++pagesSinceJump > 3) hideBackPill()
        }
        updateStatus()
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1000)
    }

    override fun onMenuTap() {
        if (sheet != null) closeSheet() else showMenu(!menuVisible)
    }

    override fun onCornerTap() = toggleBookmark()

    override fun onEndOfBook() {
        library.update(entry.id, notify = false) { it.copy(finished = true, progress = 1f) }
        dialog()
            .setTitle(R.string.end_of_book_title)
            .setMessage(getString(R.string.end_of_book_message, entry.title))
            .setNegativeButton(R.string.keep_reading, null)
            .setPositiveButton(R.string.back_to_library) { _, _ -> finish() }
            .show()
    }

    override fun onError(message: String) {
        if (message == "renderer") {
            // The page renderer crashed or was killed for memory: reopen where we were.
            save(notify = false)
            recreate()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onHighlightTap(id: Long) {
        annotations.highlights.firstOrNull { it.id == id }?.let(::showHighlightOptions)
    }

    override fun onLink(link: BookLink) {
        val w = web ?: return
        when {
            link.note != null -> showFootnote(link)
            link.internal -> {
                val chapter = link.path?.let(w::chapterOfPath) ?: -1
                if (chapter >= 0) jump { w.goToAnchor(chapter, link.anchor) }
            }
            else -> confirmExternalLink(link.href)
        }
    }

    override fun onImage(path: String) {
        val book = reflowable ?: return
        worker.execute {
            val bitmap = runCatching {
                val bytes = book.open(path)?.use { it.readBytes() } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: return@execute
            main.post {
                if (isDestroyed) return@post
                val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val view = ZoomImageView(this)
                view.setBackgroundColor(Color.BLACK)
                view.setImageBitmap(bitmap)
                view.contentDescription = getString(R.string.image)
                view.setOnClickListener { d.dismiss() }
                d.setContentView(view)
                d.show()
            }
        }
    }

    override fun onBrightnessDrag(phase: Int, delta: Float) {
        when (phase) {
            0 -> {
                dragStartBrightness = sliderBrightness()
                main.removeCallbacks(hideBrightnessHint)
                brightnessHint.visibility = View.VISIBLE
                updateBrightnessHint()
            }
            1 -> {
                liveBrightness = (dragStartBrightness + delta * 1.3f).coerceIn(0f, 1f)
                applyLight()
                updateBrightnessHint()
            }
            else -> {
                if (liveBrightness >= 0f) settings.brightness = liveBrightness
                syncLightSheet()
                main.postDelayed(hideBrightnessHint, 700)
            }
        }
    }

    override fun onSelectionAction(action: Int, selection: TextSelection) {
        when (action) {
            ACTION_HIGHLIGHT -> createHighlight(selection, settings.lastHighlightColor, "")
            ACTION_NOTE -> editNote("") { note -> createHighlight(selection, settings.lastHighlightColor, note) }
            ACTION_LOOKUP -> lookUp(selection.text)
        }
    }

    // ------------------------------------------------------------------ status: header, footer, menus

    private fun chapterTitle(chapter: Int): String? = chapterTitles.getOrNull(chapter)

    /** Title of the TOC entry a position falls under (precise even when files hold several chapters). */
    private fun titleAt(chapter: Int, offset: Int): String? = tocIndex?.titleAt(chapter, offset) ?: chapterTitle(chapter)

    private fun bookmarkAt(p: Position): Bookmark? =
        if (isPdf) annotations.bookmarks.firstOrNull { it.chapter == p.chapter } else annotations.bookmarkIn(p.chapter, p.start, p.end)

    private fun percent(p: Position) = if (p.lastPage) 100 else (p.progressEnd * 100).toInt().coerceIn(0, 100)

    private fun updateStatus() {
        val p = position ?: return
        headerTitle.text = if (isPdf) entry.title else titleAt(p.chapter, p.start) ?: entry.title
        footerRight.text = getString(R.string.percent, percent(p))
        footerLeft.text = footerText(p)
        val marked = bookmarkAt(p) != null
        ribbon.visibility = if (marked) View.VISIBLE else View.GONE
        menuBookmark.setImageResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        menuBookmark.contentDescription = getString(if (marked) R.string.remove_bookmark else R.string.add_bookmark)
        if (!seeking) {
            seek.progress = (p.progressStart * 1000).toInt()
            menuChapter.text = if (isPdf) entry.title else titleAt(p.chapter, p.start) ?: entry.title
            menuPage.text = if (isPdf) {
                getString(R.string.page_of, p.page + 1, p.pageCount)
            } else {
                getString(R.string.page_of_percent, p.page + 1, p.pageCount, percent(p))
            }
        }
        val status = if (settings.showStatus) View.VISIBLE else View.INVISIBLE
        footer.visibility = status
        headerTitle.visibility = status
    }

    private fun footerText(p: Position): String {
        if (isPdf) return getString(R.string.page_of, p.page + 1, p.pageCount)
        return when (settings.footerMode % 3) {
            1 -> getString(R.string.left_in_chapter, duration((p.chapterLength - p.end).coerceAtLeast(0) / speed))
            2 -> getString(R.string.left_in_book, duration((web?.charsLeftInBook(p) ?: 0.0).toFloat() / speed))
            else -> getString(R.string.page_in_chapter, p.page + 1, p.pageCount)
        }
    }

    private fun duration(seconds: Float): String {
        val minutes = (seconds / 60).toInt()
        return when {
            minutes < 1 -> getString(R.string.less_than_a_minute)
            minutes < 60 -> resources.getQuantityString(R.plurals.minutes, minutes, minutes)
            else -> getString(R.string.hours_minutes, minutes / 60, minutes % 60)
        }
    }

    /** Learns how fast this reader reads from how long they stay on each page. */
    private fun trackReadingSpeed(prev: Position?, p: Position) {
        val now = System.currentTimeMillis()
        if (prev != null && !skipSpeedSample && !isPdf) {
            // A genuine page turn: same pagination and the new page continues where the last ended
            // (re-layouts after rotation or a font change must not count as fast reading).
            val forward = (p.chapter == prev.chapter && p.pageCount == prev.pageCount && p.page == prev.page + 1 && p.start == prev.end) ||
                (p.chapter == prev.chapter + 1 && p.page == 0)
            val seconds = (now - lastTurnAt) / 1000f
            val chars = prev.end - prev.start
            if (forward && seconds in 2f..180f && chars > 40) {
                speed = (speed * 0.85f + (chars / seconds) * 0.15f).coerceIn(3f, 120f)
            }
        }
        skipSpeedSample = false
        lastTurnAt = now
    }

    private fun save(notify: Boolean) {
        val p = position ?: return
        library.update(entry.id, notify) {
            it.copy(
                position = p.locator,
                progress = if (p.lastPage) 1f else p.progressEnd,
                lastOpenedAt = System.currentTimeMillis(),
                finished = it.finished || p.lastPage,
            )
        }
    }

    // ------------------------------------------------------------------ menus

    private fun setUpMenus() {
        findViewById<View>(R.id.menu_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.menu_title).text = entry.title
        findViewById<TextView>(R.id.menu_subtitle).apply {
            text = entry.author
            visibility = if (entry.author.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.menu_search).setOnClickListener { openSearch() }
        menuBookmark.setOnClickListener { toggleBookmark() }
        findViewById<View>(R.id.menu_contents).setOnClickListener { openContents(if (isPdf) 1 else 0) }
        findViewById<View>(R.id.menu_text).setOnClickListener { openSheet(textSheet) }
        findViewById<View>(R.id.menu_light).setOnClickListener { openSheet(lightSheet) }
        footerLeft.setOnClickListener {
            if (!isPdf) {
                settings.footerMode = (settings.footerMode + 1) % 3
                updateStatus()
            }
        }
        backPill.setOnClickListener {
            jumpBack?.let { (locator, _) -> engine?.goTo(locator) }
            hideBackPill()
        }
        scrim.setOnClickListener { closeSheet() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val f = value / 1000f
                val e = engine ?: return
                val ch = e.chapterAt(f)
                if (isPdf) {
                    menuPage.text = getString(R.string.page_of, ch + 1, (e as PdfEngine).pageCount)
                } else {
                    menuChapter.text = chapterTitle(ch) ?: entry.title
                    menuPage.text = getString(R.string.percent, (f * 100).toInt())
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                markJump()
                engine?.goToFraction(bar.progress / 1000f)
            }
        })
    }

    private fun showMenu(show: Boolean) {
        if (menuVisible == show) return
        menuVisible = show
        val offset = dp(24f)
        for ((view, dir) in listOf(topBar to -1, bottomBar to 1)) {
            view.animate().cancel()
            if (show) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationY = dir * offset
                view.animate().alpha(1f).translationY(0f).setDuration(180).withEndAction(null).start()
            } else {
                view.animate().alpha(0f).translationY(dir * offset).setDuration(150).withEndAction { view.visibility = View.GONE }.start()
            }
        }
        if (show) updateStatus()
        window.setSystemBarsVisible(show, !theme.dark)
        back.update()
    }

    private fun openSheet(view: View) {
        showMenu(false)
        sheet?.visibility = View.GONE
        sheet = view
        if (view === textSheet) refreshTextSheet() else syncLightSheet()
        scrim.setBackgroundColor(Color.TRANSPARENT)
        scrim.visibility = View.VISIBLE
        view.visibility = View.VISIBLE
        view.translationY = dp(320f)
        view.animate().translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        back.update()
    }

    private fun closeSheet() {
        val view = sheet ?: return
        sheet = null
        scrim.visibility = View.GONE
        view.animate().translationY(view.height.toFloat()).setDuration(180).withEndAction { view.visibility = View.GONE }.start()
        back.update()
    }

    private fun showPanel(view: View) {
        showMenu(false)
        closeSheet()
        panel = view
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(160).start()
        window.setSystemBarsVisible(true, !theme.dark)
        back.update()
    }

    private fun closePanel() {
        val view = panel ?: return
        panel = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        view.animate().alpha(0f).setDuration(140).withEndAction { view.visibility = View.GONE }.start()
        window.setSystemBarsVisible(false, !theme.dark)
        back.update()
    }

    private fun onBack() {
        when {
            panel != null -> closePanel()
            sheet != null -> closeSheet()
            menuVisible -> showMenu(false)
        }
    }

    @SuppressLint("GestureBackNavigation") // API 33+ registers an OnBackInvokedCallback instead.
    @Deprecated("Only called below API 33; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() {
        if (!back.handle()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ jumping, bookmarks

    /** Remembers where the reader was, so "Back to …" can return there after a jump. */
    private fun markJump() {
        val p = position ?: return
        jumpBack = p.locator to p.progressStart
        pagesSinceJump = -1
        skipSpeedSample = true
        backPill.text = getString(R.string.back_to, (p.progressStart * 100).toInt())
        backPill.visibility = View.VISIBLE
    }

    private fun hideBackPill() {
        jumpBack = null
        backPill.visibility = View.GONE
    }

    private fun jump(action: () -> Unit) {
        markJump()
        closePanel()
        showMenu(false)
        action()
    }

    private fun toggleBookmark() {
        val p = position ?: return
        val existing = bookmarkAt(p)
        if (existing != null) {
            annotations.bookmarks.remove(existing)
            annotations.save()
            updateStatus()
            return
        }
        val add = { label: String ->
            annotations.bookmarks.add(Bookmark(annotations.newId(), p.chapter, p.start, label, p.progressStart, System.currentTimeMillis()))
            annotations.save()
            updateStatus()
            ribbon.scaleY = 0f
            ribbon.pivotY = 0f
            ribbon.animate().scaleY(1f).setDuration(200).start()
        }
        val w = web
        if (w == null) add(getString(R.string.page_n, p.page + 1)) else w.textAt(p.start, 140) { add(it.ifEmpty { chapterTitle(p.chapter) ?: entry.title }) }
    }

    // ------------------------------------------------------------------ highlights and notes

    private fun createHighlight(sel: TextSelection, color: Int, note: String) {
        val p = position
        val h = Highlight(
            id = annotations.newId(),
            chapter = sel.chapter,
            start = sel.start,
            end = sel.end,
            text = sel.text.trim().take(4000),
            color = color,
            note = note,
            progress = p?.progressStart ?: 0f,
            createdAt = System.currentTimeMillis(),
        )
        annotations.highlights.add(h)
        annotations.save()
        web?.addHighlight(h)
    }

    private fun replaceHighlight(old: Highlight, new: Highlight) {
        val i = annotations.highlights.indexOfFirst { it.id == old.id }
        if (i < 0) return
        annotations.highlights[i] = new
        annotations.save()
        web?.updateHighlight(new)
    }

    private fun deleteHighlight(h: Highlight) {
        annotations.highlights.removeAll { it.id == h.id }
        annotations.save()
        web?.removeHighlight(h)
    }

    private fun showHighlightOptions(h: Highlight) {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.quoted, h.text)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.ITALIC)
            textSize = 15f
            setTextColor(dialogText())
        })
        if (h.note.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = h.note
                textSize = 14f
                setPadding(0, dp(10), 0, 0)
                setTextColor(dialogText())
            })
        }
        var dialog: AlertDialog? = null
        val colors = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(6))
        }
        HighlightColors.swatches.forEachIndexed { i, swatch ->
            colors.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatch)
                    if (i == h.color) setStroke(dp(3), dialogText())
                }
                contentDescription = getString(HighlightColors.names[i])
                setOnClickListener {
                    settings.lastHighlightColor = i
                    replaceHighlight(h, h.copy(color = i))
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
        }
        box.addView(colors)
        val items = arrayOf(
            getString(if (h.note.isEmpty()) R.string.add_note else R.string.edit_note),
            getString(R.string.copy),
            getString(R.string.share),
            getString(R.string.delete_highlight),
        )
        dialog = dialog()
            .setCustomTitle(box)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editNote(h.note) { note -> replaceHighlight(h, h.copy(note = note)) }
                    1 -> copy(h.text)
                    2 -> share(h.text)
                    3 -> deleteHighlight(h)
                }
            }
            .show()
    }

    private fun editNote(initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            hint = getString(R.string.note_hint)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val d = dialog()
            .setTitle(R.string.note)
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> done(input.text.toString().trim()) }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(entry.title, text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        val by = if (entry.author.isEmpty()) entry.title else getString(R.string.title_by_author, entry.title, entry.author)
        val body = getString(R.string.share_quote, text.trim(), by)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), null))
    }

    private fun lookUp(text: String) {
        val word = text.trim()
        if (word.isEmpty()) return
        val query = if (word.split(Regex("\\s+")).size == 1) "define $word" else word
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        try {
            startActivity(search)
        } catch (e: android.content.ActivityNotFoundException) {
            openUrl("https://www.google.com/search?q=" + Uri.encode(query))
        }
    }

    private fun showFootnote(link: BookLink) {
        dialog()
            .setMessage(link.note)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.go_to_note) { _, _ ->
                val w = web ?: return@setNeutralButton
                jump { w.goToAnchor(w.chapter, link.anchor) }
            }
            .show()
    }

    private fun confirmExternalLink(href: String) {
        dialog()
            .setTitle(R.string.open_link_title)
            .setMessage(href)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open) { _, _ -> openUrl(href) }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ contents, bookmarks, notes

    private fun setUpContents() {
        findViewById<View>(R.id.contents_close).setOnClickListener { closePanel() }
        findViewById<TextView>(R.id.contents_title).text = entry.title
        findViewById<View>(R.id.tab_contents).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.tab_bookmarks).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.tab_notes).setOnClickListener { selectTab(2) }
    }

    private fun openContents(tab: Int) {
        showPanel(contentsPanel)
        selectTab(tab)
    }

    private fun selectTab(tab: Int) {
        contentsTab = tab
        listOf(R.id.tab_contents, R.id.tab_bookmarks, R.id.tab_notes).forEachIndexed { i, id ->
            val t = findViewById<TextView>(id)
            t.setTextColor(if (i == tab) theme.link else theme.secondary)
            t.background = if (i == tab) underline(theme.link) else null
        }
        val rows = when (tab) {
            0 -> tocRows()
            1 -> bookmarkRows()
            else -> highlightRows()
        }
        contentsList.adapter = RowAdapter(rows, theme)
        contentsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        contentsEmpty.setText(
            when (tab) {
                0 -> R.string.no_contents
                1 -> R.string.no_bookmarks
                else -> R.string.no_notes
            },
        )
        if (tab == 0) {
            val current = rows.indexOfFirst { it.current }
            if (current > 2) contentsList.setSelection(current - 2)
        }
    }

    private fun underline(color: Int) = android.graphics.drawable.LayerDrawable(
        arrayOf(GradientDrawable().apply { setColor(color) }),
    ).apply { setLayerInset(0, dp(24), dp(41), dp(24), 0) }

    private fun tocRows(): List<Row> {
        val book = reflowable ?: return emptyList()
        val w = web ?: return emptyList()
        val p = position
        val index = tocIndex
        if (book.toc.isEmpty() || index == null) {
            return book.spine.indices.map { ch ->
                Row(
                    title = getString(R.string.section_n, ch + 1),
                    meta = getString(R.string.percent, (w.chapterStart(ch) * 100).toInt()),
                    current = ch == p?.chapter,
                    onClick = { jump { w.goToAnchor(ch, null) } },
                )
            }
        }
        val currentIndex = if (p != null) index.indexAt(p.chapter, p.start) else -1
        return index.entries.mapIndexed { i, e ->
            Row(
                title = e.title,
                meta = getString(R.string.percent, (w.bookFraction(e.chapter, index.fractionInChapter(i)) * 100).toInt()),
                depth = e.depth,
                current = i == currentIndex,
                onClick = { jump { w.goToToc(e) } },
            )
        }
    }

    private fun bookmarkRows(): List<Row> = annotations.bookmarks.map { b ->
        Row(
            title = b.label,
            subtitle = if (isPdf) null else titleAt(b.chapter, b.offset),
            meta = if (isPdf) getString(R.string.page_n, b.chapter + 1) else getString(R.string.percent, (b.progress * 100).toInt()),
            onClick = { jump { engine?.goTo(Locator(b.chapter, b.offset)) } },
            onLongClick = {
                dialog()
                    .setMessage(R.string.delete_bookmark_question)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        annotations.bookmarks.remove(b)
                        annotations.save()
                        updateStatus()
                        selectTab(1)
                    }
                    .show()
            },
        )
    }

    private fun highlightRows(): List<Row> = annotations.highlights.map { h ->
        Row(
            title = h.text,
            subtitle = titleAt(h.chapter, h.start),
            note = h.note.ifEmpty { null },
            meta = getString(R.string.percent, (h.progress * 100).toInt()),
            barColor = HighlightColors.swatches.getOrElse(h.color) { HighlightColors.swatches[0] },
            onClick = { jump { engine?.goTo(Locator(h.chapter, h.start)) } },
            onLongClick = { showHighlightOptions(h) },
        )
    }

    // ------------------------------------------------------------------ search

    private fun setUpSearch() {
        findViewById<View>(R.id.search_close).setOnClickListener { closePanel() }
        searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                runSearch(v.text.toString())
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun openSearch() {
        showPanel(searchPanel)
        searchResults.adapter = RowAdapter(searchRows, theme)
        if (searchRows.isEmpty()) {
            searchStatus.text = ""
            searchInput.requestFocus()
            main.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(searchInput, 0) }, 150)
        }
    }

    private fun runSearch(raw: String) {
        val query = raw.trim()
        val book = reflowable ?: return
        val w = web ?: return
        if (query.length < 2) return
        val token = ++searchToken
        val rows = ArrayList<Row>()
        searchRows = rows
        val adapter = RowAdapter(rows, theme)
        searchResults.adapter = adapter
        searchStatus.text = getString(R.string.searching)
        val accent = theme.link
        searcher.execute {
            var found = 0
            for (chapter in book.spine.indices) {
                if (token != searchToken || found >= MAX_RESULTS) break
                val matches = runCatching { HtmlText.find(book.chapterText(chapter), query, MAX_RESULTS - found) }.getOrDefault(emptyList())
                if (matches.isEmpty()) continue
                found += matches.size
                val index = tocIndex
                val newRows = matches.map { m ->
                    val text = SpannableString(m.snippet)
                    val end = (m.matchStart + m.matchLength).coerceAtMost(text.length)
                    if (m.matchStart in 0 until end) {
                        text.setSpan(StyleSpan(Typeface.BOLD), m.matchStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text.setSpan(ForegroundColorSpan(accent), m.matchStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val title = index?.titleAt(chapter, m.offset) ?: chapterTitles.getOrNull(chapter)
                    Row(title = text, subtitle = title, onClick = { jump { w.goToSearchHit(chapter, query, m.occurrence) } })
                }
                val count = found
                main.post {
                    if (token != searchToken) return@post
                    rows.addAll(newRows)
                    adapter.notifyDataSetChanged()
                    searchStatus.text = resources.getQuantityString(R.plurals.search_results, count, count)
                }
            }
            val total = found
            main.post {
                if (token != searchToken) return@post
                searchStatus.text = if (total == 0) {
                    getString(R.string.no_results, query)
                } else {
                    resources.getQuantityString(R.plurals.search_results, total, total) + if (total >= MAX_RESULTS) "+" else ""
                }
            }
        }
    }

    // ------------------------------------------------------------------ display settings

    private fun setUpTextSheet() {
        findViewById<View>(R.id.size_down).setOnClickListener { settings.fontSize = settings.fontSize - 10; textChanged() }
        findViewById<View>(R.id.size_up).setOnClickListener { settings.fontSize = settings.fontSize + 10; textChanged() }
        bindSwitch(R.id.switch_columns, { settings.twoColumns }) { settings.twoColumns = it; textChanged() }
        bindSwitch(R.id.switch_animation, { settings.pageAnimation }) { settings.pageAnimation = it; engine?.applySettings() }
        bindSwitch(R.id.switch_volume, { settings.volumeKeys }) { settings.volumeKeys = it }
        bindSwitch(R.id.switch_screen_on, { settings.keepScreenOn }) { settings.keepScreenOn = it; pokeScreen() }
        bindSwitch(R.id.switch_status, { settings.showStatus }) { settings.showStatus = it; updateStatus() }
        refreshTextSheet()
    }

    private fun bindSwitch(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val s = findViewById<Switch>(id)
        s.isChecked = get()
        s.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    private fun textChanged() {
        engine?.applySettings()
        refreshTextSheet()
    }

    private fun refreshTextSheet() {
        val t = theme
        val themeRow = findViewById<LinearLayout>(R.id.theme_row)
        themeRow.removeAllViews()
        ReaderTheme.entries.forEach { option ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                contentDescription = getString(option.label)
                setOnClickListener {
                    settings.theme = option
                    applyTheme()
                    engine?.applySettings()
                }
            }
            cell.addView(TextView(this).apply {
                text = getString(R.string.letter_aa)
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setTextColor(option.text)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(option.background)
                    setStroke(if (option == t) dp(3) else dp(1), if (option == t) t.link else withAlpha(t.text, 0.25f))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            cell.addView(TextView(this).apply {
                text = getString(option.label)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (option == t) t.text else t.secondary)
                setPadding(0, dp(6), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            themeRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val fontRow = findViewById<LinearLayout>(R.id.font_row)
        fontRow.removeAllViews()
        ReaderFont.entries.forEach { font ->
            fontRow.addView(segment(getString(font.label), settings.font == font) {
                settings.font = font
                textChanged()
            }.apply {
                typeface = when (font) {
                    ReaderFont.LITERATA -> literata
                    ReaderFont.SANS -> Typeface.SANS_SERIF
                    else -> Typeface.SERIF
                }
                textSize = 15f
                setPadding(dp(18), 0, dp(18), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        }

        findViewById<TextView>(R.id.size_value).text = getString(R.string.percent, settings.fontSize)
        for (id in listOf(R.id.size_down, R.id.size_up)) {
            findViewById<TextView>(id).apply {
                background = segmentBackground(false)
                setTextColor(t.text)
            }
        }
        fillSegments(R.id.spacing_row, listOf(R.string.spacing_compact, R.string.spacing_normal, R.string.spacing_relaxed), settings.lineSpacing) {
            settings.lineSpacing = it
            textChanged()
        }
        fillSegments(R.id.margin_row, listOf(R.string.margins_narrow, R.string.margins_normal, R.string.margins_wide), settings.margins) {
            settings.margins = it
            textChanged()
        }
        fillSegments(R.id.align_row, listOf(R.string.align_justified, R.string.align_left), if (settings.justify) 0 else 1) {
            settings.justify = it == 0
            textChanged()
        }
    }

    private val literata: Typeface by lazy {
        runCatching { Typeface.createFromAsset(assets, "reader/fonts/Literata-Regular.ttf") }.getOrDefault(Typeface.SERIF)
    }

    private fun fillSegments(rowId: Int, labels: List<Int>, selected: Int, pick: (Int) -> Unit) {
        val row = findViewById<LinearLayout>(rowId)
        row.removeAllViews()
        labels.forEachIndexed { i, label ->
            row.addView(segment(getString(label), i == selected) { pick(i) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
    }

    private fun segment(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        maxLines = 1
        setTextColor(if (selected) theme.link else theme.text)
        background = segmentBackground(selected)
        isSelected = selected
        setOnClickListener { onClick() }
    }

    private fun segmentBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10f)
        setColor(if (selected) withAlpha(theme.link, 0.14f) else Color.TRANSPARENT)
        setStroke(dp(1), if (selected) theme.link else withAlpha(theme.text, 0.2f))
    }

    // ------------------------------------------------------------------ light

    private fun setUpLightSheet() {
        brightnessBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                liveBrightness = value / 1000f
                systemBrightness.isChecked = false
                applyLight()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                settings.brightness = liveBrightness
            }
        })
        systemBrightness.setOnCheckedChangeListener { _, checked ->
            if (syncingLight) return@setOnCheckedChangeListener
            if (checked) {
                liveBrightness = -1f
                settings.brightness = -1f
                applyLight()
                brightnessBar.progress = (sliderBrightness() * 1000).toInt()
            } else if (liveBrightness < 0) {
                liveBrightness = sliderBrightness()
                settings.brightness = liveBrightness
                applyLight()
            }
        }
        warmthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.warmth = value / 100f
                applyLight()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        syncLightSheet()
    }

    private fun syncLightSheet() {
        syncingLight = true
        brightnessBar.progress = (sliderBrightness() * 1000).toInt()
        systemBrightness.isChecked = liveBrightness < 0
        warmthBar.progress = (settings.warmth * 100).toInt()
        syncingLight = false
    }

    /** Current brightness as a slider position 0..1 (the system's level when following it). */
    private fun sliderBrightness(): Float {
        if (liveBrightness >= 0f) return liveBrightness
        val system = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        return (DIM_ZONE + (1 - DIM_ZONE) * sqrt(system.coerceIn(0f, 1f)))
    }

    /**
     * Brightness slider: the top 90% drives the backlight on a perceptual curve; the bottom 10%
     * keeps the backlight at its lowest and dims further with an overlay, for reading in the dark.
     */
    private fun applyLight() {
        val v = liveBrightness
        val lp = window.attributes
        var dim = 0f
        if (v < 0f) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else if (v >= DIM_ZONE) {
            val x = (v - DIM_ZONE) / (1 - DIM_ZONE)
            lp.screenBrightness = (x * x).coerceIn(0.01f, 1f)
        } else {
            lp.screenBrightness = 0.01f
            dim = (DIM_ZONE - v) / DIM_ZONE
        }
        window.attributes = lp
        filter.set(settings.warmth, dim)
    }

    private fun updateBrightnessHint() {
        brightnessHint.text = getString(R.string.percent, (sliderBrightness() * 100).toInt())
    }

    // ------------------------------------------------------------------ theme

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun dialogText() = if (theme.dark) Color.WHITE else 0xFF202020.toInt()

    private fun dialog() = AlertDialog.Builder(
        this,
        if (theme.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    private fun applyTheme() {
        val t = theme
        root.setBackgroundColor(t.background)
        window.decorView.setBackgroundColor(t.background)
        themeTree(root, t)
        val accent = ColorStateList.valueOf(t.link)
        for (bar in listOf(seek, brightnessBar, warmthBar)) {
            bar.progressTintList = accent
            bar.thumbTintList = accent
            bar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(t.text, 0.25f))
        }
        loading.indeterminateTintList = accent
        window.setLightSystemBars(!t.dark)
        if (sheet === textSheet) refreshTextSheet()
        if (panel === contentsPanel) selectTab(contentsTab)
        updateStatus()
    }

    private fun themeTree(view: View, t: ReaderTheme) {
        when (view.tag) {
            "chrome" -> view.setBackgroundColor(t.chrome)
            "page" -> view.setBackgroundColor(t.background)
            "divider" -> view.setBackgroundColor(t.text)
            "pill" -> view.background = GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(t.chrome)
                setStroke(dp(1), withAlpha(t.text, 0.15f))
            }
        }
        when (view) {
            is Switch -> {
                view.setTextColor(t.text)
                view.thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(t.link, if (t.dark) 0xFFBDBDBD.toInt() else Color.WHITE),
                )
                view.trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(withAlpha(t.link, 0.5f), withAlpha(t.text, 0.3f)),
                )
            }
            is EditText -> {
                view.setTextColor(t.text)
                view.setHintTextColor(t.secondary)
            }
            is TextView -> {
                view.setTextColor(if (view.tag == "secondary") t.secondary else t.text)
                view.compoundDrawableTintList = ColorStateList.valueOf(t.text)
            }
            is ImageView -> view.imageTintList = ColorStateList.valueOf(if (view.tag == "accent") t.link else t.text)
        }
        if (view is ViewGroup && view !is ListView) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child !== engine?.view && child !is ScreenFilterView) themeTree(child, t)
            }
        }
    }

    // ------------------------------------------------------------------ insets, keys, screen

    private fun setUpInsets() {
        val ui = window
        if (Build.VERSION.SDK_INT >= 30) ui.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars: Insets
            val cut: Insets
            val ime: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                val c = insets.getInsets(WindowInsets.Type.displayCutout())
                bars = Insets(b.left, b.top, b.right, b.bottom)
                cut = Insets(c.left, c.top, c.right, c.bottom)
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                bars = Insets(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
                val c = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                cut = if (Build.VERSION.SDK_INT >= 28 && c != null) {
                    Insets(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom)
                } else {
                    Insets(0, 0, 0, 0)
                }
                @Suppress("DEPRECATION")
                ime = (insets.systemWindowInsetBottom - insets.stableInsetBottom).coerceAtLeast(0)
            }
            // The page only avoids cutouts: the bars it hides would otherwise leave empty strips,
            // and keeping its size fixed means showing the menu never re-flows the text.
            pageArea.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            topBar.setPadding(dp(4) + bars.left, bars.top, dp(4) + bars.right, 0)
            bottomBar.setPadding(bars.left, dp(10), bars.right, bars.bottom)
            textSheet.setPadding(bars.left, 0, bars.right, bars.bottom)
            lightSheet.setPadding(dp(20) + bars.left, dp(16), dp(20) + bars.right, dp(16) + bars.bottom)
            for (p in listOf(contentsPanel, searchPanel)) p.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
            (backPill.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(56) + cut.bottom
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else @Suppress("DEPRECATION") insets.consumeSystemWindowInsets()
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panel == null && sheet == null) {
            val e = engine
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> if (settings.volumeKeys && e != null) {
                    if (menuVisible) showMenu(false)
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) e.next() else e.prev()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> if (e != null) {
                    e.next()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> if (e != null) {
                    e.prev()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && settings.volumeKeys && panel == null && sheet == null) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pokeScreen()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) pokeScreen()
        return super.dispatchKeyEvent(event)
    }

    /** Keeps the screen on while reading, until [SCREEN_ON_MS] pass without a touch or key. */
    private fun pokeScreen() {
        main.removeCallbacks(screenOffRunnable)
        if (!settings.keepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.postDelayed(screenOffRunnable, SCREEN_ON_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !menuVisible && panel == null) window.setSystemBarsVisible(false, !theme.dark)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(saveRunnable)
        save(notify = true)
        settings.readingSpeed = speed
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        searchToken++
        engine?.destroy()
        reflowable?.let { book -> worker.execute { runCatching { book.close() } } }
        worker.shutdown()
        searcher.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val ACTION_HIGHLIGHT = 1001
        private const val ACTION_NOTE = 1002
        private const val ACTION_LOOKUP = 1003
        private const val MAX_RESULTS = 500
        private const val SCREEN_ON_MS = 10 * 60 * 1000L
        private const val DIM_ZONE = 0.1f
    }
}
