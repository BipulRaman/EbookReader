package `in`.bipul.ebookreader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Loads cover thumbnails off the main thread into a small memory cache. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Shows the cover from [file] in [view], or [placeholder] when there is none. */
    fun load(view: ImageView, file: File, placeholder: () -> Drawable, targetHeight: Int) {
        val key = file.path + ":" + file.lastModified()
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val bitmap = if (file.isFile) decode(file, targetHeight) else null
            if (bitmap != null) cache.put(key, bitmap)
            main.post {
                if (view.tag != key) return@post
                if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageDrawable(placeholder())
            }
        }
    }

    fun evict(file: File) {
        cache.snapshot().keys.filter { it.startsWith(file.path + ":") }.forEach(cache::remove)
    }

    private fun decode(file: File, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * A generated cover for books without one: a colour picked from the title, the title set in a
 * serif face and the author underneath, like a plain cloth-bound book.
 */
class PlaceholderCover(private val title: String, private val author: String, density: Float) : Drawable() {
    private val palette = intArrayOf(
        0xFF2E4A62.toInt(), 0xFF6B3A3A.toInt(), 0xFF3E5641.toInt(), 0xFF5B4A6B.toInt(),
        0xFF7A5230.toInt(), 0xFF2F5D5C.toInt(), 0xFF4A4A4A.toInt(), 0xFF6A4C2E.toInt(),
    )
    private val base = palette[Math.floorMod(title.hashCode(), palette.size)]
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = density }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    override fun onBoundsChange(bounds: Rect) {
        bg.shader = LinearGradient(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), lighten(base), base, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, bg)
        val pad = b.width() * 0.1f
        val inner = (b.width() - 2 * pad).toInt().coerceAtLeast(1)
        canvas.drawLine(b.left + pad, b.top + pad, b.right - pad, b.top + pad, rule)
        canvas.drawLine(b.left + pad, b.bottom - pad, b.right - pad, b.bottom - pad, rule)

        titlePaint.textSize = b.width() * 0.12f
        val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(b.left + pad, b.top + b.height() * 0.22f)
        titleLayout.draw(canvas)
        canvas.restore()

        if (author.isNotEmpty()) {
            authorPaint.textSize = b.width() * 0.08f
            val authorLayout = StaticLayout.Builder.obtain(author, 0, author.length, authorPaint, inner)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(b.left + pad, b.bottom - pad * 1.6f - authorLayout.height)
            authorLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun lighten(c: Int): Int {
        val f = 0.18f
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
