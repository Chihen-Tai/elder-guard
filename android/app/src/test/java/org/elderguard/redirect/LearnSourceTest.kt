package org.elderguard.redirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * review B8: "protected" = "has a known ad SDK" is the app's own choice; an app caught redirecting is protected from then on.
 * review B9: but only on evidence a missed tap cannot explain — never on "no tap seen" alone, however often.
 */
class LearnSourceTest {
    private val HOME = "com.google.android.apps.nexuslauncher"
    private val APP = "com.example.freewifi"   // no ad SDK: affiliate jumps written by hand
    private val SHOPEE = "com.shopee.tw"
    private val CHROME = "com.android.chrome"
    private val AD = "com.applovin.adview.AppLovinFullscreenActivity"
    private var now = 1_000_000L
    private val protected = HashSet<String>()
    private val learned = ArrayList<String>()
    private var learnable = true
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in protected
        override fun isAdPage(cls: String?) = cls == AD
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
        override fun learnable(pkg: String) = learnable
        override fun learnSource(pkg: String) { learned += pkg; protected += pkg }
    }
    private val e = RedirectEngine(testClassifier(setOf(HOME), emptySet(), SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard",
        browsers = setOf(CHROME)), env)
    private fun win(pkg: String, dt: Long, cls: String = "$pkg.MainActivity") = run { now += dt; e.onWindow(pkg, cls, now) }
    private fun using(pkg: String) { win(HOME, 1_000); win(pkg, 1_000); now += 5_000; e.onClick(pkg, "android.widget.TextView", now); now += 30_000 }

    @Test fun unprotectedAppCaughtJumpingFromAnAdPageIsProtectedFromThenOn() {
        using(APP)
        win(APP, 1_000, AD)                   // its own full-screen ad page (a renamed SDK the scan did not recognise)
        assertNull("the first jump is not acted on (the app was not protected)", win(SHOPEE, 3_000))
        assertEquals(listOf(APP), learned)
        win(APP, 5_000); now += 30_000
        assertEquals(Decision.BLOCK, win(SHOPEE, 100)!!.second.decision)
    }

    @Test fun splashJumpRightAfterOpeningFromHomeIsLearned() {
        win(HOME, 1_000); win(APP, 1_000)     // the user opens it from the home screen, it has reported taps before
        e.onClick(APP, "android.widget.TextView", now - 60_000)
        assertNull(win(SHOPEE, 800))          // 0.8 s later it is in the shop: an open-screen ad
        assertEquals(listOf(APP), learned)
    }

    /** review B9: a chat's link card that never reports taps, tapped again and again over days, teaches nothing. */
    @Test fun repeatedTaplessShopJumpsAloneNeverTeach() {
        repeat(4) {
            using(APP)                        // reports other taps
            assertNull(win(SHOPEE, 100))      // the link-card tap is not reported
            win(APP, 60_000); now += 24 * 3_600_000L
        }
        assertTrue(learned.isEmpty())
    }

    @Test fun aTappedLinkOrABrowserJumpTeachesNothing() {
        using(APP)
        now += 100; e.onClick(APP, "android.widget.TextView", now)
        assertNull(win(SHOPEE, 500))          // the user tapped
        win(APP, 5_000); now += 30_000
        assertNull(win(CHROME, 100))          // tap-less, but a browser: not strong evidence
        assertTrue(learned.isEmpty())
    }

    @Test fun notLearnableMeansNotScored() {
        learnable = false // MANUAL / ALL mode, a system app, or the user excluded it
        using(APP)
        assertNull(win(SHOPEE, 100))
        assertTrue(learned.isEmpty())
    }
}
