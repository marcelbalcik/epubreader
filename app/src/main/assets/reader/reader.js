/*
 * Lesen's injected reader script (spec sections 6, 7, 12).
 *
 * Responsibilities, in the order they happen:
 *   1. wrap the chapter in #lesen-content and stamp every block with data-i
 *   2. build the scroll-snap overlay, one snap point per page
 *   3. translate taps into either a word lookup or a page turn
 *   4. report the reading position as (blockIndex, charOffset), never pixels
 *   5. re-paginate and restore that position on every relayout
 *
 * The Kotlin side talks to this through window.Lesen; this side talks back
 * through the @JavascriptInterface object window.Android (see ReaderBridge.kt).
 */
(function () {
  "use strict";

  var BLOCK_SELECTOR =
    "p, div, li, blockquote, h1, h2, h3, h4, h5, h6, pre, td, th, dd, dt, " +
    "figcaption, section, article, aside, tr, table, figure, hr, img";

  /* Word characters of the tap target. Mirrors Normalizer.isWordChar in Kotlin;
     Latin-1 and Latin Extended cover German plus the accented loanwords that
     turn up in German prose. */
  var WORD = /[A-Za-zÀ-ɏḀ-ỿ’'-]/;

  var SENTENCE_END = /[.!?…]/;

  /* Abbreviations that end in a period without ending a sentence. */
  var ABBREVIATIONS = [
    "z.b", "u.a", "d.h", "v.a", "o.ä", "u.ä", "i.d.r", "bzw", "ca", "etc",
    "usw", "vgl", "evtl", "ggf", "bspw", "inkl", "exkl", "max", "min", "nr",
    "abb", "bzgl", "jh", "mio", "mrd", "hr", "fr", "dr", "prof", "st", "sog",
    "ff", "s", "vs", "engl", "dt", "lat", "griech"
  ];

  var MARGIN_ZONE = 0.12;      /* spec section 6: outer 12% strips */
  var SENTENCE_LIMIT = 200;    /* spec section 7 */
  var TAP_SLOP = 4;            /* spec section 7: rect tolerance in px */
  var SWIPE_MIN = 40;          /* px before a boundary swipe counts */

  var content = null;
  var snap = null;
  var blocks = [];
  var pageCount = 1;
  var highlight = null;
  var lastReport = { block: -1, offset: -1, page: -1 };
  var relayoutTimer = null;

  // --------------------------------------------------------------------------
  // setup
  // --------------------------------------------------------------------------

  function bridge() {
    return typeof window.Android !== "undefined" ? window.Android : null;
  }

  function setup() {
    if (content) return;

    content = document.createElement("div");
    content.id = "lesen-content";
    while (document.body.firstChild) {
      content.appendChild(document.body.firstChild);
    }
    document.body.appendChild(content);

    snap = document.createElement("div");
    snap.id = "lesen-snap";
    document.body.appendChild(snap);

    stampBlocks();
    paginate();

    document.body.classList.add("lesen-ready");
    document.addEventListener("click", onClick, true);
    document.body.addEventListener("scroll", onScroll, { passive: true });
    document.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", scheduleRelayout);
    window.addEventListener("orientationchange", scheduleRelayout);

    /* Images decode after parse and change the column count when they land. */
    var images = content.querySelectorAll("img");
    for (var i = 0; i < images.length; i++) {
      if (!images[i].complete) {
        images[i].addEventListener("load", scheduleRelayout);
        images[i].addEventListener("error", scheduleRelayout);
      }
    }

    installSwipeBoundaryDetector();
    report(true);
  }

  /**
   * Stamps every block-level element with data-i in document order. The saved
   * position is (spineIndex, blockIndex, charOffset) - a pixel offset breaks the
   * moment the font size, margin or orientation changes (spec section 6).
   */
  function stampBlocks() {
    blocks = [];
    var nodes = content.querySelectorAll(BLOCK_SELECTOR);
    var index = 0;
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].setAttribute("data-i", String(index));
      blocks.push(nodes[i]);
      index++;
    }
    if (blocks.length === 0) {
      content.setAttribute("data-i", "0");
      blocks.push(content);
    }
  }

  function pageWidth() {
    return window.innerWidth || document.documentElement.clientWidth;
  }

  /**
   * Rebuilds the snap overlay to match the current column count.
   *
   * The overlay is torn down *before* measuring: it is as wide as the last
   * pagination made it, and it is inside the scroller, so leaving it in place
   * would keep body.scrollWidth at the old value and the page count could never
   * shrink after the font size or margin came down.
   */
  function paginate() {
    while (snap.firstChild) snap.removeChild(snap.firstChild);
    snap.style.width = "0px";

    var width = pageWidth();
    var total = Math.max(document.body.scrollWidth, content.scrollWidth, width);
    pageCount = Math.max(1, Math.round(total / width));

    snap.style.width = pageCount * width + "px";
    for (var i = 0; i < pageCount; i++) {
      snap.appendChild(document.createElement("i"));
    }
  }

  function currentPage() {
    return Math.max(0, Math.min(pageCount - 1,
      Math.round(document.body.scrollLeft / pageWidth())));
  }

  function goToPage(page, smooth) {
    var target = Math.max(0, Math.min(pageCount - 1, page));
    var left = target * pageWidth();
    if (smooth && document.body.scrollTo) {
      document.body.scrollTo({ left: left, behavior: "smooth" });
    } else {
      document.body.scrollLeft = left;
    }
    return target;
  }

  // --------------------------------------------------------------------------
  // taps
  // --------------------------------------------------------------------------

  function zoneOf(x) {
    var w = pageWidth();
    if (x < w * MARGIN_ZONE) return "left";
    if (x > w * (1 - MARGIN_ZONE)) return "right";
    return "center";
  }

  /**
   * Click, not touchstart: the scroller has to claim swipes first, or every
   * page turn would also fire a lookup (spec section 7).
   */
  function onClick(event) {
    if (event.target && event.target.closest && event.target.closest("a[href]")) {
      return;   /* internal links are the book's own navigation */
    }
    var x = event.clientX;
    var y = event.clientY;
    var zone = zoneOf(x);

    var hit = wordAt(x, y);
    if (hit) {
      /*
       * A word under the finger wins over the page-turn strip. The strips are
       * 12% wide but the text margin is only --margin, so the strip overlaps
       * real text on a wide screen; without this, taps on the first and last
       * word of a line would turn the page instead of looking the word up,
       * which is exactly what milestone 4's check forbids. Tapping actual
       * margin still turns the page, because no word passes the rect test
       * there. See docs/DECISIONS.md.
       */
      applyHighlight(hit.range);
      var api = bridge();
      if (api) {
        api.onWordTapped(hit.word, hit.sentence, hit.blockIndex, hit.charOffset);
      }
      event.preventDefault();
      event.stopPropagation();
      return;
    }

    var api2 = bridge();
    if (api2) api2.onTapEmpty(zone);
  }

  /** caretRangeFromPoint with the standards-track fallback (spec section 12). */
  function caretRangeAt(x, y) {
    if (document.caretRangeFromPoint) {
      var r = document.caretRangeFromPoint(x, y);
      if (r) return r;
    }
    if (document.caretPositionFromPoint) {
      var pos = document.caretPositionFromPoint(x, y);
      if (pos && pos.offsetNode) {
        var range = document.createRange();
        range.setStart(pos.offsetNode, pos.offset);
        range.setEnd(pos.offsetNode, pos.offset);
        return range;
      }
    }
    return null;
  }

  /**
   * Expands the caret position over the German word class, within one text node
   * only - crossing element boundaries would glue "Haus" and "tür" together
   * across an <em>.
   */
  function wordAt(x, y) {
    var caret = caretRangeAt(x, y);
    if (!caret) return null;

    var node = caret.startContainer;
    if (!node || node.nodeType !== 3) return null;
    var text = node.nodeValue || "";
    if (!text) return null;

    var pos = Math.min(caret.startOffset, text.length);
    var start = pos;
    var end = pos;
    while (start > 0 && WORD.test(text.charAt(start - 1))) start--;
    while (end < text.length && WORD.test(text.charAt(end))) end++;
    if (end <= start) return null;

    var range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);

    /* Verify the tap actually landed on the word and not on padding beside it. */
    var rect = range.getBoundingClientRect();
    if (!rect || rect.width === 0 ||
      x < rect.left - TAP_SLOP || x > rect.right + TAP_SLOP ||
      y < rect.top - TAP_SLOP || y > rect.bottom + TAP_SLOP) {
      return null;
    }

    var raw = text.slice(start, end);
    var word = cleanToken(raw, node, end);
    if (!word) return null;

    var block = blockOf(node);
    var blockIndex = block ? parseInt(block.getAttribute("data-i"), 10) : 0;
    var offset = offsetWithinBlock(block, node, start);

    return {
      word: word,
      sentence: sentenceAround(block, offset, word),
      blockIndex: isNaN(blockIndex) ? 0 : blockIndex,
      charOffset: offset,
      range: range
    };
  }

  /**
   * Post-processing required by spec section 7: strip leading and trailing
   * apostrophes and hyphens, drop soft hyphens, and rejoin a word the book
   * hyphenated across a line itself.
   */
  function cleanToken(raw, node, end) {
    var word = raw.replace(/[­​‌‍⁠﻿]/g, "");
    word = word.replace(/^['’-]+/, "").replace(/['’-]+$/, "");

    if (/-$/.test(raw)) {
      /* "Kinder-" at a line break: the rest of the word follows in the same
         block, possibly in the next text node. */
      var tail = followingText(node, end, 24);
      var match = tail.match(/^\s*([A-Za-zÀ-ɏ]+)/);
      if (match && /\n|\r/.test(tail.slice(0, match.index + 1)) === false) {
        word = word + match[1];
      }
    }
    return word;
  }

  function followingText(node, offset, limit) {
    var out = (node.nodeValue || "").slice(offset, offset + limit);
    var block = blockOf(node);
    var walker = document.createTreeWalker(block || content, NodeFilter.SHOW_TEXT);
    var seen = false;
    while (walker.nextNode() && out.length < limit) {
      if (walker.currentNode === node) {
        seen = true;
        continue;
      }
      if (seen) out += walker.currentNode.nodeValue || "";
    }
    return out.slice(0, limit);
  }

  function blockOf(node) {
    var el = node.nodeType === 3 ? node.parentNode : node;
    while (el && el !== content) {
      if (el.hasAttribute && el.hasAttribute("data-i")) return el;
      el = el.parentNode;
    }
    return blocks.length ? blocks[0] : content;
  }

  function offsetWithinBlock(block, node, offsetInNode) {
    if (!block) return 0;
    var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    var total = 0;
    while (walker.nextNode()) {
      if (walker.currentNode === node) return total + offsetInNode;
      total += (walker.currentNode.nodeValue || "").length;
    }
    return total;
  }

  // --------------------------------------------------------------------------
  // sentences
  // --------------------------------------------------------------------------

  function looksLikeAbbreviation(text, dotIndex) {
    /* Ordinals and dates: "3. Mai", "1998." */
    if (dotIndex > 0 && /[0-9]/.test(text.charAt(dotIndex - 1))) return true;

    var before = text.slice(Math.max(0, dotIndex - 12), dotIndex).toLowerCase();
    var token = before.match(/[a-zäöüß.]+$/);
    if (!token) return false;
    var word = token[0];
    for (var i = 0; i < ABBREVIATIONS.length; i++) {
      if (word === ABBREVIATIONS[i] || word.slice(-(ABBREVIATIONS[i].length + 1)) ===
        "." + ABBREVIATIONS[i]) {
        return true;
      }
    }
    /* A single capital letter is an initial: "A. Schmidt". */
    var original = text.slice(Math.max(0, dotIndex - 2), dotIndex);
    return /(^|\s)[A-ZÄÖÜ]$/.test(original);
  }

  /**
   * The sentence the word sits in, clipped to 200 characters, used as the vocab
   * card's context (spec section 7). Splits on a sentence-ending mark followed
   * by whitespace, tolerating "z.B.", "ca.", "bzw." and ordinals like "3. Mai".
   */
  function sentenceAround(block, offset, word) {
    var text = (block ? block.textContent : "") || "";
    if (!text) return word;
    text = text.replace(/[­​]/g, "");

    var start = 0;
    var end = text.length;

    for (var i = offset - 1; i > 0; i--) {
      if (SENTENCE_END.test(text.charAt(i)) && /\s/.test(text.charAt(i + 1) || " ")) {
        if (!looksLikeAbbreviation(text, i)) {
          start = i + 1;
          break;
        }
      }
    }
    for (var j = offset; j < text.length; j++) {
      if (SENTENCE_END.test(text.charAt(j)) &&
        (j + 1 >= text.length || /\s/.test(text.charAt(j + 1)))) {
        if (!looksLikeAbbreviation(text, j)) {
          end = j + 1;
          break;
        }
      }
    }

    var sentence = text.slice(start, end).replace(/\s+/g, " ").trim();
    if (sentence.length > SENTENCE_LIMIT) {
      /* Keep the window around the tapped word rather than the first 200 chars. */
      var rel = Math.max(0, offset - start);
      var from = Math.max(0, Math.min(rel - SENTENCE_LIMIT / 2, sentence.length - SENTENCE_LIMIT));
      sentence = (from > 0 ? "…" : "") +
        sentence.substr(from, SENTENCE_LIMIT).trim() +
        (from + SENTENCE_LIMIT < sentence.length ? "…" : "");
    }
    return sentence || word;
  }

  // --------------------------------------------------------------------------
  // highlight
  // --------------------------------------------------------------------------

  function applyHighlight(range) {
    clearHighlight();
    try {
      var span = document.createElement("span");
      span.className = "lesen-hit";
      range.surroundContents(span);
      highlight = span;
    } catch (e) {
      /* surroundContents throws if the range crosses element boundaries; the
         lookup is more important than the highlight. */
      highlight = null;
    }
  }

  function clearHighlight() {
    if (!highlight || !highlight.parentNode) {
      highlight = null;
      return;
    }
    var parent = highlight.parentNode;
    while (highlight.firstChild) parent.insertBefore(highlight.firstChild, highlight);
    parent.removeChild(highlight);
    parent.normalize();
    highlight = null;
  }

  // --------------------------------------------------------------------------
  // position
  // --------------------------------------------------------------------------

  /** The first block visible in the current column. */
  function visibleBlock() {
    var width = pageWidth();
    var best = null;
    var bestKey = Infinity;
    for (var i = 0; i < blocks.length; i++) {
      var rects = blocks[i].getClientRects();
      for (var r = 0; r < rects.length; r++) {
        var rect = rects[r];
        if (rect.width === 0 && rect.height === 0) continue;
        if (rect.right <= 0 || rect.left >= width) continue;
        var key = Math.max(0, rect.top) * width + Math.max(0, rect.left);
        if (key < bestKey) {
          bestKey = key;
          best = blocks[i];
        }
      }
    }
    return best || blocks[0] || content;
  }

  /**
   * Character offset of the first character of [block] that is visible in the
   * current column. Binary search over Range rects: cheaper than walking every
   * character and accurate enough to land on the right sentence.
   */
  function visibleOffset(block) {
    var length = (block.textContent || "").length;
    if (length === 0) return 0;
    var lo = 0;
    var hi = length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (isBeforeViewport(block, mid)) lo = mid + 1;
      else hi = mid;
    }
    return Math.min(lo, length);
  }

  function isBeforeViewport(block, offset) {
    var rect = rectAt(block, offset);
    if (!rect) return false;
    return rect.left < 0 || (rect.left < 1 && rect.bottom < 0);
  }

  function rectAt(block, offset) {
    var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    var seen = 0;
    while (walker.nextNode()) {
      var node = walker.currentNode;
      var length = (node.nodeValue || "").length;
      if (seen + length >= offset) {
        var range = document.createRange();
        var local = Math.max(0, Math.min(offset - seen, length - 1));
        range.setStart(node, local);
        range.setEnd(node, Math.min(local + 1, length));
        var rects = range.getClientRects();
        return rects.length ? rects[0] : null;
      }
      seen += length;
    }
    return null;
  }

  function report(force) {
    var api = bridge();
    if (!api) return;
    var block = visibleBlock();
    var blockIndex = parseInt(block.getAttribute("data-i"), 10);
    if (isNaN(blockIndex)) blockIndex = 0;
    var offset = visibleOffset(block);
    var page = currentPage();
    if (!force && blockIndex === lastReport.block && offset === lastReport.offset &&
      page === lastReport.page) {
      return;
    }
    lastReport = { block: blockIndex, offset: offset, page: page };
    api.onPositionChanged(blockIndex, offset, page, pageCount);
  }

  var scrollTimer = null;

  function onScroll() {
    if (scrollTimer) clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () { report(false); }, 90);
  }

  // --------------------------------------------------------------------------
  // relayout
  // --------------------------------------------------------------------------

  function scheduleRelayout() {
    if (relayoutTimer) clearTimeout(relayoutTimer);
    relayoutTimer = setTimeout(relayout, 60);
  }

  /**
   * Re-paginate and restore by block index (spec section 12): a font size,
   * margin or orientation change moves every pixel, so the anchor has to be the
   * block the reader was on.
   */
  function relayout() {
    var anchor = { block: lastReport.block, offset: lastReport.offset };
    if (anchor.block < 0) {
      var block = visibleBlock();
      anchor.block = parseInt(block.getAttribute("data-i"), 10) || 0;
      anchor.offset = visibleOffset(block);
    }
    paginate();
    restore(anchor.block, anchor.offset);
    report(true);
  }

  /** Scrolls so that (blockIndex, charOffset) is in the visible column. */
  function restore(blockIndex, charOffset) {
    var block = content.querySelector('[data-i="' + blockIndex + '"]') ||
      blocks[Math.min(blockIndex, blocks.length - 1)] || content;
    if (!block) return 0;

    var rect = charOffset > 0 ? rectAt(block, charOffset) : null;
    if (!rect) {
      var rects = block.getClientRects();
      rect = rects.length ? rects[0] : null;
    }
    if (!rect) {
      block.scrollIntoView();
      return goToPage(currentPage(), false);
    }
    var absolute = rect.left + document.body.scrollLeft;
    return goToPage(Math.floor(absolute / pageWidth()), false);
  }

  // --------------------------------------------------------------------------
  // swipes past a chapter edge
  // --------------------------------------------------------------------------

  /**
   * At the first or last page the scroller has nowhere to go, so a swipe there
   * is reported to Kotlin, which loads the adjacent spine item (at page 0 going
   * forward, at its last page going back - spec section 6).
   */
  function installSwipeBoundaryDetector() {
    var startX = 0;
    var startLeft = 0;
    var tracking = false;

    document.body.addEventListener("touchstart", function (e) {
      if (!e.touches || e.touches.length !== 1) {
        tracking = false;
        return;
      }
      tracking = true;
      startX = e.touches[0].clientX;
      startLeft = document.body.scrollLeft;
    }, { passive: true });

    document.body.addEventListener("touchend", function (e) {
      if (!tracking) return;
      tracking = false;
      var touch = (e.changedTouches && e.changedTouches[0]) || null;
      if (!touch) return;
      var dx = touch.clientX - startX;
      if (Math.abs(dx) < SWIPE_MIN) return;
      if (Math.abs(document.body.scrollLeft - startLeft) > 4) return;  /* scroller moved */

      var api = bridge();
      if (!api) return;
      var page = currentPage();
      if (dx < 0 && page >= pageCount - 1) api.onTapEmpty("right");
      else if (dx > 0 && page <= 0) api.onTapEmpty("left");
    }, { passive: true });
  }

  // --------------------------------------------------------------------------
  // the API Kotlin calls
  // --------------------------------------------------------------------------

  window.Lesen = {
    /** Turns a page inside this chapter. Returns false at a chapter edge. */
    turnPage: function (delta) {
      var page = currentPage();
      var target = page + (delta > 0 ? 1 : -1);
      if (target < 0 || target > pageCount - 1) {
        report(true);
        return false;
      }
      goToPage(target, false);
      report(true);
      return true;
    },

    goToPage: function (page) {
      var landed = goToPage(page, false);
      report(true);
      return landed;
    },

    goToLastPage: function () {
      var landed = goToPage(pageCount - 1, false);
      report(true);
      return landed;
    },

    restore: function (blockIndex, charOffset) {
      var page = restore(blockIndex, charOffset);
      report(true);
      return page;
    },

    /** Jumps to an id inside this chapter (TOC entries with a fragment). */
    goToFragment: function (id) {
      if (!id) return false;
      var target = null;
      try {
        target = content.querySelector("#" + window.CSS.escape(id)) ||
          content.querySelector('[name="' + id + '"]');
      } catch (e) {
        target = document.getElementById(id);
      }
      if (!target) return false;
      var block = target.hasAttribute("data-i") ? target : blockOf(target);
      var index = parseInt(block.getAttribute("data-i"), 10) || 0;
      restore(index, 0);
      report(true);
      return true;
    },

    pageInfo: function () {
      return JSON.stringify({ page: currentPage(), pages: pageCount });
    },

    clearHighlight: function () {
      clearHighlight();
    },

    /** Long-press selection: hands the selected text to the Nachschlagen item. */
    sendSelection: function () {
      var selection = window.getSelection();
      var text = selection ? String(selection) : "";
      var api = bridge();
      if (api) api.onSelectionTranslate(text);
      return text;
    },

    clearSelection: function () {
      var selection = window.getSelection();
      if (selection && selection.removeAllRanges) selection.removeAllRanges();
    },

    /**
     * Applies the settings sheet. One setProperty per value: no stylesheet is
     * rebuilt and the chapter is never reloaded, so the reader keeps its place
     * (spec section 12).
     */
    applySettings: function (json) {
      var settings;
      try {
        settings = JSON.parse(json);
      } catch (e) {
        return false;
      }
      var root = document.documentElement;
      if (settings.fontSize) root.style.setProperty("--font-size", settings.fontSize + "px");
      if (settings.lineHeight) root.style.setProperty("--line-height", settings.lineHeight);
      if (settings.margin !== undefined) root.style.setProperty("--margin", settings.margin + "px");
      if (settings.fontFamily) root.style.setProperty("--font-family", settings.fontFamily);
      if (settings.bg) root.style.setProperty("--bg", settings.bg);
      if (settings.fg) root.style.setProperty("--fg", settings.fg);
      if (settings.link) root.style.setProperty("--link", settings.link);
      if (settings.hit) root.style.setProperty("--hit-bg", settings.hit);

      document.body.classList.toggle("lesen-force-colors", !!settings.forceColors);
      document.body.classList.toggle("lesen-dark", settings.theme === "dark");
      scheduleRelayout();
      return true;
    },

    /*
     * Text helpers, exposed so tools/readerjs/test_reader.js can check the
     * sentence splitter and the token cleanup without a device. Nothing in the
     * app calls these.
     */
    __text: {
      sentenceAround: sentenceAround,
      cleanToken: cleanToken,
      looksLikeAbbreviation: looksLikeAbbreviation,
      wordRegex: WORD
    },

    /** Diagnostics for the settings screen: how many pages this chapter has. */
    stats: function () {
      return JSON.stringify({
        blocks: blocks.length,
        pages: pageCount,
        width: pageWidth(),
        scrollWidth: document.body.scrollWidth
      });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", setup);
  } else {
    setup();
  }
  window.addEventListener("load", scheduleRelayout);
})();
