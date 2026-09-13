package de.lesen.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import de.lesen.reader.di.AppContainer
import de.lesen.reader.library.LibraryScreen
import de.lesen.reader.reader.ReaderScreen
import de.lesen.reader.ui.LesenTheme
import de.lesen.reader.vocab.VocabScreen

/**
 * The whole app, in one activity.
 *
 * Screen state is three cases, so there is no navigation library: adding one
 * would mean another dependency and another back-stack model to reason about
 * for what is a library, a reader and a word list.
 *
 * Volume keys have to be caught here - they never reach Compose focus - and are
 * relayed to whichever screen registered for them (spec section 6).
 */
class MainActivity : ComponentActivity() {

    /** Set by the reader while it is on screen; returns true if it handled the key. */
    var volumeKeyHandler: ((keyCode: Int) -> Boolean)? = null

    private val container: AppContainer
        get() = (application as LesenApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LesenAppUi(container) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyHandler?.invoke(keyCode) == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the matching key-up so the system volume UI stays away while
        // the reader is using the keys for page turns.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            volumeKeyHandler != null
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

private sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: Long) : Screen
    data object Vocab : Screen
}

@Composable
private fun LesenAppUi(container: AppContainer) {
    var screen by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver<Screen, Long>(
            save = { s ->
                when (s) {
                    is Screen.Reader -> s.bookId
                    Screen.Vocab -> -2L
                    else -> -1L
                }
            },
            restore = { value ->
                when (value) {
                    -1L -> Screen.Library
                    -2L -> Screen.Vocab
                    else -> Screen.Reader(value)
                }
            },
        )
    ) { mutableStateOf<Screen>(Screen.Library) }

    val settings by container.settings.settings.collectAsState()

    LesenTheme(readerTheme = (screen as? Screen.Reader)?.let { settings.theme }) {
        when (val current = screen) {
            Screen.Library -> LibraryScreen(
                viewModel = viewModel(factory = container.libraryFactory()),
                onOpenBook = { screen = Screen.Reader(it) },
                onOpenVocab = { screen = Screen.Vocab },
            )

            is Screen.Reader -> ReaderScreen(
                viewModel = viewModel(
                    key = "reader-${current.bookId}",
                    factory = container.readerFactory(current.bookId),
                ),
                onBack = { screen = Screen.Library },
                onOpenVocab = { screen = Screen.Vocab },
            )

            Screen.Vocab -> VocabScreen(
                viewModel = viewModel(factory = container.vocabFactory()),
                onBack = { screen = Screen.Library },
            )
        }
    }
}
