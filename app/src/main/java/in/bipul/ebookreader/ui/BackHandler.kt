package `in`.bipul.ebookreader.ui

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Intercepts back only while [wanted] says something is open (a panel, a search bar), so the
 * system's predictive "back to home" animation still works the rest of the time. Call [update]
 * whenever that state changes; Android 12 and older route back through [Activity.onBackPressed],
 * which should call [handle].
 */
class BackHandler(private val activity: Activity, private val wanted: () -> Boolean, private val onBack: () -> Unit) {
    private var registered = false
    private val callback: Any? = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { onBack() } else null

    fun update() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = wanted()
        val cb = callback as OnBackInvokedCallback
        if (want && !registered) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            registered = true
        } else if (!want && registered) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            registered = false
        }
    }

    /** For onBackPressed (Android 12 and older): returns true when back was consumed. */
    fun handle(): Boolean {
        if (!wanted()) return false
        onBack()
        return true
    }
}
