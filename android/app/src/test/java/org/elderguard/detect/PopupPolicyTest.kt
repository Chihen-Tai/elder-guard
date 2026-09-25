package org.elderguard.detect

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PopupPolicyTest {
    private val ad = "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"
    private fun policy() = PopupPolicy(neverSource = setOf("org.elderguard", "com.launcher"), transparentPackages = setOf("com.ime"))

    @Test fun firstPopupIsNotBlocked_secondIs() {
        val p = policy()
        p.onWindow("com.launcher", "L", 0)
        val first = p.onWindow("bad.app", ad, 1_000)
        assertTrue(first.intrusion); assertFalse("first pop-up is only recorded", first.block)
        p.onWindow("com.launcher", "L", 2_000)          // our BACK or the user returns to the launcher
        val second = p.onWindow("bad.app", ad, 20_000)
        assertTrue("second pop-up within the window is closed", second.block)
        assertEquals("com.launcher", second.coveredPackage)
    }

    @Test fun popupsFarApartAreNotConsecutive() {
        val p = policy()
        p.onWindow("com.launcher", "L", 0)
        p.onWindow("bad.app", ad, 1_000)
        p.onWindow("com.launcher", "L", 2_000)
        assertFalse(p.onWindow("bad.app", ad, 1_000 + 31 * 60 * 1000L).block)
    }

    @Test fun inAppInterstitial_neverBlocked() {
        val p = policy()
        p.onWindow("com.launcher", "L", 0)
        p.onWindow("com.game", "com.game.MainActivity", 1_000) // user opened the game
        repeat(5) { i ->
            assertFalse(p.onWindow("com.game", "com.applovin.adview.AppLovinFullscreenActivity", 2_000L + i).intrusion)
            p.onWindow("com.game", "com.game.MainActivity", 3_000L + i)
        }
    }

    @Test fun tappingANotification_isAUserChoice() {
        val p = policy()
        p.onWindow("com.launcher", "L", 0)
        p.onWindow("com.android.systemui", "NotificationShade", 1_000)
        assertFalse(p.onWindow("bad.app", ad, 2_000).intrusion)
    }

    @Test fun keyboardDoesNotChangeTheForegroundApp() {
        val p = policy()
        p.onWindow("com.whatsapp", "W", 0)
        p.onWindow("com.ime", "InputMethod", 500)
        val d = p.onWindow("bad.app", ad, 1_000)
        assertEquals("com.whatsapp", d.coveredPackage)
    }

    @Test fun normalAppSwitching_neverIntrusion() {
        val p = policy()
        val seq = listOf("com.launcher", "jp.naver.line.android", "com.launcher", "com.android.chrome", "com.android.settings", "com.launcher")
        seq.forEachIndexed { i, pkg -> assertFalse(p.onWindow(pkg, "$pkg.Main", i * 1000L).intrusion) }
    }

    /** Replays the real vivo incident: the ad closer would have acted from the 2nd pop-up on, and never on other apps. */
    @Test fun realIncidentReplay() {
        val f = File("../../dataset-local/fixtures/vivo_2026-09-25_1830-1930.tsv")
        assumeTrue("local fixture not present", f.exists())
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val p = PopupPolicy(neverSource = setOf("org.elderguard", "com.android.launcher3"),
            userInitiatedSources = setOf("com.android.systemui"))
        val decisions = f.readLines().filter { it.isNotBlank() }.map { it.split("\t") }.filter { it[1] == "ACTIVITY_RESUMED" }
            .map { it[2] to p.onWindow(it[2], it.getOrNull(3)?.ifBlank { null }, fmt.parse(it[0])!!.time) }
        val blocked = decisions.filter { it.second.block }
        val intrusions = decisions.filter { it.second.intrusion }
        assertEquals(setOf("com.mjh.profitorabalancepointcalc"), intrusions.map { it.first }.toSet())
        assertEquals("all but the first pop-up are closed", intrusions.size - 1, blocked.size)
        assertTrue("many pop-ups closed, got ${blocked.size}", blocked.size >= 10)
    }

    /** Regression (emulator, 0.3.1): after our BACK no event arrives for the covered app; without setForeground the
     *  next pop-up of the same app was treated as in-app navigation and not closed. */
    @Test fun afterOurBack_nextPopupOfSameAppIsStillClosed() {
        val p = policy()
        p.onWindow("com.normal", "N", 0)
        p.onWindow("bad.app", ad, 1_000)                 // 1st: recorded
        p.onWindow("com.normal", "N", 2_000)             // user dismisses it
        assertTrue(p.onWindow("bad.app", ad, 20_000).block) // 2nd: closed by BACK; no event for com.normal follows
        p.setForeground("com.normal")                    // what the service now learns from the usage history
        assertTrue("3rd pop-up must be closed too", p.onWindow("bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity", 40_000).block)
    }

    /** Regression (emulator, 0.3.2): the 1st ad was left open and the 2nd ad of the same app came on top of it. */
    @Test fun secondAdOnTopOfUndismissedFirstAd_isANewPopup() {
        val p = policy()
        p.onWindow("com.normal", "N", 0)
        assertFalse(p.onWindow("bad.app", ad, 18_000).block)                   // 1st pop-up, left open
        val d = p.onWindow("bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity", 38_000)
        assertTrue("2nd pop-up 20 s later on top of the 1st is closed", d.block)
        assertEquals("com.normal", d.coveredPackage)
    }

    @Test fun chainedAdPagesWithinOnePopup_countOnce() {
        val p = policy()
        p.onWindow("com.normal", "N", 0)
        p.onWindow("bad.app", ad, 1_000)
        val chained = p.onWindow("bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity", 1_700)
        assertFalse("web ad -> video ad within 1 s is the same pop-up", chained.intrusion)
    }

    @Test fun userOpenedApp_ownAdsAfterAPause_neverCount() {
        val p = policy()
        p.onWindow("com.launcher", "L", 0)
        p.onWindow("com.game", "com.game.MainActivity", 1_000)          // user opened it: not an intruder
        assertFalse(p.onWindow("com.game", "com.applovin.adview.AppLovinFullscreenActivity", 60_000).intrusion)
    }

    /** Regression (emulator, 0.3.3): a heads-up notification (System UI window) between two pop-ups reset the state. */
    @Test fun headsUpNotificationBetweenPopups_doesNotReset() {
        val p = PopupPolicy(neverSource = setOf("com.launcher"), userInitiatedSources = emptySet(), transparentPackages = setOf("com.android.systemui"))
        p.onWindow("com.normal", "N", 0)
        assertFalse(p.onWindow("bad.app", ad, 18_000).block)
        p.onWindow("com.android.systemui", "HeadsUp", 18_500)
        assertTrue(p.onWindow("bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity", 38_000).block)
    }

    @Test fun seed_deduplicatesTheSamePopupLoggedTwice() {
        val p = policy()
        p.seed("bad.app", 10_000); p.seed("bad.app", 10_400)   // same pop-up logged by two monitors
        p.onWindow("com.normal", "N", 11_000)
        val d = p.onWindow("bad.app", ad, 20_000)
        assertEquals(2, d.count)
    }

    /** 0.5.0 emulator field log: the adware's own overlay (TextView) came 0.1 s before its 2nd ad and hid it. */
    @Test fun overlayWindowJustBeforeSecondAd_doesNotHideIt() {
        val p = policy()
        p.onWindow("com.normal", "com.normal.Main", 0)
        assertFalse(p.onWindow("bad.app", ad, 56_152).block)                 // 1st pop-up: recorded only
        p.onWindow("bad.app", ad, 56_218)                                      // duplicate event of the same page
        assertFalse(p.onWindow("bad.app", "android.widget.TextView", 76_129).intrusion) // overlay: not an ad page
        val second = p.onWindow("bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity", 76_227)
        assertTrue("2nd ad over the undismissed 1st is closed", second.block)
        assertEquals(2, second.count)
        assertEquals("com.normal", second.coveredPackage)
    }

    /** vivo 0.5.0: alert -> guard -> Back landed on the app's own ad page, 0.3 s later. Not a pop-up. */
    @Test fun backOutOfGuardOntoAnOpenAd_isNotAPopup() {
        val exit = 42_000L
        val p = PopupPolicy(neverSource = setOf("org.elderguard", "com.launcher"), userJustLeftGuard = { t -> IntrusionDetector.isReturnAfterExit(listOf(exit), t) })
        p.onWindow("bad.app", "bad.app.Splash", 30_000)                  // user opened the app
        p.onWindow("bad.app", ad, 38_000)                                // its own ad (in-app)
        p.onWindow("org.elderguard", "org.elderguard.MainActivity", 38_500) // tapped the guard's alert
        assertFalse(p.onWindow("bad.app", ad, 42_300).intrusion)
        // a real pop-up later over the launcher still counts
        p.onWindow("com.launcher", "L", 60_000)
        assertTrue(p.onWindow("bad.app", ad, 80_000).intrusion)
    }

    @Test fun adOverGuardWithoutUserExit_isStillAPopup() {
        val p = PopupPolicy(neverSource = setOf("org.elderguard"), userJustLeftGuard = { false })
        p.onWindow("org.elderguard", "org.elderguard.MainActivity", 0)
        assertTrue(p.onWindow("bad.app", ad, 5_000).intrusion)
    }
}
