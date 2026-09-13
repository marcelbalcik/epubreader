#!/usr/bin/env python3
"""Build Lesen's read-only dictionary (dict.db) from a Wiktextract JSONL dump.

Run this on a desktop, never on the device. It does not download anything: pass
--input the path to the kaikki.org German extract (see README.md).

Output: a SQLite file (schema in spec 4.2), VACUUMed and ANALYZEd, gzipped into
app/src/main/assets/dict.db.gz, plus a sibling dict.db.sha256 holding the
checksum of the *uncompressed* database for the on-device install check.
"""

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lesen_norm import norm  # noqa: E402

try:  # optional, ~3x faster on a 1 GB dump
    import orjson

    def loads(line):
        return orjson.loads(line)
except ImportError:  # pragma: no cover
    def loads(line):
        return json.loads(line)


SCHEMA_VERSION = "1"
NORMALIZER_VERSION = "1"

# Spec 4.1: parts of speech worth keeping. Everything else (name, prefix,
# suffix, phrase, proverb, abbrev, character, punct, ...) is dropped.
KEEP_POS = {
    "noun", "verb", "adj", "adv", "pron", "prep", "conj",
    "num", "intj", "det", "article", "particle",
}

# Senses carrying only these tags are not worth a dictionary entry (spec 4.1).
DEAD_SENSE_TAGS = {"obsolete", "archaic"}

# Form-table rows that are not surface forms of the word.
BAD_FORM_TAGS = {
    "table-tags", "inflection-template", "class", "auxiliary", "romanization",
    "error-unrecognized-form", "multiword-construction", "obsolete", "archaic",
}
BAD_FORM_TEXT = {"", "-", "--", "—", "–", "?", "none", "no plural", "unknown", "__unknown__"}

GENDER_TAGS = {"masculine": "m", "feminine": "f", "neuter": "n"}
GENDER_ORDER = "mfn"

MAX_SENSES = 6           # spec 4.1
MAX_FORM_LEN = 40
MAX_GLOSS_LEN = 400

_EXPANSION_GENDER = re.compile(r"\s(m|f|n)(?:\s+or\s+(m|f|n))?(?:\s+or\s+(m|f|n))?\b")
_EXPANSION_PLURAL = re.compile(r"plural\s+([^\s,;)]+)")

DDL = """
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;

CREATE TABLE entry (
  id       INTEGER PRIMARY KEY,
  lemma    TEXT NOT NULL,
  pos      TEXT NOT NULL,
  gender   TEXT,
  plural   TEXT,
  ipa      TEXT,
  glosses  TEXT NOT NULL
);

CREATE TABLE form (
  form_norm TEXT NOT NULL,
  entry_id  INTEGER NOT NULL REFERENCES entry(id),
  is_lemma  INTEGER NOT NULL,
  UNIQUE (form_norm, entry_id)
);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
"""

POST_DDL = """
CREATE INDEX idx_form_norm ON form(form_norm);
CREATE INDEX idx_entry_lemma ON entry(lemma);
"""


class Entry:
    """One merged (lemma, pos) headword."""

    __slots__ = ("id", "lemma", "pos", "gender", "plural", "ipa", "glosses", "forms")

    def __init__(self, eid, lemma, pos):
        self.id = eid
        self.lemma = lemma
        self.pos = pos
        self.gender = None
        self.plural = None
        self.ipa = None
        self.glosses = []
        self.forms = set()

    def merge_gender(self, g):
        if not g:
            return
        merged = set(self.gender or "") | set(g)
        self.gender = "".join(c for c in GENDER_ORDER if c in merged)


def sense_is_dead(sense):
    tags = set(sense.get("tags") or ())
    if not tags:
        return False
    return tags.issubset(DEAD_SENSE_TAGS) or bool(tags & DEAD_SENSE_TAGS)


def collect_glosses(obj):
    """Return (live_glosses, formof_targets). A form-of entry has no live gloss
    of its own -- it only points at a lemma."""
    live, targets = [], []
    for sense in obj.get("senses") or ():
        glosses = [g.strip() for g in (sense.get("glosses") or ()) if g and g.strip()]
        pointer = []
        for key in ("form_of", "alt_of"):
            for item in sense.get(key) or ():
                w = (item or {}).get("word")
                if w and " " not in w:
                    pointer.append(w)
        if pointer:
            targets.extend(pointer)
            continue
        if not glosses or sense_is_dead(sense):
            continue
        live.extend(g[:MAX_GLOSS_LEN] for g in glosses)
    return live, targets


def gender_of(obj, word):
    found = set()
    for f in obj.get("forms") or ():
        tags = set(f.get("tags") or ())
        hit = tags & set(GENDER_TAGS)
        if not hit:
            continue
        text = (f.get("form") or "").strip()
        # Gender is carried either on a marker row ("n") or on the canonical
        # headword row; a gender tag on an inflected form says nothing.
        if text in ("", "m", "f", "n") or text == word or "canonical" in tags:
            found |= {GENDER_TAGS[t] for t in hit}
    for t in obj.get("head_templates") or ():
        args = (t.get("args") or {})
        a1 = str(args.get("1") or "").strip()
        if a1 in ("m", "f", "n"):
            found.add(a1)
        elif a1 in ("mf", "mn", "fn", "mfn"):
            found |= set(a1)
        expansion = t.get("expansion") or ""
        if expansion.startswith(word):
            m = _EXPANSION_GENDER.match(expansion[len(word):])
            if m:
                found |= {g for g in m.groups() if g}
    if not found:
        for sense in obj.get("senses") or ():
            found |= {GENDER_TAGS[t] for t in set(sense.get("tags") or ()) & set(GENDER_TAGS)}
    return "".join(c for c in GENDER_ORDER if c in found) or None


def plural_of(obj, word):
    best = None
    for f in obj.get("forms") or ():
        text = (f.get("form") or "").strip()
        if not usable_form(text, f):
            continue
        tags = set(f.get("tags") or ())
        if "plural" not in tags or text == word:
            continue
        if tags & {"genitive", "dative", "accusative"}:
            continue
        if "nominative" in tags:
            return text
        if best is None:
            best = text
    if best:
        return best
    for t in obj.get("head_templates") or ():
        m = _EXPANSION_PLURAL.search(t.get("expansion") or "")
        if m:
            cand = m.group(1).strip()
            if usable_form(cand, {}) and cand != word:
                return cand
    return None


def ipa_of(obj):
    for s in obj.get("sounds") or ():
        ipa = (s.get("ipa") or "").strip()
        if ipa:
            return ipa
    return None


def usable_form(text, f):
    if not text or text.lower() in BAD_FORM_TEXT:
        return False
    tags = set(f.get("tags") or ())
    # A gender marker row ("form": "n", tags: ["neuter"]) is metadata, not a
    # surface form. Indexing those makes "f" match every feminine noun.
    if text in ("m", "f", "n", "mf", "mn", "fn", "mfn") and (tags & set(GENDER_TAGS)):
        return False
    if len(text) < 2:
        return False
    if len(text) > MAX_FORM_LEN:
        return False
    if any(ch.isspace() for ch in text):
        return False          # multiword ("ist gegangen") is useless for a tap
    if text[0] in "(*[":
        return False
    if tags & BAD_FORM_TAGS:
        return False
    return True


def build(input_path, db_path, limit=None, verbose=True):
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    con.executescript(DDL)

    entries = {}                         # (lemma_norm, pos) -> Entry
    by_lemma = defaultdict(list)         # lemma_norm -> [Entry]
    pending = []                         # (form_norm, target_norm, target_pos)
    next_id = 1
    lines = kept = skipped_pos = skipped_nogloss = 0
    t0 = time.time()

    with open(input_path, "r", encoding="utf-8") as fh:
        for line in fh:
            lines += 1
            if limit and lines > limit:
                break
            if verbose and lines % 100000 == 0:
                print(f"  ... {lines:,} lines, {len(entries):,} entries "
                      f"({time.time() - t0:.0f}s)", file=sys.stderr)
            line = line.strip()
            if not line or line[0] != "{":
                continue
            try:
                obj = loads(line)
            except ValueError:
                continue
            if obj.get("lang_code") not in (None, "de"):
                continue
            word = (obj.get("word") or "").strip()
            pos = (obj.get("pos") or "").strip()
            if not word or any(ch.isspace() for ch in word):
                continue
            if pos not in KEEP_POS:
                skipped_pos += 1
                continue
            lemma_norm = norm(word)
            if not lemma_norm:
                continue

            live, targets = collect_glosses(obj)
            if not live:
                # Pure inflection / alternative-spelling entry: resolve it to a
                # form row on the target lemma in pass 2 instead of keeping a
                # "genitive singular of Haus" entry the reader would show.
                skipped_nogloss += 1
                for target in targets:
                    pending.append((lemma_norm, norm(target), pos))
                continue

            key = (lemma_norm, pos)
            e = entries.get(key)
            if e is None:
                e = Entry(next_id, word, pos)
                next_id += 1
                entries[key] = e
                by_lemma[lemma_norm].append(e)
            kept += 1
            for g in live:
                if len(e.glosses) >= MAX_SENSES:
                    break
                if g not in e.glosses:
                    e.glosses.append(g)
            e.merge_gender(gender_of(obj, word))
            e.plural = e.plural or plural_of(obj, word)
            e.ipa = e.ipa or ipa_of(obj)
            e.forms.add(lemma_norm)
            for f in obj.get("forms") or ():
                text = (f.get("form") or "").strip()
                if usable_form(text, f):
                    fn = norm(text)
                    if fn:
                        e.forms.add(fn)

    if verbose:
        print(f"  read {lines:,} lines in {time.time() - t0:.0f}s", file=sys.stderr)

    cur = con.cursor()
    cur.executemany(
        "INSERT INTO entry (id, lemma, pos, gender, plural, ipa, glosses) VALUES (?,?,?,?,?,?,?)",
        ((e.id, e.lemma, e.pos, e.gender, e.plural, e.ipa,
          json.dumps(e.glosses, ensure_ascii=False)) for e in entries.values()),
    )
    cur.executemany(
        "INSERT OR IGNORE INTO form (form_norm, entry_id, is_lemma) VALUES (?,?,?)",
        ((fn, e.id, 1 if fn == norm(e.lemma) else 0)
         for e in entries.values() for fn in e.forms),
    )

    # Pass 2: inflection/alt-spelling entries become form rows on their target.
    resolved = orphaned = 0
    rows = []
    for form_norm, target_norm, pos in pending:
        cands = [x for x in by_lemma.get(target_norm, ()) if x.pos == pos] \
            or by_lemma.get(target_norm, ())
        if not cands:
            orphaned += 1
            continue
        resolved += 1
        for e in cands:
            rows.append((form_norm, e.id, 0))
    cur.executemany(
        "INSERT OR IGNORE INTO form (form_norm, entry_id, is_lemma) VALUES (?,?,?)", rows)

    con.executescript(POST_DDL)

    entry_count = cur.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    form_count = cur.execute("SELECT COUNT(*) FROM form").fetchone()[0]
    build_id = time.strftime("%Y%m%d%H%M%S", time.gmtime())
    meta = {
        "source": "kaikki.org Wiktextract extraction of English Wiktionary (German)",
        "source_file": os.path.basename(input_path),
        "build_date": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "build_id": build_id,
        "schema_version": SCHEMA_VERSION,
        "normalizer_version": NORMALIZER_VERSION,
        "entry_count": str(entry_count),
        "form_count": str(form_count),
        "formof_resolved": str(resolved),
        "formof_orphaned": str(orphaned),
    }
    cur.executemany("INSERT OR REPLACE INTO meta (key, value) VALUES (?,?)", meta.items())
    con.commit()
    con.execute("VACUUM")
    con.execute("ANALYZE")
    con.commit()
    stats = {
        "lines": lines, "entries": entry_count, "forms": form_count,
        "kept_entry_objects": kept, "skipped_pos": skipped_pos,
        "formof_objects": skipped_nogloss, "formof_resolved": resolved,
        "formof_orphaned": orphaned, "build_id": build_id,
    }
    return con, stats


def report(con, db_path, stats, gz_path=None):
    cur = con.cursor()
    size = os.path.getsize(db_path)
    print()
    print("=== Lesen dictionary build report ===")
    print(f"  source lines read   : {stats['lines']:,}")
    print(f"  entries             : {stats['entries']:,}")
    print(f"  forms               : {stats['forms']:,}")
    print(f"  forms per entry     : {stats['forms'] / max(1, stats['entries']):.1f}")
    print(f"  inflection entries folded into forms: {stats['formof_resolved']:,} "
          f"(unresolved: {stats['formof_orphaned']:,})")
    print(f"  dropped by POS filter: {stats['skipped_pos']:,}")
    print(f"  dict.db             : {size / 1e6:.1f} MB")
    if gz_path and os.path.exists(gz_path):
        print(f"  dict.db.gz          : {os.path.getsize(gz_path) / 1e6:.1f} MB")
    print(f"  build_id            : {stats['build_id']}")
    print()
    print("  20 most ambiguous forms (form_norm -> number of entries):")
    for form_norm, c in cur.execute(
            "SELECT form_norm, COUNT(*) c FROM form GROUP BY form_norm "
            "ORDER BY c DESC, form_norm LIMIT 20"):
        print(f"    {c:4d}  {form_norm}")
    print()
    by_pos = cur.execute(
        "SELECT pos, COUNT(*) FROM entry GROUP BY pos ORDER BY 2 DESC").fetchall()
    print("  entries by POS: " + ", ".join(f"{p}={c:,}" for p, c in by_pos))
    print()


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", ".."))
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True,
                    help="path to the kaikki.org German Wiktextract .jsonl (see README.md)")
    ap.add_argument("--out-db", default=os.path.join(here, "work", "dict.db"))
    ap.add_argument("--out-gz", default=os.path.join(repo, "app", "src", "main", "assets", "dict.db.gz"))
    ap.add_argument("--limit", type=int, default=None, help="stop after N input lines (testing)")
    ap.add_argument("--no-gzip", action="store_true", help="build dict.db only, do not ship it")
    args = ap.parse_args(argv)

    if not os.path.exists(args.input):
        sys.exit(
            f"error: input file not found: {args.input}\n\n"
            "Download the German extract from kaikki.org first:\n"
            "  https://kaikki.org/dictionary/German/\n"
            "  file: kaikki.org-dictionary-German.jsonl (or the .json.gz, gunzipped)\n"
            "and pass it with --input. This script never downloads anything itself.\n"
            "See tools/dictbuild/README.md."
        )

    os.makedirs(os.path.dirname(os.path.abspath(args.out_db)), exist_ok=True)
    print(f"building {args.out_db} from {args.input}", file=sys.stderr)
    con, stats = build(args.input, args.out_db, limit=args.limit)

    gz_path = None
    if not args.no_gzip:
        gz_path = args.out_gz
        os.makedirs(os.path.dirname(os.path.abspath(gz_path)), exist_ok=True)
        sha = hashlib.sha256()
        with open(args.out_db, "rb") as src:
            for chunk in iter(lambda: src.read(1 << 20), b""):
                sha.update(chunk)
        digest = sha.hexdigest()
        with open(args.out_db, "rb") as src, gzip.open(gz_path, "wb", compresslevel=9) as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
        # The checksum of the uncompressed db cannot live inside the db it
        # describes, so it ships beside it and the app verifies after gunzip.
        with open(os.path.join(os.path.dirname(gz_path), "dict.db.sha256"), "w") as fh:
            fh.write(f"{digest}  {stats['entries']}  {stats['forms']}  {stats['build_id']}\n")
        print(f"  sha256(dict.db)     : {digest}", file=sys.stderr)

    report(con, args.out_db, stats, gz_path)
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
