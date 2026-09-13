package de.lesen.reader.data

import android.content.Context
import de.lesen.reader.reader.ReaderFont
import de.lesen.reader.reader.ReaderSettings
import de.lesen.reader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reader settings, in SharedPreferences.
 *
 * Not DataStore: this is seven scalars for one user on one device, and
 * SharedPreferences reads them synchronously at construction, which means the
 * first chapter is laid out with the right font size instead of being
 * re-paginated a frame later.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    val current: ReaderSettings get() = _settings.value

    private fun load(): ReaderSettings = ReaderSettings(
        fontSize = prefs.getInt(KEY_FONT_SIZE, 19),
        lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, 1.55f),
        margin = prefs.getInt(KEY_MARGIN, 24),
        font = enumOf(prefs.getString(KEY_FONT, null), ReaderFont.SERIF),
        theme = enumOf(prefs.getString(KEY_THEME, null), ReaderTheme.LIGHT),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        volumeKeysTurnPages = prefs.getBoolean(KEY_VOLUME_KEYS, true),
    ).sane()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value).sane()
        _settings.value = next
        prefs.edit()
            .putInt(KEY_FONT_SIZE, next.fontSize)
            .putFloat(KEY_LINE_HEIGHT, next.lineHeight)
            .putInt(KEY_MARGIN, next.margin)
            .putString(KEY_FONT, next.font.name)
            .putString(KEY_THEME, next.theme.name)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .putBoolean(KEY_VOLUME_KEYS, next.volumeKeysTurnPages)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_FONT_SIZE = "fontSize"
        const val KEY_LINE_HEIGHT = "lineHeight"
        const val KEY_MARGIN = "margin"
        const val KEY_FONT = "font"
        const val KEY_THEME = "theme"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        const val KEY_VOLUME_KEYS = "volumeKeys"
    }
}
