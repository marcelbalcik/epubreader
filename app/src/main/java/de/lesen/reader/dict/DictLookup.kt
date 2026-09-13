package de.lesen.reader.dict

/**
 * The lookup algorithm of spec 4.4: seven steps, first hit wins.
 *
 *  1. exact form_norm match
 *  2. sharp-s/ss and umlaut/digraph variants, both directions
 *  3. de-hyphenation (whole word, then right-hand part)
 *  4. participle ge-infix (auf-ge-standen -> aufstehen)
 *  5. suffix stripping, POS-plausible only, marked HEURISTIC
 *  6. right-to-left compound split with a Fugenelement at the seam
 *  7. prefix search as "did you mean"
 *
 * Pure Kotlin on purpose: no Android imports, so it is unit-testable on the
 * desktop JVM against a real dict.db (see app/src/test). Synchronous and
 * blocking - the caller runs it off the main thread (DictRepository).
 *
 * The desktop twin is tools/dictbuild/lookup_ref.py; the two are kept in step
 * deliberately, including the suffix list and the POS plausibility table.
 */
class DictLookup(
    private val source: DictSource,
    private val suffixRounds: Int = SUFFIX_ROUNDS,
) {

    fun lookup(surface: String): LookupResult {
        val n = Normalizer.norm(surface.trim())
        if (n.isEmpty()) return LookupResult.miss(surface)

        resolveSimple(n)?.let { (path, matched, entries) ->
            return LookupResult(surface, matched, entries, path)
        }

        split(n, depth = 0)?.let { (parts, entries) ->
            return LookupResult(surface, parts.last(), entries, LookupPath.COMPOUND, parts)
        }

        return LookupResult.miss(surface, source.prefixSearch(n, PREFIX_LIMIT))
    }

    /** Steps 1-5. Null when nothing resolved. */
    fun resolveSimple(n: String): Triple<LookupPath, String, List<DictEntry>>? {
        exact(n).takeIf { it.isNotEmpty() }?.let {
            return Triple(LookupPath.EXACT, n, it)
        }
        variant(n)?.let { return Triple(LookupPath.VARIANT, it.first, it.second) }
        dehyphenate(n)?.let { return Triple(LookupPath.VARIANT, it.first, it.second) }
        geInfix(n)?.let { return Triple(LookupPath.HEURISTIC, it.first, it.second) }
        stripSuffix(n)?.let { return Triple(LookupPath.HEURISTIC, it.first, it.second) }
        return null
    }

    // ---- step 1 -----------------------------------------------------------

    private fun exact(n: String): List<DictEntry> = source.entriesFor(n)

    // ---- step 2 -----------------------------------------------------------

    private fun variant(n: String): Pair<String, List<DictEntry>>? {
        for (cand in variantsOf(n)) {
            val hits = source.entriesFor(cand)
            if (hits.isNotEmpty()) return cand to hits
        }
        return null
    }

    // ---- step 3 -----------------------------------------------------------

    private fun dehyphenate(n: String): Pair<String, List<DictEntry>>? {
        if (!n.contains('-')) return null
        val candidates = listOf(n.replace("-", ""), n.substringAfterLast('-'))
        for (cand in candidates) {
            if (cand.isEmpty() || cand == n) continue
            val hits = source.entriesFor(cand)
            if (hits.isNotEmpty()) return cand to hits
        }
        return null
    }

    // ---- step 4 -----------------------------------------------------------

    private fun geInfix(n: String): Pair<String, List<DictEntry>>? {
        for (p in SEPARABLE_PREFIXES) {
            if (!n.startsWith(p + "ge") || n.length <= p.length + 2) continue
            val cand = p + n.substring(p.length + 2)
            val hits = source.entriesFor(cand)
            if (hits.isNotEmpty()) return cand to hits
        }
        return null
    }

    // ---- step 5 -----------------------------------------------------------

    /**
     * Suffix stripping (spec 4.4.5) with iterated rounds - see
     * docs/DECISIONS.md: one pass cannot reach "schoenste" -> "schoen", which
     * needs -e and then -st.
     *
     * The POS plausibility sets of every suffix removed along the way are
     * conjoined, so iteration cannot launder an implausible step: "gehens" must
     * not reach the verb "gehen" by stripping -es (nouns, adjectives) and then
     * -n. Without that, the spec's "don't strip -s and match a verb" rule would
     * hold for one round and quietly break on the second.
     */
    fun stripSuffix(n: String): Pair<String, List<DictEntry>>? {
        var current = listOf<Pair<String, Set<String>?>>(n to null)
        repeat(suffixRounds) {
            val next = ArrayList<Pair<String, Set<String>?>>()
            for ((s, sofar) in current) {
                for ((suffix, allowed) in SUFFIX_POS) {
                    if (!s.endsWith(suffix) || s.length - suffix.length < MIN_STEM) continue
                    val stem = s.substring(0, s.length - suffix.length)
                    val narrowed = if (sofar == null) allowed else sofar.intersect(allowed)
                    if (narrowed.isEmpty()) continue
                    val hits = source.entriesFor(stem).filter { it.pos in narrowed }
                    if (hits.isNotEmpty()) return stem to hits
                    next.add(stem to narrowed)
                }
            }
            if (next.isEmpty()) return null
            current = next
        }
        return null
    }

    // ---- step 6 -----------------------------------------------------------

    /**
     * Right-to-left longest-head match. The head must resolve through steps 1-5
     * and be at least [MIN_HEAD] characters; the modifier must resolve too,
     * possibly after a Fugenelement is consumed at the seam, and may itself be
     * split up to [MAX_COMPOUND_DEPTH] levels deep.
     *
     * Preference: longest head first, then the decomposition with fewer parts.
     */
    private fun split(n: String, depth: Int): Pair<List<String>, List<DictEntry>>? {
        var best: Split? = null
        var i = n.length - MIN_HEAD
        while (i >= MIN_MODIFIER) {
            val left = n.substring(0, i)
            val head = n.substring(i)
            val headHit = resolveSimple(head)
            if (headHit != null) {
                val modParts = resolveModifier(left, depth)
                if (modParts != null) {
                    val parts = modParts + (headHit.second)
                    val cand = Split(head.length, parts, headHit.third)
                    val incumbent = best
                    if (incumbent == null || cand.betterThan(incumbent)) best = cand
                }
            }
            i--
        }
        return best?.let { it.parts to it.entries }
    }

    private fun resolveModifier(left: String, depth: Int): List<String>? {
        if (left.length < MIN_MODIFIER) return null
        val candidates = ArrayList<String>(1 + FUGEN.size)
        candidates.add(left)
        for (fug in FUGEN) {
            if (left.endsWith(fug) && left.length - fug.length >= MIN_MODIFIER) {
                candidates.add(left.substring(0, left.length - fug.length))
            }
        }
        for (cand in candidates) {
            resolveSimple(cand)?.let { return listOf(it.second) }
        }
        if (depth < MAX_COMPOUND_DEPTH) {
            for (cand in candidates) {
                split(cand, depth + 1)?.let { return it.first }
            }
        }
        return null
    }

    private class Split(
        val headLength: Int,
        val parts: List<String>,
        val entries: List<DictEntry>,
    ) {
        fun betterThan(other: Split): Boolean = when {
            headLength != other.headLength -> headLength > other.headLength
            else -> parts.size < other.parts.size
        }
    }

    companion object {
        /** Iterated suffix stripping; see docs/DECISIONS.md. */
        const val SUFFIX_ROUNDS = 2
        const val MIN_HEAD = 4
        const val MIN_MODIFIER = 3
        const val MIN_STEM = 3
        const val MAX_COMPOUND_DEPTH = 2
        const val PREFIX_LIMIT = 15

        /** Spec 4.4.5, in this order, with the POS each suffix may produce. */
        val SUFFIX_POS: List<Pair<String, Set<String>>> = listOf(
            "en" to setOf("verb", "noun", "adj"),
            "e" to setOf("noun", "adj", "verb"),
            "er" to setOf("noun", "adj"),
            "es" to setOf("noun", "adj", "det", "pron"),
            "em" to setOf("adj", "det", "pron"),
            "n" to setOf("noun", "verb", "adj"),
            "s" to setOf("noun"),
            "et" to setOf("verb"),
            "st" to setOf("verb", "adj"),
            "te" to setOf("verb", "adj"),
            "ten" to setOf("verb", "adj"),
            "tet" to setOf("verb"),
            "ung" to setOf("noun", "verb"),
        )

        val SEPARABLE_PREFIXES = listOf(
            "ab", "an", "auf", "aus", "bei", "durch", "ein", "fest", "her",
            "hin", "los", "mit", "nach", "über", "um", "vor", "weg", "weiter",
            "zu", "zurück", "zusammen",
        )

        /** Longest first, so "es" is tried before "e" at the seam. */
        val FUGEN = listOf("es", "en", "er", "s", "n", "e")

        /** Spec 4.4.2. Both directions, so old orthography and ASCII both work. */
        fun variantsOf(n: String): List<String> {
            val out = ArrayList<String>(8)
            val seen = HashSet<String>()
            seen.add(n)
            fun add(x: String) {
                if (x.isNotEmpty() && seen.add(x)) out.add(x)
            }
            add(n.replace("ß", "ss"))
            add(n.replace("ss", "ß"))
            val folded = n.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            add(folded)
            add(folded.replace("ß", "ss"))
            val unfolded = n.replace("ae", "ä").replace("oe", "ö").replace("ue", "ü")
            add(unfolded)
            add(unfolded.replace("ss", "ß"))
            add(unfolded.replace("ß", "ss"))
            return out
        }
    }
}
