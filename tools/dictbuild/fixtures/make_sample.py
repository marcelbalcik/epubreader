#!/usr/bin/env python3
"""Generate fixtures/sample_de.jsonl -- a small, deliberately faithful imitation
of the kaikki.org German Wiktextract dump.

Field names, tag vocabulary and the shape of forms[]/head_templates[]/senses[]
mirror the real dump so that build_dict.py's extraction is exercised for real.
The *content* is hand-written: it covers milestone 1's acceptance words plus the
neighbours needed for compound splitting and for ambiguity to show up at all.

Written by hand on purpose: whether the real dump happens to contain a given
inflected form is exactly the thing the fixture must not silently assume.
"""

import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample_de.jsonl")


def e(word, pos, glosses, forms=None, ipa=None, head=None, senses=None, extra=None):
    obj = {"word": word, "pos": pos, "lang": "German", "lang_code": "de"}
    obj["senses"] = senses or [{"glosses": [g], "id": f"{word}-de-{pos}-{i}"}
                               for i, g in enumerate(glosses)]
    if forms:
        obj["forms"] = [{"form": f, "tags": list(t), "source": "declension"}
                        for f, t in forms]
    if head:
        obj["head_templates"] = [head]
    if ipa:
        obj["sounds"] = [{"ipa": ipa}]
    obj["categories"] = ["German lemmas"]
    obj["etymology_text"] = "Fixture entry; etymology deliberately dropped by the build."
    if extra:
        obj.update(extra)
    return obj


def noun(word, gender, plural, glosses, extra_forms=(), ipa=None, genitive=None):
    g = {"m": "m", "f": "f", "n": "n"}[gender]
    exp = f"{word} {g}" + (f" (strong, genitive {genitive}, plural {plural})" if plural else "")
    gender_tag = {"m": "masculine", "f": "feminine", "n": "neuter"}[gender]
    forms = [(word, ("canonical",)), (g, (gender_tag,))]
    if genitive:
        forms.append((genitive, ("genitive", "singular")))
    if plural:
        forms += [(plural, ("nominative", "plural")),
                  (plural, ("accusative", "plural")),
                  (plural + ("" if plural.endswith("n") else "n"), ("dative", "plural"))]
    forms += list(extra_forms)
    return e(word, "noun", glosses, forms, ipa,
             head={"name": "de-noun", "args": {"1": g, "2": genitive or "", "3": plural or ""},
                   "expansion": exp})


def verb(word, glosses, forms, ipa=None):
    return e(word, "verb", glosses, forms, ipa,
             head={"name": "de-verb", "args": {}, "expansion": f"{word} (class 1 strong)"})


def formof(word, pos, target, desc, tags=()):
    """An inflection-only entry, as wiktextract emits for e.g. 'Häuser'."""
    return {"word": word, "pos": pos, "lang": "German", "lang_code": "de",
            "senses": [{"glosses": [desc], "tags": list(tags),
                        "form_of": [{"word": target}]}]}


def altof(word, pos, target, desc, tags=("obsolete",)):
    return {"word": word, "pos": pos, "lang": "German", "lang_code": "de",
            "senses": [{"glosses": [desc], "tags": list(tags),
                        "alt_of": [{"word": target}]}]}


rows = [
    # --- milestone 1 targets -------------------------------------------------
    noun("Haus", "n", "Häuser", ["house", "building", "home"],
         genitive="Hauses", ipa="/haʊ̯s/"),
    formof("Häuser", "noun", "Haus", "nominative plural of Haus"),
    verb("gehen", ["to go, to walk", "to leave", "to work, to function"],
         [("gehe", ("first-person", "singular", "present")),
          ("geht", ("third-person", "singular", "present")),
          ("ging", ("first-person", "singular", "past")),
          ("ging", ("third-person", "singular", "past")),
          ("gingen", ("first-person", "plural", "past")),
          ("gegangen", ("participle", "past")),
          ("ist gegangen", ("perfect",)),          # multiword: must be skipped
          ("sein", ("auxiliary",))],                # auxiliary: must be skipped
         ipa="/ˈɡeːən/"),
    verb("aufstehen", ["to stand up, to get up", "to rise, to revolt"],
         [("stehe auf", ("first-person", "singular", "present")),
          ("steht auf", ("third-person", "singular", "present")),
          ("stand auf", ("third-person", "singular", "past")),
          ("aufgestanden", ("participle", "past"))]),
    # 'schön' on purpose carries only what the headword line gives: no
    # declension table, so 'schönste' is NOT in the form index.
    e("schön", "adj", ["beautiful, lovely", "nice, pleasant"],
      forms=[("schöner", ("comparative",)), ("am schönsten", ("superlative",))],
      ipa="/ʃøːn/",
      head={"name": "de-adj", "args": {}, "expansion": "schön (comparative schöner, superlative am schönsten)"}),
    noun("Autorin", "f", "Autorinnen", ["female author, authoress"], genitive="Autorin"),
    noun("Autor", "m", "Autoren", ["author, writer"], genitive="Autors"),
    noun("Kind", "n", "Kinder", ["child", "kid"], genitive="Kindes"),
    noun("Buch", "n", "Bücher", ["book"], genitive="Buches"),
    e("dass", "conj", ["that (introducing a subordinate clause)"],
      ipa="/das/", head={"name": "head", "args": {"1": "de", "2": "conjunction"},
                         "expansion": "dass"}),
    altof("daß", "conj", "dass", "Obsolete spelling of dass"),

    # --- compound material ---------------------------------------------------
    noun("Achse", "f", "Achsen", ["axis", "axle"]),
    noun("Norden", "m", None, ["north"], genitive="Nordens"),
    noun("Süden", "m", None, ["south"], genitive="Südens"),
    e("nord", "adv", ["north (in compounds)"]),
    noun("Donau", "f", None, ["Danube (the river)"]),
    noun("Dampf", "m", "Dämpfe", ["steam, vapour"], genitive="Dampfes"),
    noun("Schiff", "n", "Schiffe", ["ship", "nave of a church"], genitive="Schiffes"),
    noun("Fahrt", "f", "Fahrten", ["journey, ride, trip"]),
    noun("Schifffahrt", "f", "Schifffahrten", ["shipping, navigation"]),
    noun("Tür", "f", "Türen", ["door"]),
    noun("Zeit", "f", "Zeiten", ["time"], genitive="Zeit"),
    noun("Wasser", "n", "Wässer", ["water"], genitive="Wassers"),
    noun("Flasche", "f", "Flaschen", ["bottle"]),
    noun("Straße", "f", "Straßen", ["street, road"], ipa="/ˈʃtʁaːsə/"),
    noun("Wohnung", "f", "Wohnungen", ["flat, apartment"]),
    verb("wohnen", ["to live, to reside"],
         [("wohne", ("first-person", "singular", "present")),
          ("wohnt", ("third-person", "singular", "present")),
          ("wohnte", ("third-person", "singular", "past")),
          ("gewohnt", ("participle", "past"))]),
    noun("Bild", "n", "Bilder", ["picture, image"], genitive="Bildes"),
    verb("bilden", ["to form, to educate"],
         [("bilde", ("first-person", "singular", "present")),
          ("bildet", ("third-person", "singular", "present")),
          ("bildete", ("third-person", "singular", "past")),
          ("gebildet", ("participle", "past"))]),
    noun("Bildung", "f", "Bildungen", ["education", "formation"]),
    verb("sehen", ["to see"],
         [("sehe", ("first-person", "singular", "present")),
          ("sieht", ("third-person", "singular", "present")),
          ("sah", ("third-person", "singular", "past")),
          ("gesehen", ("participle", "past"))]),
    verb("laufen", ["to run", "to walk"],
         [("laufe", ("first-person", "singular", "present")),
          ("läuft", ("third-person", "singular", "present")),
          ("lief", ("third-person", "singular", "past")),
          ("gelaufen", ("participle", "past"))]),
    noun("Frau", "f", "Frauen", ["woman", "wife", "Mrs"]),
    noun("Mann", "m", "Männer", ["man", "husband"], genitive="Mannes"),
    e("der", "article", ["the (masculine nominative singular)"],
      forms=[("des", ("genitive", "masculine", "singular")),
             ("dem", ("dative", "masculine", "singular")),
             ("den", ("accusative", "masculine", "singular")),
             ("die", ("nominative", "feminine", "singular"))]),
    e("gut", "adj", ["good"], forms=[("besser", ("comparative",))]),
    e("und", "conj", ["and"]),
    e("in", "prep", ["in", "into"]),
    e("sie", "pron", ["she", "they", "you (formal)"]),
    e("sehr", "adv", ["very"]),
    e("zwei", "num", ["two"]),
    e("ach", "intj", ["oh, alas"]),
    e("jeder", "det", ["each, every"]),
    e("ja", "particle", ["yes", "indeed"]),

    # --- material that must be filtered out ----------------------------------
    e("Berlin", "name", ["Berlin, the capital of Germany"]),       # POS not kept
    e("un-", "prefix", ["un- (negating prefix)"]),                  # POS not kept
    e("auf jeden Fall", "adv", ["in any case"]),                    # multiword word
    e("Behuf", "noun", ["purpose"], senses=[{"glosses": ["purpose"], "tags": ["obsolete"]}]),
    e("Aventiure", "noun", ["adventure"], senses=[{"glosses": ["adventure"], "tags": ["archaic"]}]),
    {"word": "chien", "pos": "noun", "lang": "French", "lang_code": "fr",
     "senses": [{"glosses": ["dog"]}]},                              # wrong language
    e("Wortteil", "noun", ["part of a word"],
      senses=[{"glosses": [], "tags": []}]),                         # no usable gloss
]

with open(OUT, "w", encoding="utf-8") as fh:
    for row in rows:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
print(f"wrote {OUT}: {len(rows)} objects")
