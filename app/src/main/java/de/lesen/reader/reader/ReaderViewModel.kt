package de.lesen.reader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.lesen.reader.data.Book
import de.lesen.reader.data.BookmarkOrHistory
import de.lesen.reader.data.Position
import de.lesen.reader.data.VocabItem
import de.lesen.reader.dict.LookupPath
import de.lesen.reader.dict.LookupResult
import de.lesen.reader.di.AppContainer
import de.lesen.reader.epub.EpubParser
import de.lesen.reader.epub.EpubStructure
import de.lesen.reader.epub.TocEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the composable should tell the WebView to do next. */
sealed interface ReaderCommand {
    data class LoadChapter(val url: String, val spineIndex: Int) : ReaderCommand
    data class Restore(val blockIndex: Int, val charOffset: Int) : ReaderCommand
    data class TurnPage(val delta: Int) : ReaderCommand
    data class GoToFragment(val fragment: String) : ReaderCommand
    data object GoToFirstPage : ReaderCommand
    data object GoToLastPage : ReaderCommand
    data class ApplySettings(val json: String) : ReaderCommand
    data object ClearHighlight : ReaderCommand
    data object SendSelection : ReaderCommand
}

/** The lookup sheet's state: a spinner appears the instant a tap arrives. */
sealed interface SheetState {
    data object Hidden : SheetState
    data class Loading(val surface: String) : SheetState
    data class Word(
        val result: LookupResult,
        val sentence: String,
        val saved: Boolean = false,
    ) : SheetState

    data class Selection(val results: List<LookupResult>, val tooMany: Int? = null) : SheetState
}

data class ReaderUiState(
    val book: Book? = null,
    val structure: EpubStructure? = null,
    val spineIndex: Int = 0,
    val chapterTitle: String = "",
    val pageInChapter: Int = 0,
    val pagesInChapter: Int = 1,
    val bookPercent: Float = 0f,
    val chromeVisible: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
)

class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    private val _sheet = MutableStateFlow<SheetState>(SheetState.Hidden)
    val sheet: StateFlow<SheetState> = _sheet.asStateFlow()

    private val _jumpBack = MutableStateFlow<BookmarkOrHistory?>(null)
    val jumpBack: StateFlow<BookmarkOrHistory?> = _jumpBack.asStateFlow()

    private val commandChannel = Channel<ReaderCommand>(Channel.BUFFERED)
    val commands = commandChannel.receiveAsFlow()

    val settings = container.settings.settings

    private var spineCharCounts: List<Int> = emptyList()
    private var pendingRestore: Pair<Int, Int>? = null
    private var pendingFragment: String? = null
    private var goToLastPageOnLoad = false
    private var saveJob: Job? = null
    private var lookupJob: Job? = null
    private var chromeJob: Job? = null
    private var lastPosition = Triple(0, 0, 0)   // spine, block, offset

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch { container.dict.ensureReady() }
    }

    // ---- opening ----------------------------------------------------------

    private suspend fun open() {
        val book = container.database.books().byId(bookId)
        if (book == null) {
            _ui.value = _ui.value.copy(loading = false, error = "This book is no longer here.")
            return
        }
        val structure = withContext(Dispatchers.IO) {
            runCatching { EpubParser.parse(File(book.contentDir)) }
        }.getOrElse {
            _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Unreadable EPUB")
            return
        }

        spineCharCounts = book.spineChars.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { List(structure.spine.size) { 1 } }

        val saved = container.database.positions().forBook(bookId)
        val spineIndex = (saved?.spineIndex ?: 0).coerceIn(0, structure.spine.lastIndex)
        pendingRestore = saved?.let { it.blockIndex to it.charOffset }

        container.database.books().touch(bookId, System.currentTimeMillis())

        _ui.value = _ui.value.copy(
            book = book,
            structure = structure,
            spineIndex = spineIndex,
            chapterTitle = chapterTitleFor(structure, spineIndex),
            loading = false,
        )
        loadChapter(spineIndex)
        scheduleChromeHide()
    }

    private fun chapterTitleFor(structure: EpubStructure, spineIndex: Int): String {
        val href = structure.spine.getOrNull(spineIndex)?.href ?: return ""
        return structure.toc.firstOrNull { it.href == href }?.label
            ?: structure.metadata.title
    }

    private fun loadChapter(spineIndex: Int) {
        val structure = _ui.value.structure ?: return
        val item = structure.spine.getOrNull(spineIndex) ?: return
        _ui.value = _ui.value.copy(
            spineIndex = spineIndex,
            chapterTitle = chapterTitleFor(structure, spineIndex),
        )
        send(ReaderCommand.LoadChapter(ReaderAssets.bookUrl(item.href), spineIndex))
    }

    /** Called from onPageFinished: the chapter is parsed, reader.js is live. */
    fun onChapterReady() {
        send(ReaderCommand.ApplySettings(container.settings.current.toJson()))
        val fragment = pendingFragment
        val restore = pendingRestore
        when {
            fragment != null -> {
                pendingFragment = null
                send(ReaderCommand.GoToFragment(fragment))
            }

            restore != null -> {
                pendingRestore = null
                send(ReaderCommand.Restore(restore.first, restore.second))
            }

            goToLastPageOnLoad -> {
                goToLastPageOnLoad = false
                send(ReaderCommand.GoToLastPage)
            }

            else -> send(ReaderCommand.GoToFirstPage)
        }
    }

    // ---- taps -------------------------------------------------------------

    fun onWordTapped(word: String, sentence: String, blockIndex: Int, charOffset: Int) {
        lookupJob?.cancel()
        _sheet.value = SheetState.Loading(word)
        showChrome(auto = false)
        lookupJob = viewModelScope.launch {
            val result = container.dict.lookup(word)
            _sheet.value = SheetState.Word(result, sentence)
        }
        rememberPosition(blockIndex, charOffset)
    }

    /**
     * A tap that did not land on a word. The outer 12% strips turn pages; the
     * centre toggles the chrome (spec sections 6 and 8.2). reader.js also sends
     * left/right here when a swipe runs off the end of a chapter, which is the
     * same action: turn, and cross into the next spine item if needed.
     */
    fun onTapEmpty(zone: String) {
        when (zone) {
            ReaderBridge.ZONE_LEFT -> turnPage(-1)
            ReaderBridge.ZONE_RIGHT -> turnPage(1)
            else -> toggleChrome()
        }
    }

    fun turnPage(delta: Int) {
        val state = _ui.value
        val structure = state.structure ?: return
        val atStart = state.pageInChapter <= 0
        val atEnd = state.pageInChapter >= state.pagesInChapter - 1

        when {
            delta > 0 && atEnd -> {
                val next = state.spineIndex + 1
                if (next <= structure.spine.lastIndex) {
                    pendingRestore = null
                    goToLastPageOnLoad = false
                    loadChapter(next)
                }
            }

            delta < 0 && atStart -> {
                val previous = state.spineIndex - 1
                if (previous >= 0) {
                    pendingRestore = null
                    // Landing on page 0 of the previous chapter would skip the
                    // whole chapter the reader just came from.
                    goToLastPageOnLoad = true
                    loadChapter(previous)
                }
            }

            else -> send(ReaderCommand.TurnPage(delta))
        }
    }

    fun onPositionChanged(blockIndex: Int, charOffset: Int, page: Int, pages: Int) {
        val state = _ui.value
        val percent = progressPercent(state.spineIndex, page, pages)
        _ui.value = state.copy(
            pageInChapter = page,
            pagesInChapter = pages,
            bookPercent = percent,
        )
        rememberPosition(blockIndex, charOffset)
    }

    /** Cumulative characters, so the percentage means the same after a jump. */
    private fun progressPercent(spineIndex: Int, page: Int, pages: Int): Float {
        val total = spineCharCounts.sum().toFloat()
        if (total <= 0f) return 0f
        val before = spineCharCounts.take(spineIndex).sum().toFloat()
        val chapter = spineCharCounts.getOrElse(spineIndex) { 0 }.toFloat()
        val within = if (pages > 1) (page.toFloat() / (pages - 1).toFloat()) else 1f
        return ((before + chapter * within.coerceIn(0f, 1f)) / total * 100f).coerceIn(0f, 100f)
    }

    private fun rememberPosition(blockIndex: Int, charOffset: Int) {
        val spineIndex = _ui.value.spineIndex
        lastPosition = Triple(spineIndex, blockIndex, charOffset)
        // Debounced: a swipe through ten pages is one write, not ten.
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            savePositionNow()
        }
    }

    fun savePositionNow() {
        val (spineIndex, blockIndex, charOffset) = lastPosition
        val percent = _ui.value.bookPercent
        viewModelScope.launch {
            container.database.positions().save(
                Position(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    blockIndex = blockIndex,
                    charOffset = charOffset,
                    progressPercent = percent,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---- chrome -----------------------------------------------------------

    fun toggleChrome() {
        if (_ui.value.chromeVisible) hideChrome() else showChrome(auto = true)
    }

    fun showChrome(auto: Boolean) {
        _ui.value = _ui.value.copy(chromeVisible = true)
        chromeJob?.cancel()
        if (auto) scheduleChromeHide()
    }

    fun hideChrome() {
        chromeJob?.cancel()
        _ui.value = _ui.value.copy(chromeVisible = false)
    }

    private fun scheduleChromeHide() {
        chromeJob?.cancel()
        chromeJob = viewModelScope.launch {
            delay(CHROME_TIMEOUT_MS)
            _ui.value = _ui.value.copy(chromeVisible = false)
        }
    }

    // ---- TOC --------------------------------------------------------------

    fun jumpTo(entry: TocEntry) {
        val structure = _ui.value.structure ?: return
        val target = structure.spineIndexOf(entry.href)
        if (target < 0) return

        val (spineIndex, blockIndex, charOffset) = lastPosition
        viewModelScope.launch {
            val id = container.database.history().insert(
                BookmarkOrHistory(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    blockIndex = blockIndex,
                    charOffset = charOffset,
                    label = _ui.value.chapterTitle,
                    createdAt = System.currentTimeMillis(),
                )
            )
            _jumpBack.value = container.database.history().lastJump(bookId)?.takeIf { it.id == id }
        }

        pendingFragment = entry.fragment
        pendingRestore = null
        goToLastPageOnLoad = false
        if (target == _ui.value.spineIndex) {
            onChapterReady()          // same chapter: just move within it
        } else {
            loadChapter(target)
        }
        showChrome(auto = true)
    }

    /** The "return to previous position" snackbar action (spec section 8.3). */
    fun returnToJumpOrigin() {
        val entry = _jumpBack.value ?: return
        _jumpBack.value = null
        pendingRestore = entry.blockIndex to entry.charOffset
        pendingFragment = null
        if (entry.spineIndex == _ui.value.spineIndex) {
            send(ReaderCommand.Restore(entry.blockIndex, entry.charOffset))
            pendingRestore = null
        } else {
            loadChapter(entry.spineIndex)
        }
        viewModelScope.launch { container.database.history().delete(entry) }
    }

    fun dismissJumpBack() {
        _jumpBack.value = null
    }

    // ---- lookup sheet -----------------------------------------------------

    fun dismissSheet() {
        lookupJob?.cancel()
        _sheet.value = SheetState.Hidden
        send(ReaderCommand.ClearHighlight)
        showChrome(auto = true)
    }

    fun lookUpAgain(surface: String) {
        lookupJob?.cancel()
        _sheet.value = SheetState.Loading(surface)
        lookupJob = viewModelScope.launch {
            val result = container.dict.lookup(surface)
            val sentence = (_sheet.value as? SheetState.Word)?.sentence.orEmpty()
            _sheet.value = SheetState.Word(result, sentence)
        }
    }

    /**
     * Long-press selection (spec section 7). Each word is looked up on its own
     * and shown as a list; there is no sentence translation, and past eight
     * words the sheet says so rather than pretending.
     */
    fun onSelection(text: String) {
        lookupJob?.cancel()
        val words = text.split(Regex("[^\\p{L}'’-]+")).filter { it.length > 1 }
        if (words.isEmpty()) {
            _sheet.value = SheetState.Hidden
            return
        }
        if (words.size > MAX_SELECTION_WORDS) {
            _sheet.value = SheetState.Selection(emptyList(), tooMany = words.size)
            return
        }
        _sheet.value = SheetState.Loading(words.first())
        lookupJob = viewModelScope.launch {
            _sheet.value = SheetState.Selection(container.dict.lookupAll(words))
        }
    }

    fun requestSelectionLookup() = send(ReaderCommand.SendSelection)

    // ---- vocab ------------------------------------------------------------

    fun saveWord() {
        val state = _sheet.value as? SheetState.Word ?: return
        val result = state.result
        val entry = result.entries.firstOrNull() ?: return
        viewModelScope.launch {
            container.database.vocab().insert(
                VocabItem(
                    bookId = bookId,
                    surface = result.surface,
                    lemma = entry.lemma,
                    pos = entry.pos,
                    glosses = entry.glosses.joinToString("; "),
                    grammar = listOfNotNull(entry.article, entry.plural).joinToString(", ")
                        .ifEmpty { null },
                    sentence = state.sentence.ifEmpty { null },
                    createdAt = System.currentTimeMillis(),
                )
            )
            _sheet.value = state.copy(saved = true)
        }
    }

    fun saveFromSelection(result: LookupResult) {
        val entry = result.entries.firstOrNull() ?: return
        viewModelScope.launch {
            container.database.vocab().insert(
                VocabItem(
                    bookId = bookId,
                    surface = result.surface,
                    lemma = entry.lemma,
                    pos = entry.pos,
                    glosses = entry.glosses.joinToString("; "),
                    grammar = listOfNotNull(entry.article, entry.plural).joinToString(", ")
                        .ifEmpty { null },
                    sentence = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---- settings ---------------------------------------------------------

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        container.settings.update(transform)
        send(ReaderCommand.ApplySettings(container.settings.current.toJson()))
    }

    /** Re-applied after an orientation change, which re-paginates in reader.js. */
    fun reapplySettings() = send(ReaderCommand.ApplySettings(container.settings.current.toJson()))

    val lookupPathLabel: (LookupPath) -> Int = { path ->
        when (path) {
            LookupPath.EXACT -> de.lesen.reader.R.string.path_exact
            LookupPath.VARIANT -> de.lesen.reader.R.string.path_variant
            LookupPath.HEURISTIC -> de.lesen.reader.R.string.path_heuristic
            LookupPath.COMPOUND -> de.lesen.reader.R.string.path_compound
            LookupPath.FUZZY -> de.lesen.reader.R.string.path_fuzzy
            LookupPath.NONE -> de.lesen.reader.R.string.path_none
        }
    }

    private fun send(command: ReaderCommand) {
        commandChannel.trySend(command)
    }

    override fun onCleared() {
        savePositionNow()
        super.onCleared()
    }

    companion object {
        const val MAX_SELECTION_WORDS = 8
        private const val CHROME_TIMEOUT_MS = 2_500L
    }
}
