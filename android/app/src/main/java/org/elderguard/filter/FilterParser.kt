package org.elderguard.filter

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** What one filter list compiled to. Counts are for the UI and for sanity checks during updates. */
class CompiledList(
    val block: RuleSet,
    val allow: RuleSet,
    val validLines: Int,
    /** Lines that looked like rules but could not be used for DNS (paths, wildcards, $options, bad names). */
    val skippedLines: Int,
) {
    val ruleCount get() = block.size + allow.size
}

class FilterFormatException(msg: String) : Exception(msg)

/**
 * Reads the three list formats DNS blockers use, streaming (a 1M-line list never becomes a List<String>):
 *  - hosts:     `0.0.0.0 ads.example.com`  / `127.0.0.1 a.com b.com`       -> exact rules
 *  - domains:   `ads.example.com`                                        -> suffix rules
 *  - adblock:   `||ads.example.com^`, `@@||ok.example.com^` (allow)       -> suffix rules
 * Adblock rules with a path, a wildcard, a regex or a `$modifier` (other than `$important`) are SKIPPED, never
 * widened into a global block (BlockAds cut everything after `^`, so `||x.com^$third-party` blocked all of x.com).
 */
object FilterParser {
    /** A name a list must never block: blocking any of these would take down large parts of the web or the phone. */
    private val TOO_BROAD = setOf(
        "co.uk", "com.tw", "com.cn", "com.hk", "net.cn", "org.cn", "gov.tw", "edu.tw", "org.tw", "net.tw", "idv.tw",
        "co.jp", "ne.jp", "or.jp", "com.au", "co.kr", "com.sg", "com.my", "com.br", "co.in",
        "blogspot.com", "github.io", "appspot.com", "cloudfront.net", "amazonaws.com", "azurewebsites.net",
        "googleapis.com", "googleusercontent.com", "gstatic.com", "akamaized.net", "akamaihd.net", "cloudflare.com",
    )
    private val HOSTS_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "::0", "0:0:0:0:0:0:0:0")
    private val HOSTS_SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0")

    /**
     * Returns the normalized name, or null when [raw] is not a usable host name.
     * Normalization: trim, lowercase, drop one trailing dot and a leading "*." (same meaning as a suffix rule).
     */
    fun normalize(raw: String): String? {
        var d = raw.trim().lowercase()
        if (d.endsWith('.')) d = d.dropLast(1)
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.length !in 3..253 || '.' !in d) return null
        var labelLen = 0
        var prev = '.'
        for (c in d) {
            when {
                c == '.' -> { if (prev == '.' || prev == '-' || labelLen == 0) return null; labelLen = 0 }
                c in 'a'..'z' || c in '0'..'9' || c == '_' -> labelLen++
                c == '-' -> { if (labelLen == 0) return null; labelLen++ }
                else -> return null
            }
            if (labelLen > 63) return null
            prev = c
        }
        if (prev == '-' || prev == '.') return null
        val tld = d.substringAfterLast('.')
        if (tld.all { it.isDigit() }) return null // an IP address, not a name
        if (d in TOO_BROAD) return null
        return d
    }

    private enum class Kind { BLOCK_EXACT, BLOCK_SUFFIX, ALLOW_SUFFIX }

    /** One line -> (kind, name) or null; a non-null return with null name means "a rule we cannot use". */
    private fun parseLine(line0: String, emit: (Kind, String) -> Unit): Boolean? {
        var line = line0.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return null
        val hash = line.indexOf(" #")
        if (hash >= 0) line = line.substring(0, hash).trim()
        val tab = line.indexOf('\t')
        if (tab >= 0) line = line.replace('\t', ' ')

        var allow = false
        if (line.startsWith("@@")) { allow = true; line = line.substring(2) }
        if (line.startsWith("||")) {
            val body = line.substring(2)
            val dollar = body.indexOf('$')
            val opts = if (dollar >= 0) body.substring(dollar + 1).lowercase() else ""
            if (opts.isNotEmpty() && opts.split(',').any { it != "important" }) return false
            val name = (if (dollar >= 0) body.substring(0, dollar) else body).removeSuffix("^").removeSuffix("|")
            if (name.any { it == '/' || it == '*' || it == '^' || it == '|' || it == '?' || it == ':' }) return false
            val d = normalize(name) ?: return false
            emit(if (allow) Kind.ALLOW_SUFFIX else Kind.BLOCK_SUFFIX, d)
            return true
        }
        if (allow) return false // "@@" without "||": not a DNS rule
        if (line.startsWith('|') || line.startsWith('/') || line.contains("##") || line.contains("#@#")) return false
        val sp = line.indexOf(' ')
        if (sp < 0) {
            val d = normalize(line) ?: return false
            emit(Kind.BLOCK_SUFFIX, d)
            return true
        }
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts[0] !in HOSTS_IPS) return false // a hosts line that redirects somewhere real is not a block rule
        var any = false
        for (p in parts.drop(1)) {
            if (p in HOSTS_SKIP) continue
            val d = normalize(p) ?: continue
            emit(Kind.BLOCK_EXACT, d); any = true
        }
        return any
    }

    /**
     * Compiles a list. Throws [FilterFormatException] when more than [maxBytes] are read (a truncated or runaway
     * download must not be half-used) — the caller keeps the previous version.
     */
    fun parse(input: InputStream, maxBytes: Long = Long.MAX_VALUE, maxRules: Int = MAX_RULES): CompiledList {
        val counting = CountingStream(input, maxBytes)
        val r = BufferedReader(InputStreamReader(counting, Charsets.UTF_8), 64 * 1024)
        val be = LongBuilder(); val bs = LongBuilder(); val a = LongBuilder()
        var valid = 0; var skipped = 0
        while (true) {
            val line = r.readLine() ?: break
            when (parseLine(line) { k, d ->
                val h = DomainHash.of(d)
                when (k) { Kind.BLOCK_EXACT -> be.add(h); Kind.BLOCK_SUFFIX -> bs.add(h); Kind.ALLOW_SUFFIX -> a.add(h) }
            }) { true -> valid++; false -> skipped++; null -> {} }
            if (valid > maxRules) throw FilterFormatException("more than $maxRules rules")
        }
        return CompiledList(RuleSet(be.build(), bs.build()), RuleSet(DomainSet.EMPTY, a.build()), valid, skipped)
    }

    fun parse(text: String) = parse(text.byteInputStream())

    /** One list may hold at most this many rules (bounds memory before anything is kept). */
    const val MAX_RULES = 2_000_000

    private class LongBuilder {
        private var a = LongArray(1024)
        private var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun build() = DomainSet.of(a, n)
    }

    private class CountingStream(private val s: InputStream, private val max: Long) : InputStream() {
        private var n = 0L
        private fun count(k: Int): Int { if (k > 0) { n += k; if (n > max) throw FilterFormatException("list larger than $max bytes") }; return k }
        override fun read(): Int = s.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(s.read(b, off, len))
        override fun close() = s.close()
    }
}
