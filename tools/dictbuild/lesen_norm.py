"""Surface-form normalisation for Lesen (spec section 4.3).

This module is the Python half of a two-implementation contract. The Kotlin half
lives in app/src/main/java/de/lesen/reader/dict/Normalizer.kt and MUST behave
identically for every input. Both are tested against the same fixture file,
tools/dictbuild/fixtures/norm_cases.tsv.

Steps, in order:
  1. Unicode NFC.
  2. Strip soft hyphens (U+00AD) and zero-width / invisible formatting chars.
  3. Normalise the apostrophe family to U+0027.
  4. Lowercase.
  5. NFC again, then strip combining marks.

Note on step 5: the spec lists "strip combining marks" with step 2. Lowercasing
can *introduce* a combining mark (U+0130 LATIN CAPITAL LETTER I WITH DOT ABOVE
lowercases to "i" + U+0307 in both Python and Java), so the mark strip runs after
lowercasing instead. It also runs before, because marks that NFC cannot compose
must not survive. Doing it on both sides of the lowercase keeps the two
implementations bit-identical for pathological input.

Deliberately NOT done here: no ss/ss folding, no umlaut -> ae/oe/ue folding. The
primary index stays exact; those are query-time fallback variants (spec 4.4.2).
"""

import unicodedata

# Zero width and invisible formatting characters that turn up inside Wiktionary
# headwords and inside EPUB text alike.
_INVISIBLE = (
    "­"  # SOFT HYPHEN
    "​"  # ZERO WIDTH SPACE
    "‌"  # ZERO WIDTH NON-JOINER
    "‍"  # ZERO WIDTH JOINER
    "‎"  # LEFT-TO-RIGHT MARK
    "‏"  # RIGHT-TO-LEFT MARK
    "⁠"  # WORD JOINER
    "﻿"  # ZERO WIDTH NO-BREAK SPACE / BOM
    "؜"  # ARABIC LETTER MARK
    "᠎"  # MONGOLIAN VOWEL SEPARATOR
)

# Apostrophe family -> U+0027.
_APOSTROPHES = (
    "’"  # RIGHT SINGLE QUOTATION MARK
    "‘"  # LEFT SINGLE QUOTATION MARK
    "ʼ"  # MODIFIER LETTER APOSTROPHE
    "ʹ"  # MODIFIER LETTER PRIME
    "′"  # PRIME
    "´"  # ACUTE ACCENT
    "`"  # GRAVE ACCENT
    "＇"  # FULLWIDTH APOSTROPHE
)

_INVISIBLE_MAP = {ord(c): None for c in _INVISIBLE}
_APOSTROPHE_MAP = {ord(c): "'" for c in _APOSTROPHES}
_TRANSLATION = {**_INVISIBLE_MAP, **_APOSTROPHE_MAP}


def _strip_marks(s: str) -> str:
    return "".join(c for c in s if unicodedata.category(c) not in ("Mn", "Me", "Mc"))


def norm(s: str) -> str:
    """Normalise a surface form. Total: never raises, may return ''."""
    if not s:
        return ""
    s = unicodedata.normalize("NFC", s)
    s = s.translate(_TRANSLATION)
    s = _strip_marks(s)
    s = s.lower()
    s = unicodedata.normalize("NFC", s)
    s = _strip_marks(s)
    return s
