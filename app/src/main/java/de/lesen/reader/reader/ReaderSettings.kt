package de.lesen.reader.reader

/**
 * Everything the settings sheet controls (spec sections 6 and 8.5).
 *
 * Pure Kotlin: the values are clamped here and serialised to the JSON blob
 * reader.js turns into CSS custom properties, so both the clamping and the
 * payload are unit-testable. A font size outside 14..28px would not crash
 * anything, it would just quietly ruin the pagination.
 */
enum class ReaderTheme(
    val bg: String,
    val fg: String,
    val link: String,
    val hit: String,
    /** Dark and sepia must override the book's own hardcoded colours. */
    val forceColors: Boolean,
) {
    LIGHT("#FDFDFB", "#1A1A1A", "#3B5E8C", "rgba(255, 214, 102, 0.55)", false),
    SEPIA("#F4ECD8", "#3A2F22", "#7A5C2E", "rgba(214, 168, 79, 0.45)", true),
    DARK("#121212", "#D8D8D8", "#8AB4F8", "rgba(120, 144, 200, 0.40)", true);

    val cssName: String get() = name.lowercase()
}

enum class ReaderFont(val cssFamily: String) {
    /** Bundled OFL serif; the fallbacks matter only if the font file is gone. */
    SERIF("\"Literata\", Georgia, \"Times New Roman\", serif"),
    SANS("\"Inter\", Roboto, \"Helvetica Neue\", sans-serif"),
    SYSTEM("sans-serif"),
}

data class ReaderSettings(
    val fontSize: Int = 19,
    val lineHeight: Float = 1.55f,
    val margin: Int = 24,
    val font: ReaderFont = ReaderFont.SERIF,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val keepScreenOn: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
) {

    /** Clamped to the ranges in spec section 6. */
    fun sane(): ReaderSettings = copy(
        fontSize = fontSize.coerceIn(FONT_SIZE_RANGE),
        lineHeight = lineHeight.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX),
        margin = margin.coerceIn(MARGIN_RANGE),
    )

    /** The payload for Lesen.applySettings in reader.js. */
    fun toJson(): String {
        val s = sane()
        return buildString {
            append('{')
            append("\"fontSize\":").append(s.fontSize).append(',')
            append("\"lineHeight\":").append(formatLineHeight(s.lineHeight)).append(',')
            append("\"margin\":").append(s.margin).append(',')
            append("\"fontFamily\":\"").append(s.font.cssFamily.replace("\"", "\\\"")).append("\",")
            append("\"theme\":\"").append(s.theme.cssName).append("\",")
            append("\"bg\":\"").append(s.theme.bg).append("\",")
            append("\"fg\":\"").append(s.theme.fg).append("\",")
            append("\"link\":\"").append(s.theme.link).append("\",")
            append("\"hit\":\"").append(s.theme.hit).append("\",")
            append("\"forceColors\":").append(s.theme.forceColors)
            append('}')
        }
    }

    companion object {
        val FONT_SIZE_RANGE = 14..28
        val MARGIN_RANGE = 8..48
        const val LINE_HEIGHT_MIN = 1.2f
        const val LINE_HEIGHT_MAX = 2.0f

        /** One decimal is all the slider offers, and avoids 1.5500000001 in CSS. */
        fun formatLineHeight(value: Float): String {
            val rounded = Math.round(value * 100f) / 100f
            return if (rounded == rounded.toInt().toFloat()) {
                "${rounded.toInt()}.0"
            } else {
                rounded.toString()
            }
        }
    }
}
