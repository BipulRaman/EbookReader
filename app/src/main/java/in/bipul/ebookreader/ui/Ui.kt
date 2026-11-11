package `in`.bipul.ebookreader.ui

import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

@Suppress("DEPRECATION")
private const val EDGE_TO_EDGE_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

/** Stable system-bar + cutout insets: they don't change when the bars hide (immersive reading). */
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Lays the window out behind the system bars (mandatory from Android 15) and hands the bar,
 * display-cutout and keyboard insets to [apply] (by default: padding on [root]).
 */
@Suppress("DEPRECATION")
fun Window.setUpEdgeToEdge(root: View, apply: (View, Insets) -> Unit = { v, i -> v.setPadding(i.left, i.top, i.right, i.bottom) }) {
    if (Build.VERSION.SDK_INT >= 30) {
        setDecorFitsSystemWindows(false)
    } else {
        decorView.systemUiVisibility = EDGE_TO_EDGE_FLAGS
    }
    root.setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            // Ignoring visibility keeps the page from re-flowing when the reader hides the bars.
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            apply(view, Insets(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)))
            WindowInsets.CONSUMED
        } else {
            apply(view, Insets(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, maxOf(insets.stableInsetBottom, insets.systemWindowInsetBottom)))
            insets.consumeSystemWindowInsets()
        }
    }
}

/** Shows or hides the status and navigation bars; while hidden a swipe reveals them briefly. */
@Suppress("DEPRECATION")
fun Window.setSystemBarsVisible(visible: Boolean, lightBars: Boolean) {
    if (Build.VERSION.SDK_INT >= 30) {
        val controller = insetsController ?: return
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(if (lightBars) light else 0, light)
        if (visible) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        }
    } else {
        var flags = EDGE_TO_EDGE_FLAGS
        if (!visible) flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (lightBars) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility = flags
    }
}

/** Light or dark status/navigation bar icons without changing their visibility. */
@Suppress("DEPRECATION")
fun Window.setLightSystemBars(light: Boolean) {
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decorView.systemUiVisibility = if (light) decorView.systemUiVisibility or flags else decorView.systemUiVisibility and flags.inv()
    }
}

/** Rounds a view's corners by clipping to its outline. */
fun View.roundCorners(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
    clipToOutline = true
}

/** A frame with a book cover's 2:3 shape (height follows width). */
class CoverFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    init {
        roundCorners(context.dp(4f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 3 / 2, MeasureSpec.EXACTLY),
        )
    }
}
