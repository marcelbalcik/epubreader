# `dict.db` build pipeline

Produces the read-only dictionary the app ships in `assets/`. Runs on a desktop.
Nothing here runs on the device and nothing here downloads anything.

## 1. Fetch the source (manual, once)

Get the **Wiktextract extraction of English Wiktionary's German entries** from
kaikki.org:

* Page: <https://kaikki.org/dictionary/German/>
* File: **`kaikki.org-dictionary-German.jsonl`** — the "all senses / all entries"
  JSONL export (one JSON object per entry). If you grab the `.json.gz`, gunzip it
  first. Expect roughly 1–2 GB uncompressed.

Put it anywhere you like, e.g.:

```
tools/dictbuild/work/kaikki.org-dictionary-German.jsonl
```

`work/` is gitignored. If the file is not where `--input` points, the script
exits with this instruction instead of guessing or downloading.

## 2. Build

```bash
python3 tools/dictbuild/build_dict.py \
    --input tools/dictbuild/work/kaikki.org-dictionary-German.jsonl
```

Options: `--out-db` (default `tools/dictbuild/work/dict.db`), `--out-gz`
(default `app/src/main/assets/dict.db.gz`), `--limit N` (stop after N input
lines, for a quick smoke run), `--no-gzip` (build the db only).

Installing `orjson` (`pip install orjson`) makes the parse roughly 3× faster; it
is optional and imported defensively.

Outputs:

| path | what |
|---|---|
| `tools/dictbuild/work/dict.db` | the SQLite file, VACUUMed + ANALYZEd |
| `app/src/main/assets/dict.db.gz` | what the APK ships |
| `app/src/main/assets/dict.db.sha256` | `sha256  entries  forms  build_id` of the **uncompressed** db |

Both assets are gitignored: they are build outputs of a source file that is too
big to commit. The app refuses to start the reader with a clear message if
`dict.db.gz` is missing from `assets/`.

The build prints a report: entry count, form count, forms-per-entry, db size,
and the 20 most ambiguous forms. Watch that last list — a single form matching
hundreds of entries means a filter regressed (the first version of this script
indexed the gender-marker rows from the form tables, so `f` matched every
feminine noun).

## 3. Check it

```bash
python3 -m unittest discover -s tools/dictbuild        # normalisation + lookup
python3 -i tools/dictbuild/lookup_ref.py tools/dictbuild/work/dict.db
>>> d.lookup("Kinderbuchautorin")
>>> d.lookup("Häuser")
```

`lookup_ref.py` is a faithful desktop twin of `DictLookup.kt`; it exists so the
algorithm can be exercised against a real `dict.db` without an emulator. The
milestone‑1 acceptance words are asserted in `test_lookup.py`, which builds its
own database from `fixtures/sample_de.jsonl` (a small, hand-written imitation of
the real dump) and needs neither the dump nor the network.

## What the build keeps and drops

Kept POS: `noun verb adj adv pron prep conj num intj det article particle`.
Everything else — including `name` (proper nouns), `prefix`, `suffix`, `phrase`,
`abbrev`, `character` — is dropped, per spec §4.1. Consequence worth knowing:
tapping *Berlin* or *Goethe* yields nothing.

Also dropped: etymology, pronunciation audio, translations, examples,
categories, Wikipedia links; senses tagged `obsolete`/`archaic` (and entries
left with no other sense); multiword headwords and multiword form rows
(`ist gegangen`); form rows tagged `auxiliary`, `table-tags`,
`inflection-template`, `class`, `romanization`; gender-marker rows.

Kept: up to 6 glosses per `(lemma, pos)` in source order, IPA (first one),
gender (`m`/`f`/`n` and combinations) and nominative plural for nouns.

Two build behaviours worth knowing, both recorded in `docs/DECISIONS.md`:

* Entries sharing a `(lemma, pos)` — different etymologies of the same word —
  are **merged** into one entry, since the sense cap is specified per
  `(lemma, pos)`.
* Inflection-only and alternative-spelling entries (`senses[].form_of` /
  `alt_of`, e.g. the separate `Häuser` and `daß` entries) do **not** become
  entries. They are folded into `form` rows pointing at the target lemma, so a
  tap on *Häuser* shows *house* rather than *nominative plural of Haus*.

## Keeping the two normalisers identical

`lesen_norm.py` and `app/src/main/java/de/lesen/reader/dict/Normalizer.kt`
must agree on every input. Both are tested against
`fixtures/norm_cases.tsv`; Gradle copies that exact file into the Kotlin unit
test's resources (see `app/build.gradle.kts`, task `copyNormFixture`), so there
is one fixture, not two copies that drift.
