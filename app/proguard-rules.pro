# The @JavascriptInterface bridge is called by name from reader.js; without this
# R8 renames onWordTapped and friends and every tap silently does nothing.
-keepclassmembers class de.lesen.reader.reader.ReaderBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Room generates these.
-keep class de.lesen.reader.data.** { *; }
