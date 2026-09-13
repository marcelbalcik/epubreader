package de.lesen.reader.reader

import android.webkit.JavascriptInterface

/**
 * The JS -> Kotlin half of the contract in spec section 7, exposed to reader.js
 * as `window.Android`.
 *
 * Every method here is called on a WebView JavaScript thread, never the main
 * thread, so each one hands straight over to the callbacks and does no work
 * itself. R8 must not rename these (see proguard-rules.pro): reader.js calls
 * them by name, and a renamed method turns every tap into silence.
 */
class ReaderBridge(
    private val onWord: (word: String, sentence: String, blockIndex: Int, charOffset: Int) -> Unit,
    private val onEmpty: (zone: String) -> Unit,
    private val onPosition: (blockIndex: Int, charOffset: Int, page: Int, pages: Int) -> Unit,
    private val onSelection: (text: String) -> Unit,
) {

    @JavascriptInterface
    fun onWordTapped(word: String?, sentence: String?, blockIndex: Int, charOffset: Int) {
        val cleaned = word?.trim().orEmpty()
        if (cleaned.isEmpty()) return
        onWord(cleaned, sentence?.trim().orEmpty(), blockIndex, charOffset)
    }

    @JavascriptInterface
    fun onTapEmpty(zone: String?) {
        onEmpty(
            when (zone) {
                ZONE_LEFT, ZONE_RIGHT, ZONE_CENTER -> zone
                else -> ZONE_CENTER
            }
        )
    }

    @JavascriptInterface
    fun onPositionChanged(blockIndex: Int, charOffset: Int, pageInChapter: Int, pagesInChapter: Int) {
        onPosition(
            blockIndex.coerceAtLeast(0),
            charOffset.coerceAtLeast(0),
            pageInChapter.coerceAtLeast(0),
            pagesInChapter.coerceAtLeast(1),
        )
    }

    @JavascriptInterface
    fun onSelectionTranslate(text: String?) {
        onSelection(text?.trim().orEmpty())
    }

    companion object {
        const val NAME = "Android"
        const val ZONE_LEFT = "left"
        const val ZONE_CENTER = "center"
        const val ZONE_RIGHT = "right"
    }
}
