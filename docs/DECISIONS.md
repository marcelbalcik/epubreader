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

## 2026-09-13 — App (milestones 2–6)

* **Pagination geometry (§6).** The spec says `column-width: 100vw` with
  `column-gap: 2 × margin`. Implemented as
  `column-width: calc(100vw - 2*margin)` with the same gap, so the *period* of
  one page is exactly 100vw (width + gap) and the margins are actually visible;
  with a literal 100vw column the page period would be 100vw + 2×margin and the
  scroller could not be driven in whole viewport widths.
* **Scroll snapping (§6).** `scroll-snap-align` cannot be put on a column —
  columns are not elements. reader.js builds `#lesen-snap`, an overlay of one
  100vw-wide `pointer-events: none` element per page, outside the multi-column
  flow. Swipes remain the browser's own scroller, which is what makes them feel
  native.
* **A word under the finger beats the page-turn strip (§6/§7).** The outer 12%
  strips are much wider than `--margin` (12% of a 1080px screen is ~130px), so
  a literal reading would eat taps on the first and last word of every line —
  exactly what milestone 4's check forbids ("zero taps trigger a page turn by
  accident"). A tap inside a strip therefore looks the word up if it passes the
  same rect test the spec requires, and turns the page otherwise. Tapping real
  margin still turns the page, because no word passes the test there.
* **Swiping past a chapter edge.** With the scroller at its first or last page
  a swipe has nowhere to go, so reader.js reports that gesture as
  `onTapEmpty("left"/"right")` — the same action as a margin tap. Kotlin then
  crosses into the adjacent spine item, forward at page 0 and backward at the
  last page.
* **Two extra `Book` columns (§9).** `opfPath`, so the OPF can be re-parsed on
  open without rescanning the archive, and `spineChars`, the per-chapter
  character counts the whole-book percentage is computed from (§6 asks for them
  to be counted once at import and cached). Columns rather than a fifth table.
* **`VocabItem` carries `grammar` and no foreign key.** `grammar` holds the
  article and plural for the Anki card's second field (§8.6). No FK to `book`
  on purpose: deleting a book keeps its saved words, which is what the delete
  dialog promises.
* **`AnkiExport` works on a `VocabCard` interface** that the Room entity
  implements, so the escaping is unit-testable without Room on the classpath.
  Fields carry `<br>` for newlines and spaces for tabs: Anki treats a bare
  newline as a row break and would import shifted columns without complaining.
* **Multiword forms and proper nouns are unreachable by a tap.** Consequence of
  §4.1's POS list (no `name`) and of single-word lookup; noted in
  `tools/dictbuild/README.md` too. Tapping *Berlin* yields nothing.
* **No navigation library.** Three screens, so screen state is a sealed
  interface in `MainActivity` (§2: MVVM-lite, no ceremony).
* **Settings live in SharedPreferences, not DataStore.** Seven scalars, read
  synchronously at construction so the first chapter is laid out with the right
  font size instead of being re-paginated a frame later.
* **Volume keys are relayed from the activity.** They never reach Compose
  focus, so `MainActivity` catches them and the reader registers a handler
  while it is on screen; the matching key-up is swallowed so the system volume
  UI stays away.
* **Unverified dependency versions.** Google's Maven was blocked in the
  authoring container, so the AGP / Compose BOM / AndroidX / Room / webkit pins
  in `gradle/libs.versions.toml` could not be resolved and are marked as such
  in that file. Kotlin 2.4.20 and KSP 2.3.12 were verified against Maven
  Central. §2 asks for the versions actually used to be written down: that has
  to happen on the first local build.
* **Milestones 2–6 could not be run.** The authoring container has no Android
  SDK (`dl.google.com` is blocked) and no device, so the acceptance checks for
  rendering, pagination, tap-to-lookup and export are unverified. What was
  verified offline instead: 67 JVM unit tests (dictionary core, HTML injection,
  EPUB paths, settings payload, Anki TSV), 27 Python tests (build pipeline,
  normalisation, lookup) and 42 node checks over reader.js's sentence splitter
  and token cleanup.
