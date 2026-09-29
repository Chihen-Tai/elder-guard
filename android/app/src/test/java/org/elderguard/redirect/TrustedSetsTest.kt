package org.elderguard.redirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** review B5: exemptions come from what an app cannot declare for itself (system flag, the home in use, user defaults). */
class TrustedSetsTest {
    private val HOME = "com.google.android.apps.nexuslauncher"
    private val NOVA = "com.thirdparty.launcher"   // a user-installed launcher the user CHOSE as default
    private val THEME = "com.theme.launcher.free"  // declares HOME, not chosen
    private val SMS = "com.thirdparty.sms"         // user-installed SMS app chosen as the default
    private val LINE = "jp.naver.line.android"
    private val SHOPEE = "com.shopee.tw"

    private var now = 1_000_000L
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in setOf(LINE, SMS, THEME)
        override fun isAdPage(cls: String?) = false
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
    }
    private val sys = testClassifier(
        launchers = setOf(HOME, NOVA, THEME), imes = emptySet(), systemTargets = SystemFlowClassifier.CORE_SYSTEM + SMS,
        self = "org.elderguard", defaultHome = NOVA, userDefaults = setOf(SMS),
    )
    private val e = RedirectEngine(sys, env)
    private fun win(pkg: String, dt: Long) = run { now += dt; e.onWindow(pkg, "$pkg.MainActivity", now) }

    /** review B7: "…ResolverActivity" is the share sheet only inside a system app (an OEM resolver). */
    @Test fun oemResolverInASystemAppIsAShareSheet() {
        val resolver = "com.oem.resolver"
        val s2 = testClassifier(setOf(HOME), emptySet(), SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard",
            isSystemApp = { it == resolver || TEST_IS_SYSTEM(it) })
        assertEquals(SystemFlowClassifier.Hop.CHOOSER, s2.hopOf(resolver, "$resolver.ResolverActivity"))
        assertEquals(null, s2.hopOf(LINE, "$LINE.share.RewardChooserActivity"))
    }

    @Test fun onlySystemAndDefaultHomesAreLaunchers() {
        assertEquals(setOf(HOME, NOVA), sys.launchers)
        win(NOVA, 5_000)
        assertEquals(Decision.ALLOW, win(SHOPEE, 30_000)!!.second.decision) // opened from the home screen in use
    }

    @Test fun defaultSmsAppIsAReachableTargetButItsOwnJumpsAreJudged() {
        win(HOME, 1_000); win(LINE, 5_000); now += 30_000
        assertEquals(Decision.ALLOW, win(SMS, 100)!!.second.decision) // e.g. a "send SMS" link: a system flow
        now += 30_000
        assertNotEquals(Decision.ALLOW, win(SHOPEE, 100)!!.second.decision) // the SMS app itself earns no exemption
    }
}
