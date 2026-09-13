# Lesen

An offline German EPUB reader for Android. Tap a German word, a bottom sheet
shows its lemma and English meaning — no network, no account, no telemetry.
Single user, sideloaded APK.

**The app holds no `INTERNET` permission.** The manifest removes it explicitly
(`tools:node="remove"`), so a dependency cannot merge it back in, and the
WebView additionally runs with `blockNetworkLoads = true`. That is the
correctness guarantee for "offline", not a preference.

## Layout

```
app/                        the Android app (one Gradle module)
  src/main/assets/
    dict.db.gz              the dictionary (build output, gitignored)
    dict.db.sha256          its checksum, verified on first launch
    reader/reader.css       injected pagination + theming stylesheet
    reader/reader.js        injected tap, pagination and position script
    fonts/                  Literata and Inter (OFL, bundled)
  src/main/java/de/lesen/reader/
    dict/                   normalisation + the 7-step lookup (no Android deps)
    dict/android/           install from assets, read-only SQLite, repository
    epub/                   zip extraction, OPF/NCX/nav parsing, import
    reader/                 WebView, asset serving, bridge, reader ViewModel
    data/                   Room entities, DAOs, settings
    library/ vocab/ ui/     the remaining screens and theming
tools/dictbuild/            Python 3 pipeline that builds dict.db
tools/readerjs/             offline checks for reader.js text handling
docs/DECISIONS.md           every deviation from the spec, dated
```

## Building

1. **Build the dictionary** (once, on the desktop). It is not in the repo: the
   source dump is 1–2 GB and the artifact is derived from it.

   ```bash
   # fetch kaikki.org-dictionary-German.jsonl first - see tools/dictbuild/README.md
   python3 tools/dictbuild/build_dict.py --input path/to/kaikki.org-dictionary-German.jsonl
   ```

   This writes `app/src/main/assets/dict.db.gz` and `dict.db.sha256`. Without
   them the app still builds and runs, and the library shows a message saying
   the dictionary is missing and how to build it.

2. **Build the APK.**

   ```bash
   ./gradlew :app:assembleRelease      # signed with the debug key: sideloaded by hand
   ./gradlew :app:installDebug
   ```

> **Version pins.** `gradle/libs.versions.toml` was written in a container
> where Google's Maven (`dl.google.com` / `maven.google.com`) is blocked by
> policy, so AGP, the Compose BOM, AndroidX, Room and the webkit library could
> not be resolved to check them. Kotlin (2.4.20) and KSP (2.3.12) were verified
> against Maven Central. If a pin fails to resolve on your first build, bump it
> to the current stable release — the file marks exactly which lines those are —
> and record what you landed on in `docs/DECISIONS.md`, which spec §2 asks for.

## Tests

```bash
python3 -m unittest discover -s tools/dictbuild   # normalisation + build + lookup
node tools/readerjs/test_reader.js               # reader.js sentence/token handling
./gradlew :app:testDebugUnitTest                 # the Kotlin half of all of it
```

The dictionary core (`dict/`), the HTML injection, the EPUB path arithmetic,
the settings payload and the Anki exporter carry no Android dependencies on
purpose, so all of them run as plain JVM unit tests.

Two things are checked in two languages against one fixture, because a drift
between them would be silent rather than loud:

* **Normalisation** (`dict/Normalizer.kt` and `tools/dictbuild/lesen_norm.py`)
  — both read `tools/dictbuild/fixtures/norm_cases.tsv`, 45 cases.
* **The lookup algorithm** (`dict/DictLookup.kt` and
  `tools/dictbuild/lookup_ref.py`) — both assert milestone 1's six acceptance
  words.

## How a tap becomes a definition

1. `reader.js` catches a `click` (not `touchstart`, so the scroller claims
   swipes first), finds the caret with `caretRangeFromPoint`, expands over the
   German word class inside one text node, and verifies the tap landed on the
   word's own rect rather than beside it.
2. It posts `onWordTapped(word, sentence, blockIndex, charOffset)` over the
   `@JavascriptInterface` bridge. The sheet opens immediately, with a spinner.
3. `DictLookup` runs the seven steps of spec §4.4 off the main thread: exact,
   spelling variants, de-hyphenation, participle `ge`-infix, suffix stripping,
   right-to-left compound splitting, prefix search. The path that produced the
   answer is shown in the sheet, so a guess always looks like a guess.
4. "Merken" saves lemma, glosses, grammar and the sentence; the vocab screen
   exports an Anki-importable TSV through `ACTION_CREATE_DOCUMENT`.

## Not included, on purpose

Sentence or paragraph machine translation, TTS, PDF/MOBI, cloud sync,
highlights and annotations, statistics, SRS scheduling, accounts, analytics,
crash reporting. The selection sheet says so out loud rather than pretending
when more than eight words are selected.
