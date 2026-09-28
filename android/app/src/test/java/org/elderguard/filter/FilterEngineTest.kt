package org.elderguard.filter

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    private fun engine(lists: String, userBlock: List<String> = emptyList(), userAllow: List<String> = emptyList()): FilterEngine {
        val c = FilterParser.parse(lists)
        return FilterEngine(DomainSet.ofDomains(userAllow), DomainSet.ofDomains(userBlock),
            RuleSet.union(listOf(c.allow, FilterEngine.SAFETY_ALLOW_SET)), c.block)
    }

    @Test fun suffixHashesMatchWholeNameHashes() {
        val hs = DomainHash.suffixes("a.ads.example.com")
        assertEquals(listOf("a.ads.example.com", "ads.example.com", "example.com", "com").map { DomainHash.of(it) }, hs.toList())
    }

    @Test fun suffixRuleBlocksSubdomainsButNotParentsOrLookalikes() {
        val e = engine("||ads.example.com^")
        assertTrue(e.isBlocked("ads.example.com"))
        assertTrue(e.isBlocked("sub.ads.example.com"))
        assertTrue(e.isBlocked("SUB.Ads.Example.com."))
        assertFalse(e.isBlocked("example.com"))
        assertFalse(e.isBlocked("badads.example.com"))
        assertFalse(e.isBlocked("ads.example.com.evil.net"))
    }

    @Test fun hostsLinesAreExactOnly() {
        val e = engine("0.0.0.0 tracker.example.net\n127.0.0.1 a.example.org b.example.org")
        assertTrue(e.isBlocked("tracker.example.net"))
        assertFalse(e.isBlocked("x.tracker.example.net"))
        assertTrue(e.isBlocked("b.example.org"))
    }

    @Test fun plainDomainLinesAreSuffixRules() {
        val e = engine("ads.example.com")
        assertTrue(e.isBlocked("x.ads.example.com"))
    }

    @Test fun userAllowOnMoreSpecificNameBeatsUserBlockOnParent() {
        val e = engine("", userBlock = listOf("example.com"), userAllow = listOf("api.example.com"))
        assertEquals(FilterEngine.Match.USER_ALLOW, e.match("api.example.com"))
        assertEquals(FilterEngine.Match.USER_ALLOW, e.match("v1.api.example.com"))
        assertEquals(FilterEngine.Match.USER_BLOCK, e.match("x.example.com"))
    }

    @Test fun userRulesOverrideLists() {
        val e = engine("||ads.example.com^", userAllow = listOf("ads.example.com"))
        assertFalse(e.isBlocked("ads.example.com"))
        val e2 = engine("", userBlock = listOf("shop.example.com"))
        assertTrue(e2.isBlocked("m.shop.example.com"))
    }

    @Test fun listAllowExceptionBeatsListBlock() {
        val e = engine("||example.com^\n@@||cdn.example.com^")
        assertEquals(FilterEngine.Match.LIST_ALLOW, e.match("img.cdn.example.com"))
        assertEquals(FilterEngine.Match.LIST_BLOCK, e.match("ads.example.com"))
    }

    @Test fun safetyAllowlistSurvivesAnyList() {
        val e = engine("||google.com^\n0.0.0.0 mtalk.google.com")
        assertFalse(e.isBlocked("clients3.google.com"))
        assertFalse(e.isBlocked("mtalk.google.com"))
        assertTrue(e.isBlocked("ads.google.com"))
        // "gstatic.com" itself is too broad to be accepted from a list at all
        assertFalse(engine("||gstatic.com^").isBlocked("fonts.gstatic.com"))
    }

    @Test fun modifiersPathsWildcardsAndRegexAreSkippedNotWidened() {
        val c = FilterParser.parse("||x.com^\$third-party\n||y.com/ads\n||z*.com^\n/ads[0-9]+/\nexample.com##.banner\n||ok.com^\$important")
        val e = FilterEngine(listBlock = c.block)
        assertFalse(e.isBlocked("x.com"))
        assertFalse(e.isBlocked("y.com"))
        assertTrue(e.isBlocked("ok.com"))
        assertEquals(1, c.validLines)
        assertEquals(5, c.skippedLines)
    }

    @Test fun tooBroadAndInvalidNamesAreRejected() {
        for (bad in listOf("com", "com.tw", "googleapis.com", "1.2.3.4", "-a.com", "a..com", "a_b.c-.com", "ex ample.com", "中文.com"))
            assertNull(bad, FilterParser.normalize(bad))
        assertEquals("ads.example.com", FilterParser.normalize("*.Ads.Example.COM."))
        assertFalse(engine("0.0.0.0 localhost\n0.0.0.0 com.tw").isBlocked("www.com.tw"))
    }

    @Test fun parserHandlesCommentsAndBlankLines() {
        val c = FilterParser.parse("# title\n! adblock comment\n[Adblock Plus 2.0]\n\n0.0.0.0 a.example.com # trailing\n")
        assertEquals(1, c.validLines)
        assertTrue(FilterEngine(listBlock = c.block).isBlocked("a.example.com"))
    }

    @Test fun parserRejectsOversizedInput() {
        val big = "0.0.0.0 a.example.com\n".repeat(1000)
        try { FilterParser.parse(big.byteInputStream(), maxBytes = 1000); throw AssertionError("expected failure") }
        catch (e: FilterFormatException) { }
    }

    @Test fun storeRoundTripAtomicInstallAndRollback() {
        val dir = Files.createTempDirectory("eg-filters").toFile()
        try {
            val s = FilterStore(dir)
            val v1 = FilterParser.parse("||one.example^\n||v1.example.com^")
            val v2 = FilterParser.parse("||two.example.com^")
            s.install("l", v1)
            assertTrue(FilterEngine(listBlock = s.load("l")!!.block).isBlocked("v1.example.com"))
            s.install("l", v2)
            assertTrue(FilterEngine(listBlock = s.load("l")!!.block).isBlocked("two.example.com"))
            assertTrue(s.rollback("l"))
            val back = FilterEngine(listBlock = s.load("l")!!.block)
            assertTrue(back.isBlocked("v1.example.com"))
            assertFalse(back.isBlocked("two.example.com"))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptFileFallsBackToBackup() {
        val dir = Files.createTempDirectory("eg-filters").toFile()
        try {
            val s = FilterStore(dir)
            s.install("l", FilterParser.parse("||old.example.com^"))
            s.install("l", FilterParser.parse("||new.example.com^"))
            val bin = s.binFile("l")
            val bytes = bin.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte(); bin.writeBytes(bytes)
            val loaded = s.load("l")
            assertNotNull(loaded)
            assertTrue(FilterEngine(listBlock = loaded!!.block).isBlocked("old.example.com"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun updaterKeepsOldRulesOnBadDownloads() {
        val dir = Files.createTempDirectory("eg-upd").toFile()
        try {
            fun serve(body: String) = FilterUpdater(dir, open = { File(dir, "src.txt").apply { writeText(body) }.toURI().toURL().openConnection() })
            val ok = serve((1..50).joinToString("\n") { "0.0.0.0 host$it.example.com" }).fetch("file:///x", 0, null, requireHttps = false)
            assertNotNull(ok.compiled); assertNull(ok.error)
            val html = serve("<!DOCTYPE html><html>login</html>").fetch("file:///x", 0, null, requireHttps = false)
            assertNull(html.compiled); assertNotNull(html.error)
            val shrunk = serve((1..50).joinToString("\n") { "0.0.0.0 host$it.example.com" }).fetch("file:///x", 5000, null, requireHttps = false)
            assertNull(shrunk.compiled); assertNotNull(shrunk.error)
            val http = FilterUpdater(dir).fetch("http://example.com/list", 0, null)
            assertNotNull(http.error)
            val body = (1..20).joinToString("\n") { "||a$it.example.com^" }
            val same = serve(body).fetch("file:///x", 0, null, requireHttps = false)
            assertNotNull(same.sha256)
            val again = serve(body).fetch("file:///x", 0, same.sha256, requireHttps = false)
            assertTrue(again.unchanged)
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("download-") })
        } finally { dir.deleteRecursively() }
    }

    /** Performance guard: 1M rules must match without linear scans (well under 5 µs per lookup on a laptop JVM). */
    @Test fun oneMillionRulesLookupIsFast() {
        val raw = LongArray(1_000_000) { DomainHash.of("host$it.ads-example.com") }
        val e = FilterEngine(listBlock = RuleSet(DomainSet.EMPTY, DomainSet.of(raw)))
        assertTrue(e.isBlocked("x.host999999.ads-example.com"))
        val names = (0 until 200_000).map { "img$it.cdn.news-site.example.org" }
        repeat(2) { names.forEach { n -> e.isBlocked(n) } } // warm up
        val t0 = System.nanoTime()
        var blocked = 0
        names.forEach { if (e.isBlocked(it)) blocked++ }
        val perLookupUs = (System.nanoTime() - t0) / 1000.0 / names.size
        println("filter lookup: %.2f µs/lookup over 1M rules".format(perLookupUs))
        assertEquals(0, blocked)
        assertTrue("too slow: $perLookupUs µs", perLookupUs < 5.0)
    }

    @Test fun bundledListCompiles() {
        val f = File("src/main/assets/filters/stevenblack-hosts.txt.gz")
        if (!f.exists()) return
        val t0 = System.nanoTime()
        val c = java.util.zip.GZIPInputStream(f.inputStream()).use { FilterParser.parse(it) }
        println("bundled list: ${c.ruleCount} rules, ${c.skippedLines} skipped, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(c.ruleCount > 50_000)
        val e = FilterEngine(listBlock = c.block, listAllow = FilterEngine.SAFETY_ALLOW_SET)
        assertTrue(e.isBlocked("doubleclick.net") || e.isBlocked("ad.doubleclick.net"))
        for (ok in listOf("www.google.com", "line.me", "www.youtube.com", "shopee.tw", "connectivitycheck.gstatic.com", "mtalk.google.com"))
            assertFalse(ok, e.isBlocked(ok))
    }
}
