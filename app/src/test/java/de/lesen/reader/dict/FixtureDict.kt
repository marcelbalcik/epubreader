package de.lesen.reader.dict

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * A DictSource over a real SQLite file, via JDBC, for unit tests.
 *
 * It speaks exactly the SQL that SqliteDictSource speaks on the device, so the
 * lookup algorithm is exercised against the shipped schema (spec 4.2) rather
 * than against a hand-rolled in-memory stub.
 */
class JdbcDictSource(dbFile: File) : DictSource, AutoCloseable {

    private val con: Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

    override fun entriesFor(formNorm: String): List<DictEntry> {
        con.prepareStatement(
            """
            SELECT e.id, e.lemma, e.pos, e.gender, e.plural, e.ipa, e.glosses, f.is_lemma
              FROM form f JOIN entry e ON e.id = f.entry_id
             WHERE f.form_norm = ?
             ORDER BY f.is_lemma DESC, e.id
            """.trimIndent()
        ).use { st ->
            st.setString(1, formNorm)
            st.executeQuery().use { rs ->
                val out = ArrayList<DictEntry>()
                while (rs.next()) {
                    out.add(
                        DictEntry(
                            id = rs.getLong(1),
                            lemma = rs.getString(2),
                            pos = rs.getString(3),
                            gender = rs.getString(4),
                            plural = rs.getString(5),
                            ipa = rs.getString(6),
                            glosses = Glosses.parse(rs.getString(7) ?: "[]"),
                            isLemmaForm = rs.getInt(8) == 1,
                        )
                    )
                }
                return out
            }
        }
    }

    override fun prefixSearch(formNorm: String, limit: Int): List<String> {
        con.prepareStatement(
            """
            SELECT DISTINCT form_norm FROM form WHERE form_norm LIKE ? || '%'
             ORDER BY LENGTH(form_norm), form_norm LIMIT ?
            """.trimIndent()
        ).use { st ->
            st.setString(1, formNorm)
            st.setInt(2, limit)
            st.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    override fun meta(key: String): String? {
        con.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun close() = con.close()
}

/**
 * Builds a small dictionary in the shipped schema, covering milestone 1's
 * acceptance words plus the neighbours compound splitting needs.
 *
 * Hermetic on purpose: the real dump is 1-2 GB and build_dict.py needs Python,
 * so the Kotlin side asserts the same outcomes on its own fixture. The build
 * pipeline itself is tested on the Python side against
 * tools/dictbuild/fixtures/sample_de.jsonl.
 */
object FixtureDict {

    /** lemma, pos, gender, plural, ipa, glosses, extra (non-lemma) forms. */
    private data class Row(
        val lemma: String,
        val pos: String,
        val gender: String? = null,
        val plural: String? = null,
        val ipa: String? = null,
        val glosses: List<String> = emptyList(),
        val forms: List<String> = emptyList(),
    )

    private val ROWS = listOf(
        Row(
            "Haus", "noun", "n", "Häuser", "/haʊ̯s/",
            listOf("house", "building", "home"),
            listOf("Hauses", "Häuser", "Häusern"),
        ),
        Row(
            "gehen", "verb", glosses = listOf("to go, to walk", "to work, to function"),
            forms = listOf("gehe", "geht", "ging", "gingen", "gegangen"),
        ),
        Row(
            "aufstehen", "verb", glosses = listOf("to stand up, to get up"),
            forms = listOf("aufgestanden", "stand"),
        ),
        // No declension table: "schönste" is deliberately absent, so the
        // superlative must come out of iterated suffix stripping.
        Row("schön", "adj", ipa = "/ʃøːn/", glosses = listOf("beautiful", "nice"),
            forms = listOf("schöner")),
        Row("Autorin", "noun", "f", "Autorinnen", glosses = listOf("female author"),
            forms = listOf("Autorinnen")),
        Row("Autor", "noun", "m", "Autoren", glosses = listOf("author, writer"),
            forms = listOf("Autoren", "Autors")),
        Row("Kind", "noun", "n", "Kinder", glosses = listOf("child"),
            forms = listOf("Kinder", "Kindern", "Kindes")),
        Row("Buch", "noun", "n", "Bücher", glosses = listOf("book"),
            forms = listOf("Bücher", "Büchern", "Buches")),
        Row("dass", "conj", glosses = listOf("that (introducing a clause)")),
        Row("Straße", "noun", "f", "Straßen", glosses = listOf("street, road"),
            forms = listOf("Straßen")),
        Row("Flasche", "noun", "f", "Flaschen", glosses = listOf("bottle"),
            forms = listOf("Flaschen")),
        Row("Wasser", "noun", "n", "Wässer", glosses = listOf("water"),
            forms = listOf("Wassers")),
        Row("Achse", "noun", "f", "Achsen", glosses = listOf("axis, axle"),
            forms = listOf("Achsen")),
        Row("Donau", "noun", "f", glosses = listOf("Danube")),
        Row("Dampf", "noun", "m", "Dämpfe", glosses = listOf("steam"),
            forms = listOf("Dämpfe", "Dampfes")),
        Row("Schifffahrt", "noun", "f", "Schifffahrten", glosses = listOf("shipping")),
        Row("Fahrt", "noun", "f", "Fahrten", glosses = listOf("journey, ride"),
            forms = listOf("Fahrten")),
        Row("Schiff", "noun", "n", "Schiffe", glosses = listOf("ship"),
            forms = listOf("Schiffe", "Schiffes")),
        Row("gut", "adj", glosses = listOf("good"), forms = listOf("besser")),
        Row("laufen", "verb", glosses = listOf("to run"),
            forms = listOf("läuft", "lief", "gelaufen")),
        Row("sehen", "verb", glosses = listOf("to see"),
            forms = listOf("sieht", "sah", "gesehen")),
        Row("Bild", "noun", "n", "Bilder", glosses = listOf("picture"),
            forms = listOf("Bilder", "Bildes")),
        Row("Bildung", "noun", "f", "Bildungen", glosses = listOf("education"),
            forms = listOf("Bildungen")),
        Row("Zeit", "noun", "f", "Zeiten", glosses = listOf("time"), forms = listOf("Zeiten")),
        // A noun and a verb sharing a surface form, so ambiguity is covered.
        Row("Mann", "noun", "m", "Männer", glosses = listOf("man", "husband"),
            forms = listOf("Männer", "Mannes")),
    )

    fun build(dir: File): File {
        val db = File(dir, "dict-fixture.db")
        if (db.exists()) db.delete()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { con ->
            con.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE entry (
                      id INTEGER PRIMARY KEY, lemma TEXT NOT NULL, pos TEXT NOT NULL,
                      gender TEXT, plural TEXT, ipa TEXT, glosses TEXT NOT NULL)
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE form (
                      form_norm TEXT NOT NULL,
                      entry_id INTEGER NOT NULL REFERENCES entry(id),
                      is_lemma INTEGER NOT NULL, UNIQUE (form_norm, entry_id))
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX idx_form_norm ON form(form_norm)")
                st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            }
            val insEntry = con.prepareStatement(
                "INSERT INTO entry (id, lemma, pos, gender, plural, ipa, glosses) " +
                    "VALUES (?,?,?,?,?,?,?)"
            )
            val insForm = con.prepareStatement(
                "INSERT OR IGNORE INTO form (form_norm, entry_id, is_lemma) VALUES (?,?,?)"
            )
            ROWS.forEachIndexed { index, row ->
                val id = (index + 1).toLong()
                insEntry.setLong(1, id)
                insEntry.setString(2, row.lemma)
                insEntry.setString(3, row.pos)
                insEntry.setString(4, row.gender)
                insEntry.setString(5, row.plural)
                insEntry.setString(6, row.ipa)
                insEntry.setString(7, Glosses.encode(row.glosses))
                insEntry.executeUpdate()

                val lemmaNorm = Normalizer.norm(row.lemma)
                insForm.setString(1, lemmaNorm)
                insForm.setLong(2, id)
                insForm.setInt(3, 1)
                insForm.executeUpdate()
                for (f in row.forms) {
                    val fn = Normalizer.norm(f)
                    if (fn == lemmaNorm) continue
                    insForm.setString(1, fn)
                    insForm.setLong(2, id)
                    insForm.setInt(3, 0)
                    insForm.executeUpdate()
                }
            }
            con.prepareStatement("INSERT INTO meta (key, value) VALUES (?,?)").use { st ->
                for ((k, v) in mapOf(
                    "schema_version" to "1",
                    "build_id" to "fixture",
                    "entry_count" to ROWS.size.toString(),
                )) {
                    st.setString(1, k)
                    st.setString(2, v)
                    st.executeUpdate()
                }
            }
        }
        return db
    }
}
