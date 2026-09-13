#!/usr/bin/env python3
"""Python half of the normalisation parity test (spec 4.3).

The Kotlin half is app/src/test/java/de/lesen/reader/dict/NormalizerTest.kt and
reads the same fixture file. If these two ever disagree the dictionary silently
half-works, so the fixture is the contract, not either implementation.

  python3 -m unittest discover -s tools/dictbuild
"""

import codecs
import os
import unittest

from lesen_norm import norm

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "fixtures", "norm_cases.tsv")


def load_cases(path=FIXTURE):
    cases = []
    with open(path, encoding="utf-8") as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                raise AssertionError(f"{path}:{lineno}: expected exactly one tab")
            inp, exp = (codecs.decode(p, "unicode_escape") if "\\u" in p else p
                        for p in parts)
            cases.append((lineno, inp, exp))
    return cases


class NormTest(unittest.TestCase):
    def test_fixture(self):
        cases = load_cases()
        self.assertGreaterEqual(len(cases), 40, "fixture should cover ~40 tricky words")
        for lineno, inp, exp in cases:
            with self.subTest(line=lineno, input=inp):
                self.assertEqual(norm(inp), exp)

    def test_empty(self):
        self.assertEqual(norm(""), "")

    def test_idempotent(self):
        for _, inp, _ in load_cases():
            self.assertEqual(norm(norm(inp)), norm(inp))

    def test_sharp_s_and_umlauts_are_not_folded(self):
        # Folding belongs at query time (spec 4.4.2), not in the index.
        self.assertEqual(norm("Straße"), "straße")
        self.assertEqual(norm("Bücher"), "bücher")

    def test_case_folding_edges(self):
        self.assertEqual(norm("ẞ"), "ß")          # U+1E9E capital sharp s
        self.assertEqual(norm("İ"), "i")          # lowercases to i + U+0307


if __name__ == "__main__":
    unittest.main()
