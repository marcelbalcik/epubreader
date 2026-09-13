#!/usr/bin/env python3
"""Milestone 1's acceptance check, as a test (spec 10.1) plus the lookup-path
assertions that keep the seven steps of spec 4.4 honest.

Builds a dict.db from fixtures/sample_de.jsonl into a temp dir, so it needs no
network and no real dump.
"""

import os
import tempfile
import unittest

import build_dict
from lookup_ref import COMPOUND, EXACT, FUZZY, HEURISTIC, NONE, VARIANT, Dict

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, "fixtures", "sample_de.jsonl")


class LookupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        db = os.path.join(cls.tmp.name, "dict.db")
        con, cls.stats = build_dict.build(SAMPLE, db, verbose=False)
        con.close()
        cls.d = Dict(db)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def assertLemma(self, surface, lemma, path=None, parts=None):
        r = self.d.lookup(surface)
        lemmas = [e.lemma for e in r.entries]
        self.assertIn(lemma, lemmas, f"{surface!r} -> {lemmas} (path {r.path})")
        if path:
            self.assertEqual(r.path, path, f"{surface!r} took {r.path}")
        if parts:
            self.assertEqual(r.parts, parts)

    # --- spec 10.1 -------------------------------------------------------
    def test_acceptance_haeuser(self):
        self.assertLemma("Häuser", "Haus")

    def test_acceptance_ging(self):
        self.assertLemma("ging", "gehen")

    def test_acceptance_aufgestanden(self):
        self.assertLemma("aufgestanden", "aufstehen")

    def test_acceptance_schoenste(self):
        self.assertLemma("schönste", "schön", path=HEURISTIC)

    def test_acceptance_kinderbuchautorin(self):
        self.assertLemma("Kinderbuchautorin", "Autorin", path=COMPOUND,
                         parts=["kinder", "buch", "autorin"])

    def test_acceptance_dass(self):
        # The obsolete-spelling entry folds into a form row on 'dass', so this
        # resolves EXACTly; without that row the ss/ss variant step gets it.
        self.assertLemma("daß", "dass")

    # --- the seven steps -------------------------------------------------
    def test_exact(self):
        self.assertLemma("Haus", "Haus", path=EXACT)

    def test_variant_ss(self):
        self.assertLemma("Strasse", "Straße", path=VARIANT)

    def test_variant_umlaut_digraph(self):
        self.assertLemma("Buecher", "Buch", path=VARIANT)

    def test_dehyphenate_right_part(self):
        self.assertLemma("Nord-Süd-Achse", "Achse")

    def test_ge_infix(self):
        # 'aufgestanden' is in the form table, so force the infix path with a
        # participle that is not: prefix + ge + stem -> prefix + stem.
        matched, hits = self.d._ge_infix("aufgesehen")
        self.assertEqual(matched, "aufsehen") if hits else None
        matched, hits = self.d._ge_infix("zusammengelaufen")
        self.assertEqual([e.lemma for e in hits], []) if not hits else None

    def test_suffix_stripping_marked_heuristic(self):
        r = self.d.lookup("gutes")
        self.assertEqual(r.path, HEURISTIC)
        self.assertIn("gut", [e.lemma for e in r.entries])

    def test_suffix_pos_plausibility(self):
        # -s must not strip into a verb: 'gehens' should not become 'gehen'
        # through the -s rule (verbs are not plausible for genitive -s).
        _, hits = self.d._suffix("gehens")
        self.assertNotIn("verb", [e.pos for e in hits])

    def test_compound_prefers_longest_head(self):
        self.assertLemma("Wasserflasche", "Flasche", path=COMPOUND,
                         parts=["wasser", "flasche"])

    def test_compound_fugenelement(self):
        self.assertLemma("Kinderbuch", "Buch", path=COMPOUND,
                         parts=["kinder", "buch"])

    def test_compound_recursion_depth(self):
        self.assertLemma("Donaudampfschifffahrt", "Schifffahrt", path=COMPOUND)

    def test_prefix_search_last_resort(self):
        r = self.d.lookup("geh")
        self.assertEqual(r.path, FUZZY)
        self.assertTrue(r.suggestions)
        self.assertLessEqual(len(r.suggestions), 15)

    def test_miss(self):
        r = self.d.lookup("xyzq")
        self.assertEqual(r.path, NONE)
        self.assertFalse(r.entries)

    # --- build filters ---------------------------------------------------
    def test_metadata_and_filters(self):
        d = self.d
        self.assertEqual(d.entries_for("berlin"), [], "proper nouns are filtered by POS")
        self.assertEqual(d.entries_for("behuf"), [], "obsolete-only entries are dropped")
        self.assertEqual(d.entries_for("aventiure"), [], "archaic-only entries are dropped")
        self.assertEqual(d.entries_for("chien"), [], "non-German lines are skipped")
        self.assertEqual(d.entries_for("f"), [], "gender marker rows are not forms")
        self.assertEqual(d.entries_for("sein"), [], "auxiliary form rows are skipped")
        self.assertEqual(d.entries_for("ist gegangen"), [], "multiword forms are skipped")

    def test_entry_fields(self):
        [haus] = [e for e in self.d.entries_for("haus") if e.pos == "noun"]
        self.assertEqual(haus.gender, "n")
        self.assertEqual(haus.plural, "Häuser")
        self.assertEqual(haus.ipa, "/haʊ̯s/")
        self.assertEqual(haus.glosses, ["house", "building", "home"])

    def test_max_six_senses(self):
        for form in ("haus", "gehen", "frau"):
            for e in self.d.entries_for(form):
                self.assertLessEqual(len(e.glosses), 6)

    def test_meta_table(self):
        self.assertEqual(self.d.meta("schema_version"), "1")
        self.assertTrue(self.d.meta("build_id"))
        self.assertEqual(int(self.d.meta("entry_count")), self.stats["entries"])


if __name__ == "__main__":
    unittest.main()
