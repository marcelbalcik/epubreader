package de.lesen.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.lesen.reader.vocab.VocabCard

/**
 * App data (spec section 9). Books, where they were extracted to, where the
 * reader left off, saved words, and the jump-back history.
 *
 * Two columns beyond the spec's list, both recorded in docs/DECISIONS.md:
 * `opfPath`, so the OPF can be re-parsed on open without re-scanning the
 * archive, and `spineChars`, the per-chapter character counts that the
 * whole-book progress percentage is computed from (spec section 6 asks for them
 * to be counted once at import and cached).
 */
@Entity(tableName = "book")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The SAF document URI the file came from, kept only for display/debug. */
    val uri: String,
    val title: String,
    val author: String?,
    val language: String?,
    val coverPath: String?,
    /** filesDir/books/<uuid>/content - the extracted archive root. */
    val contentDir: String,
    /** OPF location relative to [contentDir]. */
    val opfPath: String,
    val totalChars: Long,
    /** Per-spine-item character counts, comma separated, in spine order. */
    val spineChars: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "position",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class Position(
    @PrimaryKey val bookId: Long,
    val spineIndex: Int,
    /**
     * Index of the block-level element the reader is on, as stamped by
     * reader.js. Deliberately not a pixel offset: font size, margin and
     * orientation all move pixels, and losing the place in a 600-page novel is
     * the failure this column exists to prevent (spec section 6).
     */
    val blockIndex: Int,
    val charOffset: Int,
    val progressPercent: Float,
    val updatedAt: Long,
)

@Entity(
    tableName = "vocab",
    indices = [Index("bookId"), Index("lemma")],
)
data class VocabItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** The word exactly as it appeared in the book. */
    override val surface: String,
    override val lemma: String,
    val pos: String?,
    /** Joined with "; " - this is a flat card, not a dictionary row. */
    override val glosses: String,
    /** Article and plural for nouns ("die, Wohnungen"), null otherwise. */
    override val grammar: String?,
    /** The sentence the word was tapped in, clipped to 200 chars. */
    override val sentence: String?,
    val createdAt: Long,
) : VocabCard

/** Jump-back history for TOC navigation (spec sections 8.3 and 9). */
@Entity(
    tableName = "history",
    indices = [Index("bookId")],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class BookmarkOrHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val spineIndex: Int,
    val blockIndex: Int,
    val charOffset: Int = 0,
    val label: String?,
    @ColumnInfo(defaultValue = "0") val isBookmark: Boolean = false,
    val createdAt: Long,
)

/** Library row: a book plus the progress bar it shows. */
data class BookWithProgress(
    val book: Book,
    val progressPercent: Float,
)
