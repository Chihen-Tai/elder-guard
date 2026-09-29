package org.elderguard.redirect

import org.junit.Assert.assertTrue
import org.junit.Test

/** 安全審查第三輪：用「類別名稱」當信任依據的剩餘位置。失敗＝漏洞存在。 */
class ConAttack3Test {
    private val GAME = "com.example.puzzlegame"
    private val SHOPEE = "com.shopee.tw"
    private val HOME = "com.google.android.apps.nexuslauncher"
    private var now = 1_000_000L
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg == GAME
        override fun isAdPage(cls: String?) = false
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
    }
    private val e = RedirectEngine(testClassifier(launchers = setOf(HOME), imes = emptySet(),
        systemTargets = SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard", browsers = emptySet()), env)
    private fun win(pkg: String, cls: String, dt: Long) = run { now += dt; e.onWindow(pkg, cls, now) }
    private fun show(tag: String, r: Pair<Transition, Verdict>?) =
        println("CON3 $tag -> ${r?.first?.from}/${r?.first?.fromClass}>${r?.first?.to} ${r?.second?.decision} ${r?.second?.score} ${r?.second?.reasons}")

    /** b9：受保護的 App 先切到自己一個叫 *ChooserActivity 的頁面，再跳蝦皮 → 被當成「使用者在分享選單選的」。 */
    @Test fun b9_ownActivityNamedChooserIsNotAShareSheet() {
        win(HOME, "$HOME.Launcher", 1_000)
        win(GAME, "$GAME.MainActivity", 1_000)
        e.onClick(GAME, "android.widget.TextView", now + 5_000); now += 40_000
        win(GAME, "$GAME.ads.RewardChooserActivity", 100) // 同一個 App 內換頁：沒有轉換
        val r = win(SHOPEE, "$SHOPEE.MainActivity", 800)
        show("b9", r)
        assertTrue(r != null && r.second.decision != Decision.ALLOW)
    }

    /** 對照：同樣情境，頁面名稱普通 → 被擋。 */
    @Test fun c9_control() {
        win(HOME, "$HOME.Launcher", 1_000)
        win(GAME, "$GAME.MainActivity", 1_000)
        e.onClick(GAME, "android.widget.TextView", now + 5_000); now += 40_000
        win(GAME, "$GAME.ads.RewardActivity", 100)
        val r = win(SHOPEE, "$SHOPEE.MainActivity", 800)
        show("c9", r)
        assertTrue(r != null && r.second.decision != Decision.ALLOW)
    }
}
