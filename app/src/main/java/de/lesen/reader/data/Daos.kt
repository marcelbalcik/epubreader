package de.lesen.reader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query(
        """
        SELECT b.*, COALESCE(p.progressPercent, 0) AS progressPercent
          FROM book b LEFT JOIN position p ON p.bookId = b.id
         ORDER BY b.lastOpenedAt DESC, b.addedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<BookWithProgressRow>>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun byId(id: Long): Book?

    @Query("SELECT * FROM book WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Query("UPDATE book SET lastOpenedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Delete
    suspend fun delete(book: Book)
}

/** Flat projection for the library query; Room maps the extra column here. */
data class BookWithProgressRow(
    val id: Long,
    val uri: String,
    val title: String,
    val author: String?,
    val language: String?,
    val coverPath: String?,
    val contentDir: String,
    val opfPath: String,
    val totalChars: Long,
    val spineChars: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val progressPercent: Float,
) {
    fun toBookWithProgress() = BookWithProgress(
        book = Book(
            id = id, uri = uri, title = title, author = author, language = language,
            coverPath = coverPath, contentDir = contentDir, opfPath = opfPath,
            totalChars = totalChars, spineChars = spineChars, addedAt = addedAt,
            lastOpenedAt = lastOpenedAt,
        ),
        progressPercent = progressPercent,
    )
}

@Dao
interface PositionDao {

    @Query("SELECT * FROM position WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): Position?

    @Query("SELECT * FROM position WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<Position?>

    @Upsert
    suspend fun save(position: Position)

    @Query("DELETE FROM position WHERE bookId = :bookId")
    suspend fun clear(bookId: Long)
}

@Dao
interface VocabDao {

    @Query(
        """
        SELECT * FROM vocab
         WHERE (:query = '' OR lemma LIKE '%' || :query || '%'
                OR surface LIKE '%' || :query || '%'
                OR glosses LIKE '%' || :query || '%')
         ORDER BY createdAt DESC
        """
    )
    fun observe(query: String): Flow<List<VocabItem>>

    @Query("SELECT * FROM vocab ORDER BY createdAt")
    suspend fun all(): List<VocabItem>

    @Query("SELECT COUNT(*) FROM vocab WHERE lemma = :lemma")
    suspend fun countFor(lemma: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: VocabItem): Long

    @Delete
    suspend fun delete(item: VocabItem)

    @Query("DELETE FROM vocab WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(entry: BookmarkOrHistory): Long

    @Query(
        "SELECT * FROM history WHERE bookId = :bookId AND isBookmark = 0 " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun lastJump(bookId: Long): BookmarkOrHistory?

    @Query("SELECT * FROM history WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observe(bookId: Long): Flow<List<BookmarkOrHistory>>

    @Delete
    suspend fun delete(entry: BookmarkOrHistory)

    @Query("DELETE FROM history WHERE bookId = :bookId AND isBookmark = 0")
    suspend fun clearJumps(bookId: Long)
}
