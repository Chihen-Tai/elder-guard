package org.elderguard.detect

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class IntrusionDetectorTest {
    private val ignored = setOf("org.elderguard", "com.android.systemui", "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller")

    /**
     * Real events from the vivo test phone, 2026-09-25 18:30-19:30 (local data, not in the repo).
     * At 19:05:51 "Fast PDF Reader" (package com.mjh.profitorabalancepointcalc) was installed from a YouTube ad;
     * it was uninstalled at 19:09:43 after repeated full-screen ads.
     */
    @Test fun realIncident_findsSource_andNoFalsePositives() {
        val f = File("../../dataset-local/fixtures/vivo_2026-09-25_1830-1930.tsv")
        assumeTrue("local fixture not present", f.exists())
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val events = f.readLines().filter { it.isNotBlank() }.map { it.split("\t") }
            .filter { it[1] == "ACTIVITY_RESUMED" }
            .map { ScreenEvent(fmt.parse(it[0])!!.time, it[2], it.getOrNull(3)?.ifBlank { null }) }
        val d = IntrusionDetector(ignored)
        val summaries = d.summarize(d.detect(events))
        summaries.forEach { println("${it.packageName} x${it.count} ${it.level} over=${it.coveredPackages}") }
        assertEquals("exactly one source app", listOf("com.mjh.profitorabalancepointcalc"), summaries.map { it.packageName })
        val s = summaries.single()
        assertEquals(IntrusionSummary.Level.CONFIRMED, s.level)
        assertTrue("repeated many times, got ${s.count}", s.count >= 5)
        assertTrue("covered the launcher", "com.android.launcher3" in s.coveredPackages)
        // At 19:09:24 the ad covered the system uninstall confirmation while the user was removing the app.
        assertTrue("covered the uninstall dialog", "com.google.android.packageinstaller" in s.coveredPackages)
        assertTrue("covered the Play Store", "com.android.vending" in s.coveredPackages)
    }

    @Test fun inAppInterstitial_isNotAnIntrusion() {
        val ev = listOf(
            ScreenEvent(1_000, "com.launcher", "L"),
            ScreenEvent(2_000, "com.game", "com.game.MainActivity"),          // user opened the game
            ScreenEvent(3_000, "com.game", "com.applovin.adview.AppLovinFullscreenActivity"), // ad inside the game
        )
        assertTrue(IntrusionDetector(ignored).detect(ev).isEmpty())
    }

    @Test fun notificationTapIntoNormalApp_isNotAnIntrusion() {
        val ev = listOf(ScreenEvent(1_000, "com.launcher", "L"), ScreenEvent(2_000, "jp.naver.line.android", "jp.naver.line.android.ChatActivity"))
        assertTrue(IntrusionDetector(ignored).detect(ev).isEmpty())
    }

    @Test fun hiddenIconAppOverOtherApp_isAnIntrusion() {
        val ev = listOf(ScreenEvent(1_000, "com.whatsapp", "W"), ScreenEvent(2_000, "com.hidden.cleaner", "a.b.C"),
            ScreenEvent(9_000, "com.whatsapp", "W"), ScreenEvent(9_500, "com.hidden.cleaner", "a.b.C"))
        val d = IntrusionDetector(ignored, hasHiddenIcon = { it == "com.hidden.cleaner" })
        val s = d.summarize(d.detect(ev)).single()
        assertEquals(2, s.count)
        assertEquals(IntrusionSummary.Level.CONFIRMED, s.level)
        assertEquals(Intrusion.Kind.HiddenAppOverOtherApp, s.examples.first().kind)
    }

    @Test fun singleAdOverOtherApp_isOnlySuspected() {
        val ev = listOf(ScreenEvent(1_000, "com.launcher", "L"), ScreenEvent(2_000, "x.y", "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"))
        val d = IntrusionDetector(ignored)
        assertEquals(IntrusionSummary.Level.SUSPECTED, d.summarize(d.detect(ev)).single().level)
    }

    /** Same re-pop rule as PopupPolicy (emulator 0.3.4: a 2nd ad on top of the undismissed 1st was missed here). */
    @Test fun repopOnTopOfOwnUndismissedAd_isCounted() {
        val ev = listOf(ScreenEvent(0, "com.normal", "N"),
            ScreenEvent(18_000, "bad.app", "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"),
            ScreenEvent(18_700, "bad.app", "com.bytedance.sdk.openadsdk.activity.single.TTFullScreenExpressVideoActivity"), // chained: same pop-up
            ScreenEvent(38_000, "bad.app", "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"))                     // popped up again
        val list = IntrusionDetector(ignored).detect(ev)
        assertEquals(listOf(18_000L, 38_000L), list.map { it.timeMs })
        assertEquals("com.normal", list.last().coveredPackage)
    }

    @Test fun launchingTheAppsOwnStartScreen_isNotAHiddenAppPopup() {
        val ev = listOf(ScreenEvent(0, "com.launcher", "L"), ScreenEvent(1_000, "com.hidden", "com.hidden.Main"),
            ScreenEvent(5_000, "com.launcher", "L"), ScreenEvent(9_000, "com.hidden", "com.hidden.Popup"))
        val d = IntrusionDetector(ignored, hasHiddenIcon = { it == "com.hidden" }, isLauncherEntry = { p, c -> p == "com.hidden" && c == "com.hidden.Main" })
        assertEquals(listOf(9_000L), d.detect(ev).map { it.timeMs })
    }

    @Test fun pageRightAfterUserClosedTheGuard_isAReturn() {
        val ad = "com.google.android.gms.ads.AdActivity"
        val ev = listOf(
            ScreenEvent(1_000, "org.elderguard", "org.elderguard.MainActivity"),
            ScreenEvent(4_300, "bad.app", ad),                 // 0.3 s after Back closed the guard
            ScreenEvent(9_000, "com.launcher", "L"),
            ScreenEvent(30_000, "bad.app", ad),                // real pop-up over the launcher
        )
        val d = IntrusionDetector(setOf("org.elderguard", "com.launcher"), userExitTimes = listOf(4_000L)).detect(ev)
        org.junit.Assert.assertEquals(listOf(30_000L), d.map { it.timeMs })
    }

    /** vivo 0.5.0 usage events: tapping the icon of an app whose reward-video ad was left open. Not a pop-up. */
    @Test fun launcherTapResumesOldAdThenOwnScreen_isNotAPopup() {
        val l = "com.android.launcher3"
        val ev = listOf(
            ScreenEvent(0, l, "com.bbk.launcher2.Launcher"),
            ScreenEvent(10_000, "com.tidal.cleaner", "com.mbridge.msdk.reward.player.MBRewardVideoActivity"),
            ScreenEvent(10_120, "com.tidal.cleaner", "com.tidal.cleaner.ui.TrashActivity"),
        )
        assertTrue(IntrusionDetector(setOf(l), homePackages = setOf(l)).detect(ev).isEmpty())
    }

    @Test fun adOverLauncherThatStaysOnTop_isStillAPopup() {
        val l = "com.android.launcher3"
        val ev = listOf(
            ScreenEvent(0, l, "L"),
            ScreenEvent(10_000, "bad.app", "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"),
            ScreenEvent(10_060, "bad.app", "com.bytedance.sdk.openadsdk.activity.TTFullWebActivity"), // same page again
            ScreenEvent(30_000, l, "L"),
        )
        org.junit.Assert.assertEquals(1, IntrusionDetector(setOf(l), homePackages = setOf(l)).detect(ev).size)
    }

    private fun fixture(name: String): List<ScreenEvent>? {
        val f = File("../../dataset-local/fixtures/$name")
        if (!f.exists()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return f.readLines().filter { it.isNotBlank() }.map { it.split("\t") }.filter { it[1] == "ACTIVITY_RESUMED" }
            .map { ScreenEvent(fmt.parse(it[0])!!.time, it[2], it.getOrNull(3)?.ifBlank { null }) }
    }

    /** The launcher-tap rule must not hide the real incident. */
    @Test fun realIncident_stillFound_withLauncherTapRule() {
        val ev = fixture("vivo_2026-09-25_1830-1930.tsv"); assumeTrue(ev != null)
        val d = IntrusionDetector(ignored, homePackages = setOf("com.android.launcher3"))
        val s = d.summarize(d.detect(ev!!))
        s.forEach { println("incident+rule: ${it.packageName} x${it.count} over=${it.coveredPackages}") }
        assertEquals(listOf("com.mjh.profitorabalancepointcalc"), s.map { it.packageName })
        assertTrue("covered the launcher", "com.android.launcher3" in s.single().coveredPackages)
    }

    /** vivo 22:25-22:37: several ad apps installed and opened by the user; two real pop-ups over Google Play. */
    @Test fun fieldEvening_launcherTapsNotCounted_popupsOverPlayStillFound() {
        val ev = fixture("vivo_2026-09-25_2225-2237.tsv"); assumeTrue(ev != null)
        val d = IntrusionDetector(ignored, homePackages = setOf("com.android.launcher3"))
        val list = d.detect(ev!!)
        list.forEach { println("evening: ${SimpleDateFormat("HH:mm:ss").format(it.timeMs)} ${it.packageName} ${it.className?.substringAfterLast('.')} over=${it.coveredPackage}") }
        val overPlay = list.filter { it.packageName == "com.smarttools.pdfreader.editor.viewer" && it.coveredPackage == "com.android.vending" }
        assertTrue("both pop-ups over Google Play are kept, got ${overPlay.size}", overPlay.size >= 2)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val times = list.map { fmt.format(it.timeMs) }
        assertTrue("22:34:09 launcher tap is not a pop-up", "22:34:09" !in times)
        assertTrue("22:35:31 launcher tap is not a pop-up", "22:35:31" !in times)
        assertTrue("22:33:59 ad over its own permission request is in-app", "22:33:59" !in times)
    }
}
