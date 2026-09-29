package org.elderguard.redirect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectStatsTest {
    @Test fun countsTapTimesDecisionsAndAnswersAndSurvivesARoundTrip() {
        val s = RedirectStats()
        s.record(1_000, Decision.ALLOW, listOf(ReasonCode.USER_CLICK, ReasonCode.SHOPPING_REDIRECT), 433, null)   // LINE-like
        s.record(2_000, Decision.ALLOW, listOf(ReasonCode.USER_CLICK, ReasonCode.SHOPPING_REDIRECT), 3_201, null) // WeChat-like
        s.record(3_000, Decision.ALLOW, listOf(ReasonCode.USER_CLICK, ReasonCode.BROWSER_REDIRECT), 1_200, null)
        s.record(4_000, Decision.ALLOW, listOf(ReasonCode.LAUNCHER_FLOW), null, null)                         // not a tap
        s.record(5_000, Decision.BLOCK, listOf(ReasonCode.NO_USER_INTERACTION, ReasonCode.SHOPPING_REDIRECT), null, "back")
        s.record(6_000, Decision.ASK, listOf(ReasonCode.INSTANT_LAUNCH), null, null)
        s.record(7_000, Decision.ALLOW, emptyList(), null, "user_open_anyway")                                // the answer line
        val r = RedirectStats.parse(s.toText())
        assertEquals(1_000, r.sinceMs)
        assertArrayEquals(intArrayOf(1, 0, 0, 1), r.tapShop)
        assertArrayEquals(intArrayOf(0, 1, 0, 0), r.tapOther)
        assertEquals(1, r.blocks); assertEquals(1, r.asks); assertEquals(1, r.tapless)
        assertEquals(1, r.openedAnyway)
    }
}
