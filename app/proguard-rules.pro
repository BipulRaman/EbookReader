# The reader page's script calls these through WebView's JavaScript bridge; R8 can't see those
# calls, so keep every @JavascriptInterface method under its original name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
