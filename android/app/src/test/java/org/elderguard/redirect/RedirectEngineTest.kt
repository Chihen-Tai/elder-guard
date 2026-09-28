package org.elderguard.redirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The must-pass scenarios A–K (and more), replayed as event sequences with realistic timing. */
class RedirectEngineTest {
    private val WECHAT = "com.tencent.mm"
    private val LINE = "jp.naver.line.android"
    private val BILI = "tv.danmaku.bili"
    private val DOUYIN = "com.ss.android.ugc.aweme"
    private val YT = "com.google.android.youtube"
    private val GAME = "com.example.puzzlegame"
    private val NEWS = "com.example.news"
    private val SHOPEE = "com.shopee.tw"
    private val TAOBAO = "com.taobao.taobao"
    private val PLAY = "com.android.vending"
    private val CHROME = "com.android.chrome"
    private val GALLERY = "com.example.gallery"
    private val BANK = "com.cathaybk.mymobibank.android"
    private val HOME = "com.google.android.apps.nexuslauncher"

    private var now = 1_000_000L
    private val rules = HashMap<String, RedirectRule>()
    private val grants = HashSet<String>()
    private val notified = HashSet<String>()
    private var protected = setOf(WECHAT, LINE, GAME, NEWS, GALLERY, BILI)
    private lateinit var e: RedirectEngine

    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in protected
        override fun isAdPage(cls: String?) = cls?.startsWith("com.applovin.") == true || cls?.startsWith("com.bytedance.sdk.openadsdk.") == true
        override fun rule(source: String, target: String) = rules["$source>$target"]?.takeIf { it.active(now) }
        override fun consumeGrant(source: String, target: String) = grants.remove("$source>$target")
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = pkg in notified
    }

    @Before fun setUp() {
        val sys = SystemFlowClassifier(
            launchers = setOf(HOME), imes = setOf("com.google.android.inputmethod.latin"),
            systemTargets = SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard", browsers = setOf(CHROME),
        )
        e = RedirectEngine(sys, env)
    }

    private fun win(pkg: String, cls: String? = "$pkg.MainActivity", dt: Long = 0): Pair<Transition, Verdict>? { now += dt; return e.onWindow(pkg, cls, now) }
    private fun click(pkg: String, cls: String? = "android.widget.TextView", dt: Long = 0) { now += dt; e.onClick(pkg, cls, now) }
    private fun open(pkg: String, dt: Long = 0) = win(pkg, dt = dt)!!.second

    /** The user has been using [pkg] for a while and taps things (so click events are known to work there). */
    private fun using(pkg: String) { win(pkg, dt = 5_000); click(pkg, dt = 20_000); now += 10_000 }

    @Test fun caseA_wechatUserClicksBilibili() {
        using(WECHAT)
        click(WECHAT, "android.widget.LinearLayout")
        val v = open(BILI, dt = 300)
        assertEquals(Decision.ALLOW, v.decision)
        assertEquals(ReasonCode.USER_CLICK, v.primary)
    }

    @Test fun caseB_wechatUserClicksDouyin() {
        using(WECHAT)
        click(WECHAT)
        assertEquals(Decision.ALLOW, open(DOUYIN, dt = 900).decision)
    }

    @Test fun caseC_lineToYoutubeEvenWithColdStart() {
        using(LINE)
        click(LINE)
        assertEquals(Decision.ALLOW, open(YT, dt = 2_800).decision)
    }

    @Test fun caseD_gameNoInteractionToShopee_blocked() {
        using(GAME)
        now += 60_000 // playing, no taps reported for a minute
        val v = open(SHOPEE)
        assertEquals(Decision.BLOCK, v.decision)
        assertEquals(RiskLevel.HIGH, v.risk)
        assertTrue(ReasonCode.NO_USER_INTERACTION in v.reasons)
        assertTrue(ReasonCode.SHOPPING_REDIRECT in v.reasons)
    }

    @Test fun caseD2_newsAdAutoOpensPlayStore_blocked() {
        using(NEWS)
        now += 30_000
        val v = open(PLAY)
        assertEquals(Decision.BLOCK, v.decision)
        assertTrue(ReasonCode.APP_STORE_REDIRECT in v.reasons)
    }

    @Test fun caseE_adCloseButtonClickThrough_isNotTrusted() {
        using(GAME)
        win(GAME, "com.applovin.adview.AppLovinFullscreenActivity", dt = 1_000) // full-screen ad inside the game
        click(GAME, "android.widget.ImageButton", dt = 5_000)                  // the user taps "X"
        val v = open(SHOPEE, dt = 400)
        assertTrue("was ${v.decision}", v.decision == Decision.ASK || v.decision == Decision.BLOCK)
        assertTrue(ReasonCode.AD_PAGE_REDIRECT in v.reasons)
    }

    @Test fun caseE2_adPageWithoutClick_blocked() {
        using(GAME)
        win(GAME, "com.bytedance.sdk.openadsdk.activity.TTFullScreenExpressVideoActivity", dt = 1_000)
        assertEquals(Decision.BLOCK, open(TAOBAO, dt = 6_000).decision)
    }

    @Test fun caseF_shakeToShopping_highRisk() {
        using(GAME)
        now += 20_000
        e.onShake(now)
        val v = open(TAOBAO, dt = 600)
        assertEquals(Decision.BLOCK, v.decision)
        assertEquals(RiskLevel.HIGH, v.risk)
        assertEquals(ReasonCode.SHAKE_REDIRECT, v.primary)
    }

    @Test fun shakeAloneWithClickOnNormalButton_isNotBlocked() {
        using(LINE)
        e.onShake(now)
        click(LINE, dt = 200)
        assertEquals(Decision.ALLOW, open(YT, dt = 300).decision)
    }

    @Test fun caseG_shareSheetToWechat() {
        using(GALLERY)
        click(GALLERY)
        assertEquals(ReasonCode.SYSTEM_FLOW, open("com.android.intentresolver", dt = 200).primary)
        val v = open(WECHAT, dt = 1_500)
        assertEquals(Decision.ALLOW, v.decision)
        assertEquals(ReasonCode.SHARE_FLOW, v.primary)
    }

    @Test fun caseH_oauthRoundTrip() {
        using(GAME)
        click(GAME, "android.widget.Button")
        assertEquals(Decision.ALLOW, open(CHROME, dt = 500).decision)           // "Sign in with Google" opens the browser
        protected = protected + CHROME                                           // even if the browser itself is protected
        now += 40_000                                                           // the user types a password: no clicks we see
        val back = open(GAME)
        assertEquals(Decision.ALLOW, back.decision)
        assertTrue(back.primary == ReasonCode.RETURN_FLOW || back.primary == ReasonCode.AUTH_FLOW)
        // a second trip to the browser in the same sign-in (e.g. 2-step verification) is fine too
        now += 5_000
        assertEquals(Decision.ALLOW, open(CHROME).decision)
    }

    @Test fun caseI_gameHomeThenUserOpensShopee() {
        using(GAME)
        assertEquals(ReasonCode.SYSTEM_FLOW, open(HOME, dt = 1_000).primary)
        val v = open(SHOPEE, dt = 2_000)
        assertEquals(Decision.ALLOW, v.decision)
        assertEquals(ReasonCode.LAUNCHER_FLOW, v.primary)
    }

    @Test fun recentsSwitchIsAllowed() {
        using(GAME)
        win(HOME, "com.android.quickstep.RecentsActivity", dt = 1_000)
        assertEquals(ReasonCode.RECENTS_FLOW, open(SHOPEE, dt = 1_000).primary)
    }

    @Test fun caseJ_notificationTapOpensLine() {
        using(GAME)
        now += 30_000
        e.onNotificationTap(LINE, now)
        assertEquals(ReasonCode.NOTIFICATION_CLICK, open(LINE, dt = 400).primary)
    }

    @Test fun caseJ2_shadeTapOnNonAutoCancelNotification() {
        using(GAME)
        now += 30_000
        notified += LINE
        win("com.android.systemui", "android.widget.FrameLayout") // pulled the shade down
        assertEquals(ReasonCode.NOTIFICATION_CLICK, open(LINE, dt = 1_200).primary)
        // but an app without a notification showing does not get that pass
        using(GAME)
        now += 30_000
        win("com.android.systemui", "android.widget.FrameLayout")
        assertEquals(Decision.BLOCK, open(SHOPEE, dt = 800).decision)
    }

    @Test fun caseK_loopIsDetectedAndSendsHome() {
        using(GAME)
        now += 30_000
        var acted: Verdict? = null
        for (i in 1..3) {
            val (tr, v) = win(SHOPEE, dt = 1_000)!!
            assertEquals("round $i", Decision.BLOCK, v.decision)
            acted = e.recordBlocked(tr, v)
            now += 800
            e.recoveredTo(GAME, SHOPEE, now) // the guard pressed BACK and the game is in front again
            // the game is NOT allowed back to the shop as a "return trip"
        }
        assertTrue(acted!!.goHome)
        assertEquals(ReasonCode.LOOP_DETECTED, acted.primary)
        // during the cooldown the same pair is handled at once
        win(HOME, dt = 500); win(GAME, dt = 3_000)
        val v = open(SHOPEE, dt = 1_000)
        assertEquals(Decision.BLOCK, v.decision)
        assertTrue(v.goHome)
    }

    @Test fun returnTripAfterAllowedOpenIsAllowed() {
        using(WECHAT)
        click(WECHAT)
        assertEquals(Decision.ALLOW, open(BILI, dt = 300).decision)
        now += 60_000 // watched a video; pressed Back (no click)
        val v = open(WECHAT)
        assertEquals(ReasonCode.RETURN_FLOW, v.primary)
    }

    @Test fun unprotectedSourceIsNeverJudged() {
        win("com.example.calculator", dt = 1_000)
        assertNull(win(SHOPEE, dt = 30_000))
    }

    @Test fun systemTargetsAlwaysAllowed() {
        using(GAME)
        now += 30_000
        for (t in listOf("com.google.android.dialer", "com.android.settings", "com.google.android.permissioncontroller", "com.google.android.documentsui", "com.google.android.gms")) {
            assertEquals(t, ReasonCode.SYSTEM_FLOW, open(t, dt = 100).primary)
            win(GAME, dt = 100)
        }
    }

    @Test fun keyboardAndOwnPromptAreTransparent() {
        using(WECHAT)
        assertNull(win("com.google.android.inputmethod.latin", dt = 100))
        assertNull(win("org.elderguard", dt = 100))
        assertNull(win(WECHAT, dt = 100)) // still the same app: no switch
    }

    @Test fun rulesAreSourceTargetPairsNotGlobal() {
        rules["$WECHAT>$BILI"] = RedirectRule(WECHAT, BILI, Decision.ALLOW, RedirectRule.CreatedBy.USER, 0)
        using(WECHAT)
        now += 30_000
        assertEquals(ReasonCode.KNOWN_ALLOW_RULE, open(BILI).primary)
        // another app opening the same target is still analysed
        using(GAME)
        now += 30_000
        assertEquals(Decision.BLOCK, open(BILI).decision)
    }

    @Test fun expiredRuleNoLongerApplies() {
        rules["$GAME>$SHOPEE"] = RedirectRule(GAME, SHOPEE, Decision.ALLOW, RedirectRule.CreatedBy.TEMPORARY, 0, expiresAt = now + 1_000)
        using(GAME)
        now += 30_000
        assertEquals(Decision.BLOCK, open(SHOPEE).decision)
    }

    @Test fun allowOnceIsConsumed() {
        grants += "$GAME>$SHOPEE"
        using(GAME); now += 30_000
        assertEquals(ReasonCode.USER_ALLOW_ONCE, open(SHOPEE).primary)
        win(GAME, dt = 2_000); now += 30_000
        // the one-time grant is gone; and going back to the shop is not a "return trip" either (the game was the
        // shop's source, not its destination)
        assertEquals(Decision.BLOCK, open(SHOPEE).decision)
    }

    @Test fun temporaryUnlockAllowsEverythingThenExpires() {
        using(GAME)
        e.unlock.unlock(30_000)
        now += 5_000
        assertEquals(ReasonCode.TEMPORARY_UNLOCK, open(SHOPEE).primary)
        now += 60_000
        win(GAME, dt = 1_000); now += 30_000
        assertEquals(Decision.BLOCK, open(SHOPEE).decision)
    }

    @Test fun bankingIsNeverHardBlocked() {
        using(GAME); now += 30_000
        val v = open(BANK)
        assertEquals(Decision.ASK, v.decision)
        assertEquals(ReasonCode.FINANCE_CAUTION, v.primary)
    }

    @Test fun unobservableGameWithoutAdPageAsksInsteadOfBlocking() {
        // a Unity game: in front for minutes, never a single click/scroll event
        win(GAME, "com.unity3d.player.UnityPlayerActivity", dt = 1_000)
        now += 120_000
        val v = open(PLAY)
        assertEquals(Decision.ASK, v.decision)
        assertTrue(ReasonCode.INTERACTION_UNOBSERVABLE in v.reasons)
    }

    @Test fun shakeDetectorNeedsTwoJolts() {
        var shakes = 0
        val d = ShakeDetector { shakes++ }
        d.onSample(0f, 0f, 9.8f, 0)
        d.onSample(0f, 0f, 16f, 100)
        assertEquals(0, shakes)
        d.onSample(0f, 0f, 3f, 300)
        assertEquals(1, shakes)
        d.onSample(0f, 0f, 16f, 5_000)
        d.onSample(0f, 0f, 16f, 7_000) // too far apart
        assertEquals(1, shakes)
        d.onSample(0f, 25f, 9.8f, 9_000) // one violent jolt is enough
        assertEquals(2, shakes)
        d.onSample(0.3f, 0.2f, 9.9f, 9_100) // lying on the table: nothing
        assertEquals(2, shakes)
    }

    @Test fun dwellIsMeasuredFromWhenTheSourceCameToFront() {
        e.tracker.setForeground(SHOPEE, null, now - 1) // seeded after a service restart
        win(GAME, dt = 0)
        click(GAME, dt = 2_000)
        val v = open(SHOPEE, dt = 15_000)
        assertTrue(v.reasons.toString(), ReasonCode.INSTANT_LAUNCH !in v.reasons)
    }

    @Test fun categories() {
        val sys = e.sys
        assertEquals(TargetKind.SHOPPING, sys.kind("com.shopee.tw"))
        assertEquals(TargetKind.SHOPPING, sys.kind("org.elderguard.testing.shop"))
        assertEquals(TargetKind.APP_STORE, sys.kind(PLAY))
        assertEquals(TargetKind.FINANCE, sys.kind(BANK))
        assertEquals(TargetKind.BROWSER, sys.kind(CHROME))
        assertEquals(TargetKind.OTHER, sys.kind("com.adobe.photoshop"))
        assertEquals(TargetKind.OTHER, sys.kind(YT))
    }
}
