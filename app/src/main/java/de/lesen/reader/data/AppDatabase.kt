package de.lesen.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Book::class, Position::class, VocabItem::class, BookmarkOrHistory::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun positions(): PositionDao
    abstract fun vocab(): VocabDao
    abstract fun history(): HistoryDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "lesen.db")
                // One user, one device, no sync: a destructive fallback would
                // throw away their reading positions, so migrations are written
                // by hand if the schema ever changes.
                .build()
    }
}
