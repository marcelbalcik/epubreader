package de.lesen.reader.epub

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import de.lesen.reader.data.Book
import de.lesen.reader.data.BookDao
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports an EPUB chosen through SAF (spec section 5).
 *
 *   copy into filesDir/books/<uuid>/book.epub
 *   extract the whole archive to .../content/
 *   parse structure, count characters per chapter, decode a cover thumbnail
 *
 * Extracting up front rather than streaming out of the zip on every resource
 * request keeps the WebView's asset loader trivial and page turns free of zip
 * seeks; disk is cheap and this app is sideloaded.
 */
class BookImporter(
    private val context: Context,
    private val books: BookDao,
) {

    sealed interface Result {
        data class Imported(val bookId: Long, val title: String) : Result
        data class AlreadyPresent(val bookId: Long, val title: String) : Result
        data class Refused(val rejection: ImportRejection, val detail: String?) : Result
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        books.byUri(uri.toString())?.let {
            return@withContext Result.AlreadyPresent(it.id, it.title)
        }

        val bookDir = File(booksRoot(context), UUID.randomUUID().toString())
        val contentDir = File(bookDir, "content")
        try {
            contentDir.mkdirs()
            val epubFile = File(bookDir, "book.epub")
            copyIn(uri, epubFile)

            extract(epubFile, contentDir)

            val structure = EpubParser.parse(contentDir)
            val spineChars = structure.spine.map { item ->
                TextCount.countChars(File(contentDir, item.href))
            }
            val cover = structure.metadata.coverHref?.let { href ->
                makeThumbnail(File(contentDir, href), File(bookDir, "cover.png"))
            }

            val id = books.insert(
                Book(
                    uri = uri.toString(),
                    title = structure.metadata.title,
                    author = structure.metadata.creator,
                    language = structure.metadata.language,
                    coverPath = cover?.absolutePath,
                    contentDir = contentDir.absolutePath,
                    opfPath = structure.opfPath,
                    totalChars = spineChars.sum().toLong(),
                    spineChars = spineChars.joinToString(","),
                    addedAt = System.currentTimeMillis(),
                    lastOpenedAt = 0L,
                )
            )
            Result.Imported(id, structure.metadata.title)
        } catch (e: EpubException) {
            bookDir.deleteRecursively()
            Log.w(TAG, "import refused: ${e.rejection}", e)
            Result.Refused(e.rejection, e.message)
        } catch (e: IOException) {
            bookDir.deleteRecursively()
            Log.e(TAG, "import failed", e)
            Result.Refused(ImportRejection.IO, e.message)
        } catch (e: SecurityException) {
            bookDir.deleteRecursively()
            Result.Refused(ImportRejection.IO, e.message)
        }
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        // The extracted directory is the parent of content/, and holds the
        // copied .epub and the cover thumbnail too.
        File(book.contentDir).parentFile?.deleteRecursively()
        books.delete(book)
    }

    private fun copyIn(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw EpubException(ImportRejection.IO, "could not open the chosen file")
        input.use { source ->
            FileOutputStream(target).use { out ->
                source.copyTo(out, BUFFER)
                out.fd.sync()
            }
        }
    }

    /**
     * Extracts the archive, refusing any entry whose canonical destination
     * escapes [target] (zip slip, spec section 5).
     */
    private fun extract(epub: File, target: File) {
        val canonicalTarget = target.canonicalFile
        val zip = try {
            ZipFile(epub)
        } catch (e: IOException) {
            throw EpubException(ImportRejection.NOT_A_ZIP, e.message)
        }

        zip.use { archive ->
            if (archive.getEntry("META-INF/encryption.xml") != null) {
                // Refusing beats rendering a chapter of mojibake.
                throw EpubException(ImportRejection.ENCRYPTED)
            }
            if (archive.getEntry("META-INF/container.xml") == null) {
                throw EpubException(ImportRejection.NO_CONTAINER)
            }

            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                val destination = File(canonicalTarget, entry.name)
                val canonical = destination.canonicalFile
                if (!canonical.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw EpubException(ImportRejection.UNSAFE_ENTRY, entry.name)
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                    continue
                }
                canonical.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(canonical).use { out -> input.copyTo(out, BUFFER) }
                }
            }
        }
    }

    /** Decoded once at import and cached as PNG, so the library grid is cheap. */
    private fun makeThumbnail(source: File, target: File): File? {
        if (!source.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var scale = 1
            while (bounds.outWidth / (scale * 2) >= THUMB_WIDTH) scale *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
            FileOutputStream(target).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            target
        }.onFailure { Log.w(TAG, "cover decode failed for ${source.name}", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "BookImporter"
        private const val BUFFER = 1 shl 16
        private const val THUMB_WIDTH = 480

        fun booksRoot(context: Context): File =
            File(context.filesDir, "books").apply { mkdirs() }
    }
}

/** Character counts for the whole-book progress percentage (spec section 6). */
object TextCount {

    private val TAG_RE = Regex("<[^>]*>")
    private val SCRIPT_RE = Regex("(?is)<(script|style)\\b.*?</\\1>")
    private val ENTITY_RE = Regex("&[a-zA-Z#0-9]+;")
    private val WS_RE = Regex("\\s+")

    fun countChars(file: File): Int {
        if (!file.isFile) return 0
        return runCatching {
            val html = file.readText(Charsets.UTF_8)
            plainText(html).length
        }.getOrDefault(0)
    }

    fun plainText(html: String): String = html
        .replace(SCRIPT_RE, " ")
        .replace(TAG_RE, " ")
        .replace(ENTITY_RE, "x")     // one character per entity is close enough
        .replace(WS_RE, " ")
        .trim()
}
