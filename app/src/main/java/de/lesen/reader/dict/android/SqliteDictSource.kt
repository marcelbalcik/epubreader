package de.lesen.reader.dict.android

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import de.lesen.reader.dict.DictEntry
import de.lesen.reader.dict.DictSource
import de.lesen.reader.dict.Glosses
import java.io.File

/**
 * The shipped dictionary, opened read-only straight through SQLiteDatabase
 * (spec section 2: it is a prebuilt artifact with its own schema, so Room's
 * migrations would only get in the way).
 *
 * The SQL here is the same SQL the unit tests run through JDBC, so the lookup
 * algorithm is exercised against this schema on the desktop too.
 */
class SqliteDictSource private constructor(private val db: SQLiteDatabase) : DictSource,
    AutoCloseable {

    override fun entriesFor(formNorm: String): List<DictEntry> {
        db.rawQuery(ENTRIES_SQL, arrayOf(formNorm)).use { c ->
            val out = ArrayList<DictEntry>(c.count)
            while (c.moveToNext()) {
                out.add(
                    DictEntry(
                        id = c.getLong(0),
                        lemma = c.getString(1),
                        pos = c.getString(2),
                        gender = if (c.isNull(3)) null else c.getString(3),
                        plural = if (c.isNull(4)) null else c.getString(4),
                        ipa = if (c.isNull(5)) null else c.getString(5),
                        glosses = Glosses.parse(c.getString(6) ?: "[]"),
                        isLemmaForm = c.getInt(7) == 1,
                    )
                )
            }
            return out
        }
    }

    override fun prefixSearch(formNorm: String, limit: Int): List<String> {
        val bounded = limit.coerceIn(1, 100)
        db.rawQuery(prefixSql(bounded), arrayOf(formNorm)).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    override fun meta(key: String): String? {
        db.rawQuery("SELECT value FROM meta WHERE key = ? LIMIT 1", arrayOf(key)).use { c ->
            return if (c.moveToNext()) c.getString(0) else null
        }
    }

    /** Cheap sanity check after opening: the counts in `meta` must match reality. */
    fun selfCheck(): Boolean {
        val declared = meta("entry_count")?.toLongOrNull() ?: return false
        db.rawQuery("SELECT COUNT(*) FROM entry", null).use { c ->
            if (!c.moveToNext()) return false
            val actual = c.getLong(0)
            if (actual != declared) {
                Log.e(TAG, "dictionary claims $declared entries but holds $actual")
                return false
            }
        }
        return true
    }

    override fun close() = db.close()

    companion object {
        private const val TAG = "SqliteDictSource"

        private const val ENTRIES_SQL =
            "SELECT e.id, e.lemma, e.pos, e.gender, e.plural, e.ipa, e.glosses, f.is_lemma " +
                "FROM form f JOIN entry e ON e.id = f.entry_id " +
                "WHERE f.form_norm = ? ORDER BY f.is_lemma DESC, e.id"

        /** LIMIT takes an integer, and binding it as text relies on coercion. */
        private fun prefixSql(limit: Int) =
            "SELECT DISTINCT form_norm FROM form WHERE form_norm LIKE ? || '%' " +
                "ORDER BY LENGTH(form_norm), form_norm LIMIT $limit"

        fun open(file: File): SqliteDictSource {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            return SqliteDictSource(db)
        }
    }
}
