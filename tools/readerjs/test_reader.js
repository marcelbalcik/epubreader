/*
 * Offline checks for the text handling in app/src/main/assets/reader/reader.js.
 *
 *   node tools/readerjs/test_reader.js
 *
 * The pagination and tap geometry need a real WebView, but the sentence
 * splitter and the token cleanup are pure string work and spec section 7 is
 * specific about them ("z.B.", "ca.", "bzw.", ordinals like "3. Mai", 200-char
 * clipping), so they are worth checking without a device.
 *
 * reader.js only registers its DOMContentLoaded handler when the document is
 * still loading, so a stub document with readyState "loading" gives us
 * window.Lesen without any of the DOM work running.
 */

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SCRIPT = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "reader", "reader.js");

function stubDom() {
  const noop = () => {};
  const element = () => ({
    style: { setProperty: noop },
    classList: { add: noop, toggle: noop, contains: () => false },
    appendChild: noop,
    removeChild: noop,
    setAttribute: noop,
    getAttribute: () => "0",
    hasAttribute: () => true,
    querySelectorAll: () => [],
    querySelector: () => null,
    addEventListener: noop,
    getClientRects: () => [],
    firstChild: null,
    textContent: "",
    scrollWidth: 0,
  });

  const document = {
    readyState: "loading",
    addEventListener: noop,
    createElement: element,
    createRange: () => ({ setStart: noop, setEnd: noop, getClientRects: () => [] }),
    createTreeWalker: () => ({ nextNode: () => false, currentNode: null }),
    documentElement: element(),
    body: element(),
    getElementById: () => null,
  };

  const window = {
    innerWidth: 400,
    addEventListener: noop,
    getSelection: () => null,
    CSS: { escape: (s) => s },
    document,
  };

  return {
    window,
    document,
    NodeFilter: { SHOW_TEXT: 4 },
    setTimeout,
    clearTimeout,
    Math,
    JSON,
    parseInt,
    isNaN,
    String,
    Infinity,
  };
}

const sandbox = stubDom();
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(SCRIPT, "utf8"), sandbox, { filename: "reader.js" });

const text = sandbox.window.Lesen.__text;
if (!text) {
  console.error("reader.js did not expose window.Lesen.__text");
  process.exit(1);
}

let failures = 0;

function check(name, actual, expected) {
  const ok = actual === expected;
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
  if (!ok) {
    console.log(`        expected: ${JSON.stringify(expected)}`);
    console.log(`        actual:   ${JSON.stringify(actual)}`);
  }
}

function sentenceFor(paragraph, word) {
  const offset = paragraph.indexOf(word);
  if (offset < 0) throw new Error(`"${word}" not in fixture`);
  return text.sentenceAround({ textContent: paragraph }, offset, word);
}

// --- sentence boundaries ----------------------------------------------------

check(
  "splits on a plain full stop",
  sentenceFor("Er ging nach Hause. Sie blieb stehen.", "blieb"),
  "Sie blieb stehen."
);

check(
  "keeps the first sentence when the tap is in it",
  sentenceFor("Er ging nach Hause. Sie blieb stehen.", "ging"),
  "Er ging nach Hause."
);

check(
  "tolerates z.B.",
  sentenceFor("Es gab dort z.B. ein großes Haus. Danach kam der Wald.", "Haus"),
  "Es gab dort z.B. ein großes Haus."
);

check(
  "tolerates ca.",
  sentenceFor("Dort standen ca. 20 Häuser am Hang. Weiter ging es nicht.", "Häuser"),
  "Dort standen ca. 20 Häuser am Hang."
);

check(
  "tolerates bzw.",
  sentenceFor("Der Autor bzw. die Autorin schrieb das Buch. Ende.", "Buch"),
  "Der Autor bzw. die Autorin schrieb das Buch."
);

check(
  "tolerates an ordinal date",
  sentenceFor("Am 3. Mai kam er zurück. Dann ging er wieder.", "kam"),
  "Am 3. Mai kam er zurück."
);

check(
  "tolerates an initial",
  sentenceFor("Das Buch von A. Schmidt lag dort. Niemand las es.", "lag"),
  "Das Buch von A. Schmidt lag dort."
);

check(
  "handles an exclamation mark",
  sentenceFor("Halt! Er drehte sich um.", "drehte"),
  "Er drehte sich um."
);

check(
  "handles an ellipsis",
  sentenceFor("Vielleicht… Er wusste es nicht.", "wusste"),
  "Er wusste es nicht."
);

check(
  "collapses the whitespace of a wrapped line",
  sentenceFor("Er   ging\n  nach\tHause.", "ging"),
  "Er ging nach Hause."
);

check(
  "drops soft hyphens inside the sentence",
  text.sentenceAround({ textContent: "Ein Kinder­buch lag dort." }, 4, "Kinderbuch"),
  "Ein Kinderbuch lag dort."
);

// --- 200-character clipping -------------------------------------------------

const long = "Wort ".repeat(120) + "Ziel " + "Wort ".repeat(120);
const clipped = text.sentenceAround({ textContent: long }, long.indexOf("Ziel"), "Ziel");
check("clips a long sentence to 200 chars plus ellipses", clipped.length <= 202, true);
check("keeps the tapped word inside the clip", clipped.includes("Ziel"), true);

// --- abbreviation detection -------------------------------------------------

check("abbreviation: digit before the dot", text.looksLikeAbbreviation("Am 3. Mai", 4), true);
check("abbreviation: bzw", text.looksLikeAbbreviation("Autor bzw. Autorin", 9), true);
check("abbreviation: usw", text.looksLikeAbbreviation("Hund, Katze usw. hier", 15), true);
check("not an abbreviation: Hause", text.looksLikeAbbreviation("nach Hause. Sie", 10), false);

// --- token cleanup ----------------------------------------------------------

check("strips a trailing apostrophe", text.cleanToken("geht's", null, 0), "geht's");
check("strips wrapping quotes' apostrophes", text.cleanToken("'Haus'", null, 0), "Haus");
check("strips a leading hyphen", text.cleanToken("-Haus", null, 0), "Haus");
check("removes soft hyphens", text.cleanToken("Kin­der", null, 0), "Kinder");
check("removes zero-width joiners", text.cleanToken("Ge​hen", null, 0), "Gehen");

// --- the word character class ----------------------------------------------

const WORD = text.wordRegex;
for (const ch of ["a", "Z", "ä", "Ö", "ü", "ß", "é", "à", "ÿ", "'", "-"]) {
  check(`word char: ${JSON.stringify(ch)}`, WORD.test(ch), true);
}
for (const ch of ["1", " ", ".", ",", ";", "»", "„", "—", "\n"]) {
  check(`not a word char: ${JSON.stringify(ch)}`, WORD.test(ch), false);
}

console.log();
console.log(failures === 0 ? "all reader.js text checks passed" : `${failures} failure(s)`);
process.exit(failures === 0 ? 0 : 1);
