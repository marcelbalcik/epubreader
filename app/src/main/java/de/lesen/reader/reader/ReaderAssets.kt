package de.lesen.reader.reader

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "ReaderAssets"

/** An empty response, which is how a PathHandler says "no such resource". */
private fun notFound() = WebResourceResponse(null, null, null)

/**
 * Serves the extracted book and the app's own reader assets over
 * https://appassets.androidplatform.net/ (spec section 6).
 *
 * Deliberately not file:// - a file:// document plus allowFileAccessFromFileURLs
 * would let a book's own script read the rest of internal storage, and that is
 * where the dictionary and the reading positions live.
 *
 *   /book/<path>   the extracted EPUB, rooted at this book's content directory
 *   /lesen/<path>  reader.css, reader.js and the bundled fonts, from assets/
 */
object ReaderAssets {

    const val DOMAIN = "appassets.androidplatform.net"
    const val BOOK_PREFIX = "/book/"
    const val ASSET_PREFIX = "/lesen/"
    const val BASE = "https://$DOMAIN$BOOK_PREFIX"

    fun loader(context: Context, contentRoot: File): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler(BOOK_PREFIX, BookPathHandler(contentRoot))
            .addPathHandler(ASSET_PREFIX, AppAssetPathHandler(context))
            .build()

    fun bookUrl(href: String): String = BASE + href.trimStart('/')
}

/**
 * The extracted content directory. Every request is resolved and then checked
 * against the root's canonical path, so a book cannot reach outside its own
 * folder even if one of its links tries to.
 *
 * Chapters are rewritten on the way out to carry reader.css and reader.js;
 * everything else is streamed straight off disk.
 */
class BookPathHandler(root: File) : WebViewAssetLoader.PathHandler {

    private val root: File = root.canonicalFile

    override fun handle(path: String): WebResourceResponse? {
        val decoded = de.lesen.reader.epub.EpubPaths.percentDecode(path)
        val canonical = runCatching { File(root, decoded).canonicalFile }.getOrNull()
            ?: return notFound()
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            Log.w(TAG, "refusing a request that points outside the book: $path")
            return notFound()
        }
        if (!canonical.isFile) return notFound()

        val mime = ReaderHtml.mimeOf(canonical.name)
        return try {
            if (ReaderHtml.isDocument(mime)) {
                val injected = ReaderHtml.inject(ReaderHtml.decode(canonical.readBytes()))
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
                )
            } else {
                WebResourceResponse(mime, null, FileInputStream(canonical))
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not serve $path", e)
            notFound()
        }
    }
}

/** reader.css, reader.js and the bundled fonts, straight out of assets/. */
class AppAssetPathHandler(private val context: Context) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/')
        if (clean.contains("..")) return notFound()
        return try {
            WebResourceResponse(
                ReaderHtml.mimeOf(clean),
                "utf-8",
                context.assets.open(clean),
            )
        } catch (e: IOException) {
            Log.w(TAG, "missing app asset: $clean")
            notFound()
        }
    }
}
