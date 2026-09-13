# Decisions

Deviations from the build spec, and choices the spec left open. Newest last.

## 2026-09-13 — Dictionary build (milestone 1)

* **Iterated suffix stripping (§4.4.5).** The spec's single pass cannot resolve
  `schönste → schön`, which needs `-e` and then `-st`; milestone 1's acceptance
  check requires it. Step 5 now iterates at most twice. Approved by the owner
  before the change. Guard added at the same time: the POS plausibility sets of
  every suffix removed along the path are **conjoined**, so iteration cannot
  launder an implausible step — `gehens` may not reach the verb `gehen` by
  stripping `-es` (nouns/adjectives only) and then `-n`. Without that guard the
  spec's "don't strip `-s` and match a verb" rule was violated transitively.
  Still reported as `HEURISTIC` in the UI.
* **`UNIQUE (form_norm, entry_id)` added to `form` (§4.2).** The spec requires
  deduplicated `(form_norm, entry_id)` pairs; a unique constraint plus
  `INSERT OR IGNORE` is how that is enforced, and it is needed because pass 2
  (inflection folding) can re-propose a pair pass 1 already inserted. The
  specified `idx_form_norm` index is unchanged.
* **Dictionary checksum ships beside the db, not inside it (§12).** A file
  cannot contain its own hash. `dict.db.sha256` (hash of the *uncompressed*
  `dict.db`, plus entry/form counts and `build_id`) ships as a sibling asset and
  the app verifies it after gunzip; `meta` carries `build_id`, counts and
  `schema_version`, which the app cross-checks against that file.
* **Entries sharing `(lemma, pos)` are merged.** The spec caps senses per
  `(lemma, pos)`, but the dump has one object per etymology. Merging keeps the
  cap meaningful and reduces ambiguity noise: glosses are concatenated up to 6,
  gender is unioned (`m` + `f` → `mf`), first IPA and first plural win.
* **Inflection-only / alternative-spelling entries are folded into `form`
  rows.** `senses[].form_of` / `alt_of` entries (the standalone `Häuser`,
  `daß`, …) would otherwise become entries whose only gloss is "nominative
  plural of Haus". They now add a form row against the target lemma instead.
  Side effect on the §10.1 check: `daß → dass` resolves as `EXACT` rather than
  `VARIANT` (the ß/ss variant step still covers it when the dump lacks the
  obsolete-spelling entry, e.g. `Strasse → Straße`).
* **Multiword headwords and multiword form rows are skipped.** Tap-to-lookup is
  single-word, so `auf jeden Fall` and `ist gegangen` cannot be reached.
* **`dict.db.gz` is a gitignored build output.** The 1–2 GB source dump cannot
  be committed and kaikki.org is unreachable from the build container, so the
  artifact is produced on the desktop by `tools/dictbuild/build_dict.py`
  (owner's call).
