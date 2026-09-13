package de.lesen.reader.epub

import android.util.Log
import android.util.Xml

/**
 * The Android wiring for [EpubParser]: the framework's XmlPullParser and logcat.
 *
 * Kept in its own file so EpubParser itself stays free of android.util and can
 * be unit-tested against real EPUB archives on the desktop JVM.
 */
val AndroidEpubParser: EpubParser by lazy {
    EpubParser(
        newPullParser = { Xml.newPullParser() },
        onWarning = { message -> Log.w("EpubParser", message) },
    )
}
