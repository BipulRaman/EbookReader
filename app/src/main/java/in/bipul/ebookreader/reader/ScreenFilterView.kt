package `in`.bipul.ebookreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import kotlin.math.min

/**
 * Drawn over the whole reader: a warm tint (multiplied, like a front light's amber LEDs: white
 * turns warm while black stays black) and extra dimming below the screen's lowest backlight.
 */
class ScreenFilterView(context: Context) : View(context) {
    private var warmColor = Color.WHITE
    private var dimColor = Color.TRANSPARENT

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [warmth] and [dim] are 0..1. */
    fun set(warmth: Float, dim: Float) {
        warmColor = warmTint(warmth)
        dimColor = Color.argb((dim.coerceIn(0f, 1f) * 0.65f * 255).toInt(), 0, 0, 0)
        visibility = if (warmth > 0f || dim > 0f) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (warmColor != Color.WHITE) canvas.drawColor(warmColor, PorterDuff.Mode.MULTIPLY)
        if (Color.alpha(dimColor) > 0) canvas.drawColor(dimColor)
    }

    companion object {
        /** White (no change) blended towards a candle-like amber. */
        fun warmTint(warmth: Float): Int {
            val w = warmth.coerceIn(0f, 1f)
            return Color.rgb(255, (255 - 90 * w).toInt(), (255 - 185 * w).toInt())
        }
    }
}

/** A full-screen image with pinch, double-tap zoom and panning (EPUB illustrations, maps). */
@SuppressLint("AppCompatCustomView")
class ZoomImageView(context: Context) : ImageView(context) {
    private val m = Matrix()
    private var base = 1f
    private var zoom = 1f
    private val bounds = RectF()

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()

    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        base = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        zoom = 1f
        m.reset()
        m.postScale(base, base)
        m.postTranslate((width - d.intrinsicWidth * base) / 2, (height - d.intrinsicHeight * base) / 2)
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> (width - bounds.width()) / 2 - bounds.left
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> (height - bounds.height()) / 2 - bounds.top
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    private fun scaleBy(factor: Float, fx: Float, fy: Float) {
        val target = (zoom * factor).coerceIn(1f, 6f)
        val f = target / zoom
        zoom = target
        m.postScale(f, f, fx, fy)
        clamp()
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) reset() else scaleBy(2.5f, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            m.postTranslate(-distanceX, -distanceY)
            clamp()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
