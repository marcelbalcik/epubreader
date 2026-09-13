package de.lesen.reader.dict.android

import android.content.Context
import de.lesen.reader.dict.DictLookup
import de.lesen.reader.dict.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the installed dictionary and runs lookups off the main thread.
 *
 * Compound splitting can issue a few hundred index probes, which is fast but not
 * free; the reader shows the sheet with a spinner the instant a tap arrives and
 * fills it in when this returns (spec section 4.4).
 */
class DictRepository(private val context: Context) {

    private val installer = DictInstaller(context)
    private val openLock = Mutex()

    private var source: SqliteDictSource? = null
    private var lookup: DictLookup? = null

    private val _state = MutableStateFlow<DictInstaller.State>(DictInstaller.State.Idle)
    val state: StateFlow<DictInstaller.State> = _state.asStateFlow()

    /** Idempotent. Call from a coroutine on app start and from the retry button. */
    suspend fun ensureReady(): DictInstaller.State = openLock.withLock {
        lookup?.let { return (_state.value) }

        _state.value = DictInstaller.State.Installing(0f)
        val installed = installer.install { fraction ->
            _state.value = DictInstaller.State.Installing(fraction)
        }
        _state.value = installed
        if (installed is DictInstaller.State.Ready) {
            withContext(Dispatchers.IO) {
                runCatching { SqliteDictSource.open(installed.db) }
                    .onSuccess { opened ->
                        if (opened.selfCheck()) {
                            source = opened
                            lookup = DictLookup(opened)
                        } else {
                            opened.close()
                            _state.value = DictInstaller.State.Failed(
                                DictInstaller.Reason.CHECKSUM,
                                "row counts in meta do not match the database",
                            )
                        }
                    }
                    .onFailure {
                        _state.value = DictInstaller.State.Failed(
                            DictInstaller.Reason.IO, it.message,
                        )
                    }
            }
        }
        _state.value
    }

    val isReady: Boolean get() = lookup != null

    suspend fun lookup(surface: String): LookupResult = withContext(Dispatchers.IO) {
        val engine = lookup ?: run {
            ensureReady()
            lookup
        } ?: return@withContext LookupResult.miss(surface)
        engine.lookup(surface)
    }

    /** For the multi-word selection list: looked up in one pass off the main thread. */
    suspend fun lookupAll(words: List<String>): List<LookupResult> = withContext(Dispatchers.IO) {
        val engine = lookup ?: run {
            ensureReady()
            lookup
        } ?: return@withContext words.map { LookupResult.miss(it) }
        words.map { engine.lookup(it) }
    }

    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) { source?.meta(key) }

    fun close() {
        source?.close()
        source = null
        lookup = null
    }
}
