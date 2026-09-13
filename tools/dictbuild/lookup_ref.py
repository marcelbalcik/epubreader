#!/usr/bin/env python3
"""Reference implementation of the Lesen lookup algorithm (spec 4.4).

This is the desktop twin of app/src/main/java/de/lesen/reader/dict/DictLookup.kt.
It exists so the algorithm can be exercised against a freshly built dict.db in a
REPL (milestone 1's acceptance check) without an emulator. Keep the two in sync:
the step order, the suffix list, the POS plausibility table and the compound
rules are all specified, not incidental.

  python3 -i lookup_ref.py work/dict.db
  >>> d.lookup("Häuser")
"""

import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lesen_norm import norm  # noqa: E402

EXACT, VARIANT, HEURISTIC, COMPOUND, FUZZY, NONE = (
    "EXACT", "VARIANT", "HEURISTIC", "COMPOUND", "FUZZY", "NONE")

# Spec 4.4.5, in this order. Only accept a hit whose POS is plausible for the
# suffix that was removed -- otherwise "Hauses" minus -s matches a verb.
SUFFIX_POS = (
    ("en", {"verb", "noun", "adj"}),
    ("e", {"noun", "adj", "verb"}),
    ("er", {"noun", "adj"}),
    ("es", {"noun", "adj", "det", "pron"}),
    ("em", {"adj", "det", "pron"}),
    ("n", {"noun", "verb", "adj"}),
    ("s", {"noun"}),
    ("et", {"verb"}),
    ("st", {"verb", "adj"}),
    ("te", {"verb", "adj"}),
    ("ten", {"verb", "adj"}),
    ("tet", {"verb"}),
    ("ung", {"noun", "verb"}),
)

SEPARABLE_PREFIXES = (
    "ab", "an", "auf", "aus", "bei", "durch", "ein", "fest", "her", "hin",
    "los", "mit", "nach", "über", "um", "vor", "weg", "weiter", "zu",
    "zurück", "zusammen",
)

FUGEN = ("es", "en", "er", "s", "n", "e")   # longest first at the seam

SUFFIX_ROUNDS = 2      # iterated suffix stripping; see docs/DECISIONS.md
MIN_HEAD = 4
MIN_MODIFIER = 3
MAX_COMPOUND_DEPTH = 2
PREFIX_LIMIT = 15


class Entry:
    __slots__ = ("id", "lemma", "pos", "gender", "plural", "ipa", "glosses", "is_lemma")

    def __init__(self, row):
        (self.id, self.lemma, self.pos, self.gender, self.plural, self.ipa,
         glosses, self.is_lemma) = row
        self.glosses = json.loads(glosses)

    def __repr__(self):
        art = {"m": "der ", "f": "die ", "n": "das "}.get(self.gender or "", "")
        head = f"{art}{self.lemma}"
        if self.pos == "noun" and self.plural:
            head += f", pl. {self.plural}"
        return f"<{head} [{self.pos}] {'; '.join(self.glosses[:3])}>"


class Result:
    __slots__ = ("surface", "matched", "entries", "path", "parts", "suggestions")

    def __init__(self, surface, matched=None, entries=(), path=NONE, parts=(), suggestions=()):
        self.surface = surface
        self.matched = matched
        self.entries = list(entries)
        self.path = path
        self.parts = list(parts)
        self.suggestions = list(suggestions)

    def __bool__(self):
        return bool(self.entries)

    def __repr__(self):
        if self.parts:
            deco = " = " + "|".join(self.parts)
        else:
            deco = ""
        if not self.entries:
            sug = (", did you mean: " + ", ".join(self.suggestions[:5])) if self.suggestions else ""
            return f"Result({self.surface!r} -> nothing [{self.path}]{sug})"
        return (f"Result({self.surface!r} -> {self.matched!r} [{self.path}]{deco}\n  " +
                "\n  ".join(repr(e) for e in self.entries) + ")")


def variants(s):
    """Spec 4.4.2: ss/ss and umlaut/digraph variants, both directions."""
    out = []
    seen = {s}

    def add(x):
        if x and x not in seen:
            seen.add(x)
            out.append(x)

    add(s.replace("ß", "ss"))
    add(s.replace("ss", "ß"))
    folded = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
    add(folded)
    add(folded.replace("ß", "ss"))
    unfolded = s.replace("ae", "ä").replace("oe", "ö").replace("ue", "ü")
    add(unfolded)
    add(unfolded.replace("ss", "ß"))
    add(unfolded.replace("ß", "ss"))
    return out


class Dict:
    def __init__(self, path):
        self.con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.con.execute("PRAGMA query_only=1")

    # -- raw access ---------------------------------------------------------
    def entries_for(self, form_norm):
        rows = self.con.execute(
            "SELECT e.id, e.lemma, e.pos, e.gender, e.plural, e.ipa, e.glosses, f.is_lemma "
            "FROM form f JOIN entry e ON e.id = f.entry_id "
            "WHERE f.form_norm = ? ORDER BY f.is_lemma DESC, e.id", (form_norm,)).fetchall()
        return [Entry(r) for r in rows]

    def prefix(self, form_norm, limit=PREFIX_LIMIT):
        rows = self.con.execute(
            "SELECT DISTINCT f.form_norm FROM form f WHERE f.form_norm LIKE ? || '%' "
            "ORDER BY LENGTH(f.form_norm), f.form_norm LIMIT ?", (form_norm, limit)).fetchall()
        return [r[0] for r in rows]

    def meta(self, key):
        row = self.con.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    # -- steps 1..5 --------------------------------------------------------
    def _exact(self, n):
        return self.entries_for(n)

    def _variant(self, n):
        for cand in variants(n):
            hits = self.entries_for(cand)
            if hits:
                return cand, hits
        return None, []

    def _dehyphen(self, n):
        if "-" not in n:
            return None, []
        joined = n.replace("-", "")
        for cand in (joined, n.rsplit("-", 1)[-1]):
            if not cand or cand == n:
                continue
            hits = self.entries_for(cand)
            if hits:
                return cand, hits
        return None, []

    def _ge_infix(self, n):
        for p in SEPARABLE_PREFIXES:
            if n.startswith(p + "ge") and len(n) > len(p) + 2:
                cand = p + n[len(p) + 2:]
                hits = self.entries_for(cand)
                if hits:
                    return cand, hits
        return None, []

    def _suffix(self, n, rounds=SUFFIX_ROUNDS):
        """Spec 4.4.5, with iterated stripping (see docs/DECISIONS.md 2026-09-13):
        one pass cannot reach "schönste" -> "schön", which needs -e then -st.

        The POS plausibility of every suffix removed along the way is conjoined,
        so iteration cannot launder an implausible step: "gehens" may not reach
        the verb "gehen" by stripping -es (nouns only) and then -n."""
        current = [(n, None)]           # (stem, allowed POS so far; None = any)
        for _ in range(rounds):
            nxt = []
            for s, sofar in current:
                for suf, allowed in SUFFIX_POS:
                    if len(s) - len(suf) < 3 or not s.endswith(suf):
                        continue
                    stem = s[: -len(suf)]
                    narrowed = allowed if sofar is None else (sofar & allowed)
                    if not narrowed:
                        continue
                    hits = [e for e in self.entries_for(stem) if e.pos in narrowed]
                    if hits:
                        return stem, hits
                    nxt.append((stem, narrowed))
            current = nxt
            if not current:
                break
        return None, []

    def resolve_simple(self, n, suffix_rounds=SUFFIX_ROUNDS):
        """Steps 1-5. Returns (path, matched_form, entries)."""
        hits = self._exact(n)
        if hits:
            return EXACT, n, hits
        m, hits = self._variant(n)
        if hits:
            return VARIANT, m, hits
        m, hits = self._dehyphen(n)
        if hits:
            return VARIANT, m, hits
        m, hits = self._ge_infix(n)
        if hits:
            return HEURISTIC, m, hits
        m, hits = self._suffix(n, rounds=suffix_rounds)
        if hits:
            return HEURISTIC, m, hits
        return NONE, None, []

    # -- step 6: compounds -------------------------------------------------
    def _split(self, n, depth, suffix_rounds):
        """Right-to-left longest-head match. Returns (parts, head_entries) or None."""
        best = None
        for i in range(len(n) - MIN_HEAD, MIN_MODIFIER - 1, -1):
            left, head = n[:i], n[i:]
            hpath, hmatch, hhits = self.resolve_simple(head, suffix_rounds)
            if not hhits:
                continue
            mod_parts = self._resolve_modifier(left, depth, suffix_rounds)
            if mod_parts is None:
                continue
            parts = mod_parts + [hmatch or head]
            cand = (len(head), -len(parts), parts, hhits)
            if best is None or cand[:2] > best[:2]:
                best = cand
        if best is None:
            return None
        return best[2], best[3]

    def _resolve_modifier(self, left, depth, suffix_rounds):
        """Resolve the left-hand part, allowing a Fugenelement at the seam and at
        most MAX_COMPOUND_DEPTH levels of further splitting."""
        if len(left) < MIN_MODIFIER:
            return None
        cands = [left]
        for fug in FUGEN:
            if left.endswith(fug) and len(left) - len(fug) >= MIN_MODIFIER:
                cands.append(left[: -len(fug)])
        for cand in cands:
            path, matched, hits = self.resolve_simple(cand, suffix_rounds)
            if hits:
                return [matched or cand]
        if depth < MAX_COMPOUND_DEPTH:
            for cand in cands:
                deeper = self._split(cand, depth + 1, suffix_rounds)
                if deeper:
                    return deeper[0]
        return None

    # -- public API --------------------------------------------------------
    def lookup(self, surface, suffix_rounds=SUFFIX_ROUNDS):
        n = norm(surface)
        if not n:
            return Result(surface)
        path, matched, hits = self.resolve_simple(n, suffix_rounds)
        if hits:
            return Result(surface, matched, hits, path)

        split = self._split(n, 0, suffix_rounds)
        if split:
            parts, hits = split
            return Result(surface, parts[-1], hits, COMPOUND, parts)

        sugg = self.prefix(n)
        return Result(surface, None, (), FUZZY if sugg else NONE, (), sugg)


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "work", "dict.db")
    d = Dict(path)
    print(f"opened {path}: {d.meta('entry_count')} entries, {d.meta('form_count')} forms")
    for w in sys.argv[2:]:
        print(d.lookup(w))
