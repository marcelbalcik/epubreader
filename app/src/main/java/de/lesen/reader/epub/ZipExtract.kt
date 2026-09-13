package de.lesen.reader.epub

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Unpacks an EPUB (spec section 5).
 *
 * Extracting the whole archive up front, rather than streaming out of the zip on
 * every resource request, keeps the WebView's asset loader trivial and page
 * turns free of zip seeks. Disk is cheap and this app is sideloaded.
 *
 * Free of Android imports so the zip-slip guard can be unit-tested: it is the
 * one piece of this app where getting it wrong would let a file write outside
 * the app's own folder.
 */
object ZipExtract {

    private const val BUFFER = 1 shl 16

    /**
     * Extracts [epub] into [target], refusing:
     *  - anything that is not a zip at all,
     *  - archives with no META-INF/container.xml (so: not an EPUB),
     *  - DRM-encrypted archives (META-INF/encryption.xml), because rendering
     *    those produces a chapter of mojibake rather than an error,
     *  - any entry whose canonical destination escapes [target] (zip slip).
     *
     * Returns the number of files written.
     */
    fun extract(epub: File, target: File): Int {
        val canonicalTarget = target.canonicalFile
        canonicalTarget.mkdirs()

        val archive = try {
            ZipFile(epub)
        } catch (e: IOException) {
            throw EpubException(ImportRejection.NOT_A_ZIP, e.message)
        }

        archive.use { zip ->
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                throw EpubException(ImportRejection.ENCRYPTED)
            }
            if (zip.getEntry("META-INF/container.xml") == null) {
                throw EpubException(ImportRejection.NO_CONTAINER)
            }

            var written = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                val destination = safeDestination(canonicalTarget, entry.name)
                if (entry.isDirectory) {
                    destination.mkdirs()
                    continue
                }
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { out -> input.copyTo(out, BUFFER) }
                }
                written++
            }
            return written
        }
    }

    /**
     * Resolves an entry name inside [root], or throws. Canonical paths are
     * compared, so "../", an absolute name, and a symlink-shaped name are all
     * caught by the same check.
     */
    fun safeDestination(root: File, entryName: String): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, entryName)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: throw EpubException(ImportRejection.UNSAFE_ENTRY, entryName)
        if (canonical == canonicalRoot ||
            !canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            throw EpubException(ImportRejection.UNSAFE_ENTRY, entryName)
        }
        return canonical
    }
}
