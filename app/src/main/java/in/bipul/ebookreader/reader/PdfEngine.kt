package `in`.bipul.ebookreader.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import `in`.bipul.ebookreader.book.BookException
import `in`.bipul.ebookreader.library.Locator
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shows PDFs a page per screen with Android's PdfRenderer. */
class PdfEngine(context: Context, file: File, private val settings: ReaderSettings, private val host: EngineHost) : ReaderEngine {
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: SecurityException) {
        fd.close()
        throw BookException("This PDF is password-protected and can't be opened")
    }
    val pageCount = renderer.pageCount
    private val pages = PdfPageView(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val sizes = arrayOfNulls<FloatArray>(pageCount)

    override val view: View get() = pages
    override var position: Position? = null
        private set

    fun start(at: Locator?) {
        pages.page = (at?.chapter ?: 0).coerceIn(0, max(0, pageCount - 1))
        applySettings()
        report()
        host.onReady()
    }

    override fun next() = pages.turn(1)

    override fun prev() = pages.turn(-1)

    override fun goTo(locator: Locator) = pages.jump(locator.chapter)

    override fun goToFraction(fraction: Float) = pages.jump(chapterAt(fraction))

    override fun chapterAt(fraction: Float) = (fraction * pageCount).toInt().coerceIn(0, pageCount - 1)

    override fun applySettings() {
        pages.setTheme(settings.theme)
    }

    override fun destroy() {
        main.removeCallbacksAndMessages(null)
        executor.execute {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
        executor.shutdown()
    }

    private fun report() {
        val p = pages.page
        val pos = Position(p, p, pageCount, 0, 0, 0, p.toFloat() / pageCount, (p + 1f) / pageCount, p == pageCount - 1)
        position = pos
        host.onPosition(pos)
    }

    /** Page size in PDF points; must run on the render thread. */
    private fun sizeOf(index: Int): FloatArray = sizes[index] ?: renderer.openPage(index).use { floatArrayOf(it.width.toFloat(), it.height.toFloat()) }.also { sizes[index] = it }

    @SuppressLint("ViewConstructor")
    private inner class PdfPageView(context: Context) : View(context) {
        var page = 0
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var dragX = 0f
        private var dragging = false
        private var animator: ValueAnimator? = null

        private val cache = LruCache<Int, Bitmap>(5)
        private val loading = HashSet<Int>()
        private var renderedFor = 0L
        private var tile: Bitmap? = null
        private var tileKey: String? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var background = Color.WHITE
        private val rect = RectF()
        private val tileRunnable = Runnable { requestTile() }

        fun setTheme(theme: ReaderTheme) {
            background = theme.background
            paint.colorFilter = if (theme == ReaderTheme.WHITE) {
                null
            } else {
                // Maps white paper to the theme's page colour and black ink to its text colour.
                val paper = theme.background
                val ink = theme.text
                fun row(shift: Int): FloatArray {
                    val p = (paper shr shift) and 0xFF
                    val i = (ink shr shift) and 0xFF
                    return floatArrayOf((p - i) / 255f, i.toFloat())
                }
                val r = row(16); val g = row(8); val b = row(0)
                ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            r[0], 0f, 0f, 0f, r[1],
                            0f, g[0], 0f, 0f, g[1],
                            0f, 0f, b[0], 0f, b[1],
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            setBackgroundColor(background)
            invalidate()
        }

        fun jump(target: Int) {
            val t = target.coerceIn(0, pageCount - 1)
            animator?.cancel()
            dragX = 0f
            showPage(t)
        }

        fun turn(dir: Int) {
            val target = page + dir
            if (target !in 0 until pageCount) {
                if (dir > 0) host.onEndOfBook()
                return
            }
            if (zoom > 1.01f || !settings.pageAnimation || width == 0) {
                jump(target)
                return
            }
            animateDrag(-dir * width.toFloat()) { showPage(target) }
        }

        private fun showPage(p: Int) {
            page = p
            zoom = 1f
            panX = 0f
            panY = 0f
            dragX = 0f
            tile = null
            tileKey = null
            invalidate()
            report()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            cache.evictAll()
            loading.clear()
            renderedFor++
            tile = null
            invalidate()
        }

        // ---------------------------------------------------------- rendering

        private fun fitRect(index: Int, out: RectF): Boolean {
            val size = sizes[index] ?: return false
            val scale = min(width / size[0], height / size[1])
            val w = size[0] * scale
            val h = size[1] * scale
            out.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            return true
        }

        private fun request(index: Int) {
            if (index !in 0 until pageCount || cache.get(index) != null || index in loading || width == 0) return
            loading.add(index)
            val generation = renderedFor
            val w = width
            val h = height
            executor.execute {
                val bitmap = runCatching {
                    val size = sizeOf(index)
                    val scale = min(w / size[0], h / size[1])
                    val bw = (size[0] * scale).toInt().coerceAtLeast(1)
                    val bh = (size[1] * scale).toInt().coerceAtLeast(1)
                    renderer.openPage(index).use { pg ->
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
                main.post {
                    if (generation != renderedFor) return@post
                    loading.remove(index)
                    if (bitmap != null) cache.put(index, bitmap)
                    invalidate()
                }
            }
        }

        /** Re-renders just the visible part of a zoomed page at full screen resolution. */
        private fun requestTile() {
            if (zoom <= 1.01f || width == 0) return
            val p = page
            val z = zoom
            val r = RectF()
            if (!zoomedRect(r)) return
            val key = "$p:$z:${r.left}:${r.top}:$width:$height"
            if (key == tileKey) return
            tileKey = key
            val w = width
            val h = height
            executor.execute {
                val bitmap = runCatching {
                    val size = sizeOf(p)
                    val scale = r.width() / size[0]
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(r.left, r.top)
                    }
                    renderer.openPage(p).use { pg ->
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            pg.render(it, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
                main.post {
                    if (tileKey == key && bitmap != null) {
                        tile = bitmap
                        invalidate()
                    }
                }
            }
        }

        private fun zoomedRect(out: RectF): Boolean {
            if (!fitRect(page, out)) return false
            val w = out.width() * zoom
            val h = out.height() * zoom
            val left = (width - w) / 2 + panX
            val top = (height - h) / 2 + panY
            out.set(left, top, left + w, top + h)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(background)
            if (sizes[page] == null) {
                request(page)
                return
            }
            if (zoomedRect(rect)) {
                rect.offset(dragX, 0f)
                drawPage(canvas, page, rect)
                val t = tile
                if (t != null && zoom > 1.01f && dragX == 0f) {
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawBitmap(t, 0f, 0f, paint)
                    canvas.restore()
                }
            }
            if (dragX != 0f) {
                val neighbor = if (dragX < 0) page + 1 else page - 1
                if (neighbor in 0 until pageCount) {
                    if (fitRect(neighbor, rect)) {
                        rect.offset(dragX + if (dragX < 0) width else -width, 0f)
                        drawPage(canvas, neighbor, rect)
                    } else {
                        request(neighbor)
                    }
                }
            }
            if (!dragging && animator == null) {
                request(page + 1)
                request(page - 1)
            }
        }

        private fun drawPage(canvas: Canvas, index: Int, dst: RectF) {
            val bitmap = cache.get(index)
            if (bitmap == null) {
                request(index)
                return
            }
            canvas.drawBitmap(bitmap, null, dst, paint)
        }

        // ---------------------------------------------------------- gestures

        private fun clampPan() {
            val r = RectF()
            if (!fitRect(page, r)) return
            val w = r.width() * zoom
            val h = r.height() * zoom
            val maxX = max(0f, (w - width) / 2)
            val maxY = max(0f, (h - height) / 2)
            panX = panX.coerceIn(-maxX, maxX)
            panY = panY.coerceIn(-maxY, maxY)
        }

        private fun zoomTo(newZoom: Float, fx: Float, fy: Float) {
            val z = newZoom.coerceIn(1f, 5f)
            val ratio = z / zoom
            // Keep the point under the fingers in place.
            panX = (fx - width / 2f) - (fx - width / 2f - panX) * ratio
            panY = (fy - height / 2f) - (fy - height / 2f - panY) * ratio
            zoom = z
            if (zoom <= 1.01f) {
                zoom = 1f
                panX = 0f
                panY = 0f
            }
            clampPan()
            tile = null
            invalidate()
            scheduleTile()
        }

        private fun scheduleTile() {
            removeCallbacks(tileRunnable)
            if (zoom > 1.01f) postDelayed(tileRunnable, 140)
        }

        private fun animateDrag(to: Float, end: () -> Unit = {}) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(dragX, to).apply {
                duration = WebEngine.PAGE_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    dragX = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        animator = null
                        if (!cancelled) end()
                        invalidate()
                    }
                })
                start()
            }
        }

        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (dragX != 0f) animateDrag(0f)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val x = e.x
                when {
                    x > width * 0.84f && e.y < height * 0.09f -> host.onCornerTap()
                    x < width * 0.28f -> turn(-1)
                    x > width * 0.72f -> turn(1)
                    else -> host.onMenuTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val from = zoom
                val to = if (zoom > 1.01f) 1f else 2.5f
                ValueAnimator.ofFloat(from, to).apply {
                    duration = 220
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { zoomTo(it.animatedValue as Float, e.x, e.y) }
                    start()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                if (zoom > 1.01f) {
                    panX -= distanceX
                    panY -= distanceY
                    clampPan()
                    tile = null
                    invalidate()
                    scheduleTile()
                } else {
                    if (!dragging && abs(distanceY) > abs(distanceX)) return false
                    dragging = true
                    animator?.cancel()
                    var d = dragX - distanceX
                    if ((page == 0 && d > 0) || (page == pageCount - 1 && d < 0)) d = dragX - distanceX * 0.3f
                    dragX = d
                    invalidate()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!dragging || zoom > 1.01f) return false
                dragging = false
                when {
                    velocityX < -800 && page < pageCount - 1 -> animateDrag(-width.toFloat()) { showPage(page + 1) }
                    velocityX > 800 && page > 0 -> animateDrag(width.toFloat()) { showPage(page - 1) }
                    else -> settle()
                }
                return true
            }
        })

        private fun settle() {
            when {
                dragX < -width * 0.2f && page < pageCount - 1 -> animateDrag(-width.toFloat()) { showPage(page + 1) }
                dragX > width * 0.2f && page > 0 -> animateDrag(width.toFloat()) { showPage(page - 1) }
                dragX != 0f -> animateDrag(0f)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) {
                    dragging = false
                    settle()
                }
            }
            return true
        }

        init {
            isFocusable = false
            contentDescription = null
        }
    }
}
