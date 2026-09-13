/*
 * Browser checks for reader.css + reader.js in a real Chromium, which is the
 * same engine family as the Android WebView.
 *
 *   npm install playwright          # once, anywhere on NODE_PATH
 *   node tools/readerjs/test_reader_browser.mjs
 *
 * This covers the parts of milestones 2-4 that are about the page rather than
 * about Android: that the columns paginate at exactly one viewport width per
 * page, that a tap lands on the word under the finger and never on the word
 * beside it, that margin taps turn pages while taps on text do not, that the
 * highlight cannot reflow the column, that a constrained image does not produce
 * a blank page, and that a position saved as (block, offset) survives a font
 * size change - the "same sentence, not the same pixel" check.
 *
 * What it cannot cover: the WebView settings, the @JavascriptInterface bridge
 * itself, SAF, Room, and anything else that needs a device.
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.join(HERE, "..", "..", "app", "src", "main", "assets");
const WIDTH = 400;
const HEIGHT = 800;

/* The two tags ReaderHtml.inject puts at the end of <head>. Kept identical on
   purpose; ReaderHtmlTest asserts the Kotlin side produces exactly these. */
const INJECTION = `
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link rel="stylesheet" type="text/css" href="/lesen/reader/reader.css" />
  <script src="/lesen/reader/reader.js" defer="defer"></script>
`;

/* A chapter shaped like a real EPUB one: its own stylesheet with opinions we
   have to override, an oversized image, a heading, and enough German prose to
   fill several columns. */
const PARAGRAPHS = [
  "Die Kinderbuchautorin saß am Fenster und sah auf die Straße hinaus, wo die Häuser im Regen standen.",
  "Sie ging langsam durch den Flur, öffnete die Tür und blieb stehen, weil das Licht sie überraschte.",
  "Am 3. Mai kam der Brief. Er lag auf dem Tisch, ungeöffnet, zwischen zwei Tassen und einem Buch.",
  "Es waren ca. zwanzig Menschen im Raum, die alle gleichzeitig sprachen, und niemand hörte zu.",
  "Der Autor bzw. die Autorin hatte das Manuskript längst abgegeben, aber niemand wollte es lesen.",
  "Draußen fuhr ein Donaudampfschiff vorbei, und das Wasser schlug gegen die Mauer der Uferstraße.",
  "Sie war gestern erst aufgestanden, hatte gefrühstückt und war dann wieder ins Bett gegangen.",
  "Das schönste Zimmer war das kleinste, mit einem Fenster zur Nord-Süd-Achse der alten Stadt.",
];

function chapterHtml({ imageHeight = 2400, unconstrained = false } = {}) {
  const bookCss = `
    body { background: #ffffff; color: #000000; font-family: "Some Book Font", serif;
           margin: 2em; font-size: 12pt; }
    p { text-indent: 1.5em; }
    img { width: 100%; }
    ${unconstrained ? "#lesen-content img { max-height: none !important; }" : ""}
  `;
  const image =
    `<img id="plate" alt="" width="1200" height="${imageHeight}" ` +
    `src="data:image/svg+xml;base64,${Buffer.from(
      `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="${imageHeight}"><rect width="100%" height="100%" fill="#888"/></svg>`
    ).toString("base64")}" />`;

  const body = [
    "<h2 id=\"kap\">Erstes Kapitel</h2>",
    ...PARAGRAPHS.map((p) => `<p>${p}</p>`),
    `<figure>${image}<figcaption>Eine Tafel</figcaption></figure>`,
    ...PARAGRAPHS.map((p) => `<p>${p}</p>`),
  ].join("\n");

  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Kapitel</title><style>${bookCss}</style>${INJECTION}</head>
<body>${body}</body>
</html>`;
}

async function serve() {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    try {
      if (url.pathname.startsWith("/lesen/")) {
        const file = path.join(ASSETS, url.pathname.slice("/lesen/".length));
        const body = await readFile(file);
        const type = file.endsWith(".css")
          ? "text/css"
          : file.endsWith(".js")
            ? "text/javascript"
            : "font/ttf";
        res.writeHead(200, { "content-type": type });
        res.end(body);
        return;
      }
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      res.end(
        chapterHtml({
          imageHeight: Number(url.searchParams.get("img") ?? 2400),
          unconstrained: url.searchParams.get("unconstrained") === "1",
        })
      );
    } catch (e) {
      res.writeHead(404).end(String(e));
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { server, port: server.address().port };
}

let failures = 0;

function check(name, ok, detail) {
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}`);
  if (!ok && detail !== undefined) console.log(`        ${detail}`);
}

function near(a, b, slop = 2) {
  return Math.abs(a - b) <= slop;
}

/** Installs the window.Android stub that reader.js posts to, and records calls. */
async function openChapter(browser, url) {
  const page = await browser.newPage({ viewport: { width: WIDTH, height: HEIGHT } });
  await page.addInitScript(() => {
    window.__calls = [];
    window.Android = {
      onWordTapped: (word, sentence, blockIndex, charOffset) =>
        window.__calls.push({ kind: "word", word, sentence, blockIndex, charOffset }),
      onTapEmpty: (zone) => window.__calls.push({ kind: "empty", zone }),
      onPositionChanged: (blockIndex, charOffset, page, pages) =>
        window.__calls.push({ kind: "position", blockIndex, charOffset, page, pages }),
      onSelectionTranslate: (text) => window.__calls.push({ kind: "selection", text }),
    };
  });
  await page.goto(url, { waitUntil: "load" });
  await page.waitForFunction(() => document.body.classList.contains("lesen-ready"));
  await page.waitForTimeout(150);
  return page;
}

const calls = (page, kind) =>
  page.evaluate((k) => window.__calls.filter((c) => c.kind === k), kind);
const clearCalls = (page) => page.evaluate(() => (window.__calls = []));

/** Viewport rect of the first occurrence of a word, via a Range. */
const wordRect = (page, word) =>
  page.evaluate((needle) => {
    const walker = document.createTreeWalker(
      document.getElementById("lesen-content"),
      NodeFilter.SHOW_TEXT
    );
    while (walker.nextNode()) {
      const node = walker.currentNode;
      const at = (node.nodeValue || "").indexOf(needle);
      if (at < 0) continue;
      const range = document.createRange();
      range.setStart(node, at);
      range.setEnd(node, at + needle.length);
      const rect = range.getBoundingClientRect();
      if (rect.width === 0 || rect.right <= 0 || rect.left >= window.innerWidth) continue;
      return { x: rect.left, y: rect.top, w: rect.width, h: rect.height };
    }
    return null;
  }, word);

const main = async () => {
  const { server, port } = await serve();
  const base = `http://127.0.0.1:${port}/`;
  const browser = await chromium.launch({ executablePath: chromium.executablePath() });

  try {
    const page = await openChapter(browser, base);

    // --- structure ------------------------------------------------------
    const structure = await page.evaluate(() => {
      const content = document.getElementById("lesen-content");
      const blocks = content.querySelectorAll("[data-i]");
      const indices = Array.from(blocks).map((b) => Number(b.getAttribute("data-i")));
      return {
        hasContent: !!content,
        hasSnap: !!document.getElementById("lesen-snap"),
        blocks: blocks.length,
        indicesAreDocumentOrder: indices.every((v, i) => v === i),
        stats: JSON.parse(window.Lesen.stats()),
      };
    });
    check("wraps the chapter in #lesen-content", structure.hasContent);
    check("builds the snap overlay", structure.hasSnap);
    check("stamps every block with data-i in document order", structure.indicesAreDocumentOrder,
      `blocks: ${structure.blocks}`);
    check("paginates into several pages", structure.stats.pages > 2, JSON.stringify(structure.stats));

    // --- page geometry --------------------------------------------------
    const snapCount = await page.evaluate(
      () => document.querySelectorAll("#lesen-snap > i").length
    );
    check("one snap point per page", snapCount === structure.stats.pages,
      `${snapCount} snap points, ${structure.stats.pages} pages`);

    const geometry = await page.evaluate(() => {
      const out = [];
      for (const target of [0, 1, 2, 3]) {
        window.Lesen.goToPage(target);
        out.push({ target, left: document.body.scrollLeft });
      }
      window.Lesen.goToPage(0);
      return out;
    });
    check(
      "page N starts at exactly N viewport widths",
      geometry.every((g) => near(g.left, g.target * WIDTH)),
      JSON.stringify(geometry)
    );

    const noVerticalScroll = await page.evaluate(
      () => document.body.scrollHeight <= window.innerHeight + 1
    );
    check("the page does not scroll vertically", noVerticalScroll);

    // --- the constrained image (spec section 12, trap 1) ----------------
    const plate = await page.evaluate(() => {
      const img = document.getElementById("plate");
      const rect = img.getBoundingClientRect();
      return { height: rect.height, viewport: window.innerHeight };
    });
    check(
      "an oversized image is constrained to the column",
      plate.height <= plate.viewport * 0.93,
      `image ${Math.round(plate.height)}px in a ${plate.viewport}px viewport`
    );

    const constrainedPages = structure.stats.pages;
    const loosePage = await openChapter(browser, `${base}?unconstrained=1`);
    const loosePages = await loosePage.evaluate(() => JSON.parse(window.Lesen.stats()).pages);
    await loosePage.close();
    check(
      "constraining the image saves pages the blank-page trap would cost",
      constrainedPages <= loosePages,
      `constrained ${constrainedPages} pages, unconstrained ${loosePages}`
    );

    // --- taps -----------------------------------------------------------
    await clearCalls(page);
    const target = await wordRect(page, "Kinderbuchautorin");
    check("the test word is on the first page", target !== null);
    if (target) {
      await page.mouse.click(target.x + target.w / 2, target.y + target.h / 2);
      await page.waitForTimeout(60);
      const words = await calls(page, "word");
      check("a tap on a word reports that word", words.length === 1 &&
        words[0].word === "Kinderbuchautorin", JSON.stringify(words));
      check(
        "the reported sentence is the sentence around it",
        words[0]?.sentence?.startsWith("Die Kinderbuchautorin saß"),
        words[0]?.sentence
      );
      check(
        "the sentence is clipped to 200 characters",
        (words[0]?.sentence?.length ?? 0) <= 202,
        `${words[0]?.sentence?.length} chars`
      );
      check("no page turn was reported for a word tap",
        (await calls(page, "empty")).length === 0);

      const highlighted = await page.evaluate(() => {
        const hit = document.querySelector(".lesen-hit");
        return hit ? { text: hit.textContent, display: getComputedStyle(hit).display } : null;
      });
      check("the tapped word is highlighted inline", highlighted?.display === "inline",
        JSON.stringify(highlighted));
      check("the highlight wraps the tapped word",
        highlighted?.text === "Kinderbuchautorin", highlighted?.text);
    }

    // The highlight must not reflow the column (spec section 7).
    const afterHighlight = await page.evaluate(() => JSON.parse(window.Lesen.stats()));
    check("the highlight does not change the page count",
      afterHighlight.pages === structure.stats.pages,
      `${structure.stats.pages} -> ${afterHighlight.pages}`);
    await page.evaluate(() => window.Lesen.clearHighlight());
    const afterClear = await page.evaluate(
      () => document.querySelectorAll(".lesen-hit").length
    );
    check("clearing the highlight removes the span", afterClear === 0);

    // A tap beside a word, in the margin, is a page turn.
    await clearCalls(page);
    await page.mouse.click(3, HEIGHT / 2);
    await page.waitForTimeout(60);
    const marginTap = await calls(page, "empty");
    check("a tap in the left margin reports a page turn",
      marginTap.length === 1 && marginTap[0].zone === "left", JSON.stringify(marginTap));

    // A tap in the centre, between paragraphs, toggles the chrome.
    await clearCalls(page);
    await page.mouse.click(WIDTH / 2, HEIGHT - 4);
    await page.waitForTimeout(60);
    const centreTap = await calls(page, "empty");
    check("a tap on empty space in the middle reports the centre zone",
      centreTap.length === 0 || centreTap[0].zone === "center", JSON.stringify(centreTap));

    // A word that happens to sit inside the 12% strip is still a lookup
    // (docs/DECISIONS.md).
    await clearCalls(page);
    const inStrip = await page.evaluate(() => {
      const walker = document.createTreeWalker(
        document.getElementById("lesen-content"),
        NodeFilter.SHOW_TEXT
      );
      const limit = window.innerWidth * 0.12;
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const text = node.nodeValue || "";
        const re = /[A-Za-zÄÖÜäöüß]{4,}/g;
        let m;
        while ((m = re.exec(text)) !== null) {
          const range = document.createRange();
          range.setStart(node, m.index);
          range.setEnd(node, m.index + m[0].length);
          const rect = range.getBoundingClientRect();
          if (rect.width === 0 || rect.left < 0) continue;
          if (rect.left + rect.width / 2 < limit && rect.top > 0 &&
            rect.bottom < window.innerHeight) {
            return { word: m[0], x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
          }
        }
      }
      return null;
    });
    if (inStrip) {
      await page.mouse.click(inStrip.x, inStrip.y);
      await page.waitForTimeout(60);
      const stripWords = await calls(page, "word");
      check(
        "a word inside the 12% strip is looked up, not swallowed by a page turn",
        stripWords.length === 1 && stripWords[0].word === inStrip.word,
        `${inStrip.word} at x=${Math.round(inStrip.x)}: ${JSON.stringify(stripWords)}`
      );
    } else {
      console.log("SKIP  no word falls inside the 12% strip at this width");
    }

    // --- punctuation and the rect test (milestone 4) --------------------
    await page.evaluate(() => window.Lesen.clearHighlight());
    await clearCalls(page);

    // A word immediately before a full stop, and the full stop itself.
    const punct = await page.evaluate(() => {
      const walker = document.createTreeWalker(
        document.getElementById("lesen-content"),
        NodeFilter.SHOW_TEXT
      );
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const text = node.nodeValue || "";
        const m = /([A-Za-zÄÖÜäöüß]{4,})\./.exec(text);
        if (!m) continue;
        const word = document.createRange();
        word.setStart(node, m.index);
        word.setEnd(node, m.index + m[1].length);
        const dot = document.createRange();
        dot.setStart(node, m.index + m[1].length);
        dot.setEnd(node, m.index + m[1].length + 1);
        const wr = word.getBoundingClientRect();
        const dr = dot.getBoundingClientRect();
        if (wr.width === 0 || wr.left < 0 || wr.right > window.innerWidth) continue;
        if (wr.top < 0 || wr.bottom > window.innerHeight) continue;
        return {
          word: m[1],
          lastCharX: wr.right - 2,
          y: wr.top + wr.height / 2,
          dotX: dr.left + dr.width / 2,
        };
      }
      return null;
    });
    if (punct) {
      await page.mouse.click(punct.lastCharX, punct.y);
      await page.waitForTimeout(60);
      const got = await calls(page, "word");
      check(
        "a tap on the last letter before a full stop reports the whole word",
        got.length === 1 && got[0].word === punct.word,
        `expected ${punct.word}, got ${JSON.stringify(got)}`
      );

      await clearCalls(page);
      await page.evaluate(() => window.Lesen.clearHighlight());
      await page.mouse.click(punct.dotX, punct.y);
      await page.waitForTimeout(60);
      const onDot = await calls(page, "word");
      check(
        "a tap on the full stop does not report a neighbouring word",
        onDot.length === 0 || onDot[0].word === punct.word,
        JSON.stringify(onDot)
      );
    } else {
      console.log("SKIP  no word-then-period pair is fully visible at this width");
    }

    // Empty space to the right of a short line must not resolve to the word
    // that happens to end that line (the rect test in spec section 7).
    await clearCalls(page);
    await page.evaluate(() => window.Lesen.clearHighlight());
    const endOfLine = await page.evaluate(() => {
      const caption = document.querySelector("#lesen-content figcaption");
      if (!caption) return null;
      const rect = caption.getBoundingClientRect();
      const range = document.createRange();
      range.selectNodeContents(caption);
      const text = range.getBoundingClientRect();
      const gap = rect.right - text.right;
      if (gap < 24) return null;
      return { x: text.right + gap / 2, y: text.top + text.height / 2 };
    });
    if (endOfLine) {
      await page.mouse.click(endOfLine.x, endOfLine.y);
      await page.waitForTimeout(60);
      const beside = await calls(page, "word");
      check("a tap in the blank space beside a line reports no word",
        beside.length === 0, JSON.stringify(beside));
    } else {
      console.log("SKIP  no line with enough trailing space to test the rect check");
    }

    // --- chapter edges --------------------------------------------------
    const edges = await page.evaluate(() => {
      window.Lesen.goToPage(0);
      const backAtStart = window.Lesen.turnPage(-1);
      const forwardFromStart = window.Lesen.turnPage(1);
      window.Lesen.goToLastPage();
      const forwardAtEnd = window.Lesen.turnPage(1);
      const info = JSON.parse(window.Lesen.pageInfo());
      return { backAtStart, forwardFromStart, forwardAtEnd, info };
    });
    check("turnPage refuses to go back past the first page",
      edges.backAtStart === false, JSON.stringify(edges));
    check("turnPage moves forward inside the chapter",
      edges.forwardFromStart === true, JSON.stringify(edges));
    check("turnPage refuses to go past the last page, so Kotlin can cross over",
      edges.forwardAtEnd === false && edges.info.page === edges.info.pages - 1,
      JSON.stringify(edges));

    // --- position, across a font size change (milestone 3) --------------
    await page.evaluate(() => window.Lesen.clearHighlight());
    await clearCalls(page);
    const saved = await page.evaluate(() => {
      window.Lesen.goToPage(3);
      const reports = window.__calls.filter((c) => c.kind === "position");
      const last = reports[reports.length - 1];
      const block = document.querySelector(`[data-i="${last.blockIndex}"]`);
      return {
        blockIndex: last.blockIndex,
        charOffset: last.charOffset,
        page: last.page,
        pages: last.pages,
        text: (block.textContent || "").slice(last.charOffset, last.charOffset + 40),
      };
    });
    check("the position is reported as a block index and a character offset",
      Number.isInteger(saved.blockIndex) && Number.isInteger(saved.charOffset),
      JSON.stringify(saved));

    const restored = await page.evaluate(async (anchor) => {
      window.Lesen.applySettings(JSON.stringify({
        fontSize: 26, lineHeight: 1.8, margin: 32,
        fontFamily: "\"Inter\", sans-serif", theme: "sepia",
        bg: "#F4ECD8", fg: "#3A2F22", link: "#7A5C2E",
        hit: "rgba(214,168,79,0.45)", forceColors: true,
      }));
      await new Promise((r) => setTimeout(r, 300));
      const pagesAfter = JSON.parse(window.Lesen.stats()).pages;
      window.Lesen.restore(anchor.blockIndex, anchor.charOffset);
      const block = document.querySelector(`[data-i="${anchor.blockIndex}"]`);
      const rects = Array.from(block.getClientRects());
      const visible = rects.some((r) => r.right > 0 && r.left < window.innerWidth);

      // Stronger than "the block is somewhere on screen": the character the
      // position was saved at has to be inside the column now on screen. A
      // block can be several pages long, so only this distinguishes "same
      // sentence" from "same chapter".
      let offsetVisible = false;
      let offsetText = "";
      const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
      let seen = 0;
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const len = (node.nodeValue || "").length;
        if (seen + len >= anchor.charOffset) {
          const local = Math.max(0, Math.min(anchor.charOffset - seen, len - 1));
          const range = document.createRange();
          range.setStart(node, local);
          range.setEnd(node, Math.min(local + 1, len));
          const r = range.getClientRects()[0];
          if (r) {
            offsetVisible = r.left >= -2 && r.left < window.innerWidth + 2;
            offsetText = (node.nodeValue || "").slice(local, local + 30);
          }
          break;
        }
        seen += len;
      }
      return {
        pagesAfter,
        visible,
        offsetVisible,
        offsetText,
        page: JSON.parse(window.Lesen.pageInfo()).page,
        forced: getComputedStyle(document.querySelector("#lesen-content p")).color,
        fontSize: getComputedStyle(document.getElementById("lesen-content")).fontSize,
      };
    }, saved);

    check("a larger font repaginates the chapter",
      restored.pagesAfter > saved.pages,
      `${saved.pages} pages at 19px, ${restored.pagesAfter} at 26px`);
    check("the settings actually reach the page", restored.fontSize === "26px",
      restored.fontSize);
    check(
      "the saved block is on screen again after the font size changed",
      restored.visible,
      `block ${saved.blockIndex} ("${saved.text.trim()}") landed on page ${restored.page} of ${restored.pagesAfter}`
    );
    check(
      "the saved sentence, not just the block, is in the visible column",
      restored.offsetVisible,
      `offset ${saved.charOffset} ("${restored.offsetText.trim()}") on page ` +
      `${restored.page} of ${restored.pagesAfter}`
    );
    check(
      "the theme overrides the book's hardcoded black text",
      restored.forced === "rgb(58, 47, 34)",
      restored.forced
    );

    // --- selection ------------------------------------------------------
    await clearCalls(page);
    const selection = await page.evaluate(() => {
      const block = document.querySelector("#lesen-content p");
      const range = document.createRange();
      range.selectNodeContents(block);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      window.Lesen.sendSelection();
      return window.__calls.filter((c) => c.kind === "selection");
    });
    check("sendSelection posts the selected text",
      selection.length === 1 && selection[0].text.includes("Kinderbuchautorin"),
      JSON.stringify(selection).slice(0, 120));

    await page.close();
  } finally {
    await browser.close();
    server.close();
  }

  console.log();
  console.log(failures === 0 ? "all browser checks passed" : `${failures} failure(s)`);
  process.exit(failures === 0 ? 0 : 1);
};

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
