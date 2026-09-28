package org.elderguard.filter

/**
 * Domain matching without Strings in memory: every rule is stored as a 64-bit hash in a sorted LongArray.
 * 1,000,000 rules = 8 MB and a lookup is one binary search per label (a 4-label name = 4 searches).
 *
 * The hash runs over the name from the LAST character to the first, so one pass over "a.ads.example.com" yields the
 * hashes of "com", "example.com", "ads.example.com" and "a.ads.example.com" (every suffix at a label boundary).
 * A false positive needs a 64-bit collision: about n / 2^64 per lookup, i.e. never in practice.
 */
object DomainHash {
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    /** Hash of a whole, already-normalized domain (lowercase ASCII, no trailing dot). */
    fun of(domain: String): Long {
        var h = FNV_OFFSET
        for (i in domain.length - 1 downTo 0) h = (h xor domain[i].code.toLong()) * FNV_PRIME
        return mix(h)
    }

    /**
     * Hashes of every suffix of [domain] at a label boundary, MOST specific first:
     * "a.ads.example.com" -> [h(a.ads.example.com), h(ads.example.com), h(example.com), h(com)].
     */
    fun suffixes(domain: String): LongArray {
        var labels = 1
        for (c in domain) if (c == '.') labels++
        val out = LongArray(labels)
        var h = FNV_OFFSET
        var k = labels - 1
        for (i in domain.length - 1 downTo 0) {
            val c = domain[i]
            if (c == '.') out[k--] = mix(h)
            h = (h xor c.code.toLong()) * FNV_PRIME
        }
        out[0] = mix(h)
        return out
    }

    /** murmur3 fmix64: spreads FNV's weak low bits so sorted order is uniform. */
    fun mix(x: Long): Long {
        var k = x
        k = k xor (k ushr 33); k *= -0xae502812aa7333L
        k = k xor (k ushr 33); k *= -0x3b314601e57a13adL
        return k xor (k ushr 33)
    }
}

/** An immutable set of domain hashes (sorted, no duplicates). */
class DomainSet private constructor(val hashes: LongArray) {
    val size get() = hashes.size

    operator fun contains(h: Long) = hashes.isNotEmpty() && java.util.Arrays.binarySearch(hashes, h) >= 0

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        /** Takes ownership of [raw] (sorted in place). */
        fun of(raw: LongArray, n: Int = raw.size): DomainSet {
            if (n == 0) return EMPTY
            java.util.Arrays.sort(raw, 0, n)
            var w = 1
            for (r in 1 until n) if (raw[r] != raw[w - 1]) raw[w++] = raw[r]
            return DomainSet(if (w == raw.size) raw else raw.copyOf(w))
        }

        fun ofDomains(domains: Collection<String>) = of(domains.map { DomainHash.of(it) }.toLongArray())

        /** Union of already-built sets (used to merge all enabled lists into one engine). */
        fun union(sets: List<DomainSet>): DomainSet {
            val total = sets.sumOf { it.size }
            if (total == 0) return EMPTY
            val all = LongArray(total)
            var p = 0
            for (s in sets) { System.arraycopy(s.hashes, 0, all, p, s.size); p += s.size }
            return of(all)
        }
    }
}

/**
 * The two kinds of rule:
 *  - suffix: "ads.example.com" also blocks "x.ads.example.com" (adblock `||ads.example.com^`, plain domain lines,
 *    and every rule the user types);
 *  - exact: only that name (hosts-file lines such as `0.0.0.0 ads.example.com`, which are exact by definition —
 *    turning them into suffix rules would over-block, e.g. a listed "s3.amazonaws.com" would take down every bucket).
 */
class RuleSet(val exact: DomainSet = DomainSet.EMPTY, val suffix: DomainSet = DomainSet.EMPTY) {
    val size get() = exact.size + suffix.size
    val isEmpty get() = size == 0

    companion object {
        val EMPTY = RuleSet()
        fun union(sets: List<RuleSet>) = RuleSet(DomainSet.union(sets.map { it.exact }), DomainSet.union(sets.map { it.suffix }))
    }
}
