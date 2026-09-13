package de.lesen.reader.reader

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.lesen.reader.MainActivity
import de.lesen.reader.R
import java.io.File

/**
 * The reader (spec section 8.2).
 *
 * The WebView is created once, remembered by book id, and handed to AndroidView
 * with a factory that just returns it. Nothing in this composable may recreate
 * it: a new WebView means a reload, and a reload means the reader loses their
 * place mid-page (spec section 12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenVocab: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val sheet by viewModel.sheet.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val jumpBack by viewModel.jumpBack.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val book = ui.book
    val lookupLabel = stringResource(R.string.lookup_action)

    // One WebView per book, built the first time this book is opened.
    val webView = remember(book?.id) {
        book?.let { current ->
            ReaderWebView(context).also { web ->
                web.setLookupLabel(lookupLabel)
                web.onLookupSelection = { viewModel.requestSelectionLookup() }
                web.configure(
                    contentRoot = File(current.contentDir),
                    bridge = ReaderBridge(
                        onWord = viewModel::onWordTapped,
                        onEmpty = viewModel::onTapEmpty,
                        onPosition = viewModel::onPositionChanged,
                        onSelection = viewModel::onSelection,
                    ),
                    onChapterReady = viewModel::onChapterReady,
                    backgroundColor = MaterialTheme.colorScheme.background.toArgb(),
                )
            }
        }
    }

    // Spec section 8.2: keep the screen on while reading, user-toggleable.
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Volume keys turn pages (spec section 6). They never reach Compose focus,
    // so MainActivity relays them here while the reader is on screen.
    DisposableEffect(settings.volumeKeysTurnPages) {
        val activity = context as? MainActivity
        if (settings.volumeKeysTurnPages) {
            activity?.volumeKeyHandler = { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        viewModel.turnPage(1)
                        true
                    }

                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        viewModel.turnPage(-1)
                        true
                    }

                    else -> false
                }
            }
        } else {
            activity?.volumeKeyHandler = null
        }
        onDispose { activity?.volumeKeyHandler = null }
    }

    LaunchedEffect(webView) {
        val web = webView ?: return@LaunchedEffect
        viewModel.commands.collect { command ->
            when (command) {
                is ReaderCommand.LoadChapter -> web.loadUrl(command.url)
                is ReaderCommand.Restore ->
                    web.eval("Lesen.restore(${command.blockIndex}, ${command.charOffset})")

                is ReaderCommand.TurnPage -> web.eval("Lesen.turnPage(${command.delta})")
                is ReaderCommand.GoToFragment ->
                    web.eval("Lesen.goToFragment(${command.fragment.asJsString()})")

                ReaderCommand.GoToFirstPage -> web.eval("Lesen.goToPage(0)")
                ReaderCommand.GoToLastPage -> web.eval("Lesen.goToLastPage()")
                is ReaderCommand.ApplySettings ->
                    web.eval("Lesen.applySettings(${command.json.asJsString()})")

                ReaderCommand.ClearHighlight -> web.eval("Lesen.clearHighlight()")
                ReaderCommand.SendSelection -> web.eval("Lesen.sendSelection()")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.savePositionNow() }
    }

    BackHandler {
        when {
            sheet != SheetState.Hidden -> viewModel.dismissSheet()
            showToc -> showToc = false
            showSettings -> showSettings = false
            else -> {
                viewModel.savePositionNow()
                onBack()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (webView != null) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        ui.error?.let { error ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, modifier = Modifier.padding(24.dp))
            }
        }

        // Chrome: auto-hides after 2.5 s, toggles on a centre tap.
        AnimatedVisibility(
            visible = ui.chromeVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {
                    Text(
                        ui.chapterTitle.ifEmpty { book?.title.orEmpty() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.savePositionNow()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showToc = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.toc),
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            )
        }

        AnimatedVisibility(
            visible = ui.chromeVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.page_of, ui.pageInChapter + 1, ui.pagesInChapter),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(R.string.book_percent, ui.bookPercent.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // "Return to previous position" after a TOC jump (spec section 8.3).
        jumpBack?.let { entry ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Snackbar(
                    action = {
                        TextButton(onClick = viewModel::returnToJumpOrigin) {
                            Text(stringResource(R.string.return_action))
                        }
                    },
                    dismissAction = {
                        TextButton(onClick = viewModel::dismissJumpBack) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                ) {
                    Text(
                        entry.label?.let { "${stringResource(R.string.return_to_position)}: $it" }
                            ?: stringResource(R.string.return_to_position)
                    )
                }
            }
        }
    }

    if (showToc) {
        TocSheet(
            structure = ui.structure,
            currentSpineIndex = ui.spineIndex,
            onSelect = { entry ->
                showToc = false
                viewModel.jumpTo(entry)
            },
            onDismiss = { showToc = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onChange = { transform -> viewModel.updateSettings(transform) },
            onDismiss = { showSettings = false },
            onOpenVocab = {
                showSettings = false
                onOpenVocab()
            },
        )
    }

    if (sheet != SheetState.Hidden) {
        LookupSheet(
            state = sheet,
            onDismiss = viewModel::dismissSheet,
            onSave = viewModel::saveWord,
            onSaveOne = viewModel::saveFromSelection,
            onLookUp = viewModel::lookUpAgain,
            pathLabel = viewModel.lookupPathLabel,
        )
    }
}

private fun ReaderWebView.eval(script: String) {
    evaluateJavascript(script, null)
}

/** Quotes a value for interpolation into an evaluateJavascript call. */
internal fun String.asJsString(): String = buildString {
    append('"')
    for (ch in this@asJsString) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\u2028' -> append("\\u2028")
        '\u2029' -> append("\\u2029")
        '<' -> append("\\u003C")
        else -> append(ch)
    }
    append('"')
}
