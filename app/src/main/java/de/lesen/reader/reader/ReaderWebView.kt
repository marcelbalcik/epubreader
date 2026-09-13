package de.lesen.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * The one WebView the reader owns (spec sections 6, 7 and 12).
 *
 * Subclassed for exactly one reason: to add "Nachschlagen" to the long-press
 * action mode. Everything else is configured in [configure] so the Compose layer
 * can stay a thin wrapper around a stable instance - recreating the WebView on
 * recomposition would reload the chapter and lose the reader's place.
 */
class ReaderWebView(context: Context) : WebView(context) {

    /** Invoked when the reader taps the "Nachschlagen" action item. */
    var onLookupSelection: (() -> Unit)? = null

    private var lookupLabel: String = "Nachschlagen"

    fun setLookupLabel(label: String) {
        lookupLabel = label
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(callback: ActionMode.Callback?): ActionMode.Callback? {
        if (callback == null) return null
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val handled = callback.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, MENU_LOOKUP, 0, lookupLabel)
                return handled
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_LOOKUP) {
                    onLookupSelection?.invoke()
                    mode.finish()
                    return true
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                callback.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode, view: View?, outRect: Rect?) {
                if (callback is ActionMode.Callback2) {
                    callback.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }

    companion object {
        private const val MENU_LOOKUP = 0x1E5E4
    }
}

/**
 * Applies the settings of spec section 6 and wires the bridge.
 *
 * blockNetworkLoads is the belt to the manifest's braces: the app holds no
 * INTERNET permission, so a network load could not succeed anyway, but a book
 * that tries should fail instantly rather than stalling the page.
 */
@SuppressLint("SetJavaScriptEnabled")
fun ReaderWebView.configure(
    contentRoot: File,
    bridge: ReaderBridge,
    onChapterReady: () -> Unit,
    backgroundColor: Int,
) {
    val loader: WebViewAssetLoader = ReaderAssets.loader(context, contentRoot)

    settings.apply {
        javaScriptEnabled = true              // reader.js is the whole feature
        domStorageEnabled = false
        databaseEnabled = false
        blockNetworkLoads = true
        blockNetworkImage = true
        loadsImagesAutomatically = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        textZoom = 100                        // our own --font-size decides
        layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
    }

    WebView.setWebContentsDebuggingEnabled(de.lesen.reader.BuildConfig.DEBUG)
    setBackgroundColor(backgroundColor)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = OVER_SCROLL_NEVER
    isLongClickable = true

    addJavascriptInterface(bridge, ReaderBridge.NAME)

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            // Everything the book can legitimately reach is on our own domain.
            // Anything else (http, mailto, intent:) is refused outright.
            return url.host != ReaderAssets.DOMAIN
        }

        override fun onPageFinished(view: WebView, url: String) {
            onChapterReady()
        }
    }
}
