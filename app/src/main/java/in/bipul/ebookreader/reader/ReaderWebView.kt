package `in`.bipul.ebookreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The page view: a locked-down WebView (no file/content access, no zoom, no scrolling) whose
 * text-selection toolbar gets the reader's own actions (Highlight, Note, Look up) in front of
 * the system ones (Copy, Share, Translate...).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(context: Context, private val actions: List<Pair<Int, String>>) : WebView(context) {
    /** Called with the action id and the ActionMode to finish once the selection has been read. */
    var onSelectionAction: ((Int, ActionMode) -> Unit)? = null

    init {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            offscreenPreRaster = true
            safeBrowsingEnabled = false
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isLongClickable = true
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(wrap(callback))

    private fun wrap(callback: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = callback.onCreateActionMode(mode, menu)
            actions.forEachIndexed { i, (id, title) ->
                menu.add(Menu.NONE, id, i, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = callback.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (actions.any { it.first == item.itemId }) {
                onSelectionAction?.invoke(item.itemId, mode)
                return true
            }
            return callback.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }
}
