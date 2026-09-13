package de.lesen.reader.dict.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the bundled dictionary once (spec section 12).
 *
 * A .db inside assets/ cannot be opened in place, so dict.db.gz is copied out to
 * filesDir and gunzipped on first launch, verified against the checksum that
 * ships beside it, and then left alone forever. Subsequent launches do nothing
 * but a cheap marker check - no re-copy, no re-hash of a 100+ MB file.
 *
 * The checksum ships as its own asset rather than inside `meta` because a file
 * cannot contain its own hash; `meta` carries the build id and the row counts,
 * which are cross-checked after opening (see SqliteDictSource).
 */
class DictInstaller(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Installing(val fraction: Float) : State
        data class Ready(val db: File, val buildId: String?) : State
        data class Failed(val reason: Reason, val detail: String? = null) : State
    }

    enum class Reason { MISSING_ASSET, CHECKSUM, IO }

    private val dictDir get() = File(context.filesDir, "dict")
    private val dbFile get() = File(dictDir, DB_NAME)
    private val markerFile get() = File(dictDir, "installed.txt")

    /** True when a previous launch already installed this exact build. */
    fun isInstalled(): Boolean {
        val expected = expectedChecksum() ?: return false
        if (!dbFile.isFile || dbFile.length() == 0L) return false
        val marker = runCatching { markerFile.readText().trim() }.getOrNull() ?: return false
        return marker == expected.line
    }

    /**
     * Installs if needed. Safe to call on every launch; returns quickly when
     * the marker matches. [onProgress] is called with 0..1 while unpacking.
     */
    suspend fun install(onProgress: (Float) -> Unit = {}): State = withContext(Dispatchers.IO) {
        val expected = expectedChecksum()
            ?: return@withContext State.Failed(Reason.MISSING_ASSET)

        if (isInstalled()) {
            return@withContext State.Ready(dbFile, expected.buildId)
        }

        dictDir.mkdirs()
        val tmp = File(dictDir, "$DB_NAME.part")
        tmp.delete()

        try {
            val total = assetSize(GZ_NAME).toFloat().coerceAtLeast(1f)
            val digest = MessageDigest.getInstance("SHA-256")
            var readFromAsset = 0L

            context.assets.open(GZ_NAME).use { rawAsset ->
                // Counting the compressed bytes gives an honest progress bar
                // without knowing the uncompressed size up front.
                val counting = object : java.io.FilterInputStream(rawAsset) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            readFromAsset += n
                            onProgress((readFromAsset / total).coerceIn(0f, 1f))
                        }
                        return n
                    }
                }
                GZIPInputStream(counting, BUFFER).use { gz ->
                    DigestInputStream(gz, digest).use { hashed ->
                        FileOutputStream(tmp).use { out ->
                            hashed.copyTo(out, BUFFER)
                            out.fd.sync()
                        }
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected.sha256, ignoreCase = true)) {
                tmp.delete()
                Log.e(TAG, "dictionary checksum mismatch: expected ${expected.sha256}, got $actual")
                return@withContext State.Failed(
                    Reason.CHECKSUM,
                    "expected ${expected.sha256.take(12)}…, got ${actual.take(12)}…",
                )
            }

            dbFile.delete()
            if (!tmp.renameTo(dbFile)) {
                tmp.delete()
                return@withContext State.Failed(Reason.IO, "could not move the unpacked database")
            }
            markerFile.writeText(expected.line)
            onProgress(1f)
            State.Ready(dbFile, expected.buildId)
        } catch (e: IOException) {
            tmp.delete()
            Log.e(TAG, "dictionary install failed", e)
            State.Failed(Reason.IO, e.message)
        }
    }

    fun installedFile(): File? = dbFile.takeIf { it.isFile && it.length() > 0 }

    /** Contents of assets/dict.db.sha256: "<sha256>  <entries>  <forms>  <build_id>". */
    private fun expectedChecksum(): Checksum? = runCatching {
        val line = context.assets.open(SHA_NAME).bufferedReader().use { it.readLine() }.trim()
        val fields = line.split(Regex("\\s+"))
        Checksum(
            sha256 = fields[0],
            entryCount = fields.getOrNull(1)?.toLongOrNull(),
            formCount = fields.getOrNull(2)?.toLongOrNull(),
            buildId = fields.getOrNull(3),
            line = line,
        )
    }.getOrNull()

    private fun assetSize(name: String): Long = runCatching {
        context.assets.openFd(name).use { it.length }
    }.getOrElse {
        // Uncompressed assets have no file descriptor on some devices; fall back
        // to a stream count, which is still cheap next to the gunzip itself.
        context.assets.open(name).use { input ->
            var total = 0L
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            total
        }
    }

    data class Checksum(
        val sha256: String,
        val entryCount: Long?,
        val formCount: Long?,
        val buildId: String?,
        val line: String,
    )

    companion object {
        const val DB_NAME = "dict.db"
        const val GZ_NAME = "dict.db.gz"
        const val SHA_NAME = "dict.db.sha256"
        private const val BUFFER = 1 shl 16
        private const val TAG = "DictInstaller"
    }
}
