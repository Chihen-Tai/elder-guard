package org.elderguard.redirect

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 安全審查寫的攻擊情境：每個測試寫的是「應該有的安全行為」，失敗＝漏洞存在。保留為回歸測試。 */
class ConAttackTest {
    private val LINE = "jp.naver.line.android"
    private val GAME = "com.example.puzzlegame"
    private val SHOPEE = "com.shopee.tw"
    private val TAOBAO = "com.taobao.taobao"
    private val PLAY = "com.android.vending"
    private val CHROME = "com.android.chrome"
    private val YT = "com.google.android.youtube"
    private val HOME = "com.google.android.apps.nexuslauncher"
    private val ROGUE = "com.cleaner.systemui.booster" // 名字裡有 systemui 的第三方 App

    private var now = 1_000_000L
    private var protected = setOf(LINE, GAME, ROGUE)
    private lateinit var e: RedirectEngine
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in protected
        override fun isAdPage(cls: String?) = cls?.startsWith("com.applovin.") == true
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
    }

    @Before fun setUp() {
        val sys = testClassifier(
            launchers = setOf(HOME), imes = setOf("com.google.android.inputmethod.latin"),
            systemTargets = SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard", browsers = setOf(CHROME),
        )
        e = RedirectEngine(sys, env)
    }

    private fun win(pkg: String, cls: String? = "$pkg.MainActivity", dt: Long = 0): Pair<Transition, Verdict>? { now += dt; return e.onWindow(pkg, cls, now) }
    private fun click(pkg: String, dt: Long = 0) { now += dt; e.onClick(pkg, "android.widget.TextView", now) }
    private fun using(pkg: String) { win(pkg, dt = 5_000); click(pkg, dt = 20_000); now += 10_000 }
    private fun show(tag: String, r: Pair<Transition, Verdict>?) =
        println("CON $tag -> ${r?.first?.from}>${r?.first?.to} ${r?.second?.decision} ${r?.second?.score} ${r?.second?.reasons}")

    /** 攻擊 1：套件名含 "systemui" 就被當成透明／系統畫面，它的跳轉看起來像「從桌面開的」。 */
    @Test fun a1_packageNamedSystemUiIsNotExempt() {
        win(HOME, dt = 1_000)
        val entered = win(ROGUE, dt = 1_000)
        show("a1 enter", entered)
        click(ROGUE, dt = 20_000); now += 30_000 // 會回報點按的 App，30 秒沒點
        val r = win(SHOPEE, dt = 100)
        show("a1 jump", r)
        assertTrue("rogue app must be tracked as the front app", e.tracker.current != HOME || entered != null)
        assertTrue("jump to a shop with no tap must not be ALLOW", r != null && r.second.decision != Decision.ALLOW)
    }

    /** 攻擊 2：守門員按返回的 4 秒內，目標 App 往「任何地方」的跳轉都被當成守門員自己造成的。 */
    @Test fun a2_recoveryWindowOnlyCoversTheWayBack() {
        protected = protected + SHOPEE
        using(LINE); now += 30_000
        val (tr, v) = win(SHOPEE)!!
        show("a2 first", tr to v)
        e.expectRecovery(tr.to, now)
        val r = win(TAOBAO, dt = 500) // 購物 App 趁守門員返回時再跳到另一個購物 App
        show("a2 second", r)
        assertTrue("only SHOPEE->LINE/launcher may be GUARD_RECOVERY", r!!.second.primary != ReasonCode.GUARD_RECOVERY)
    }

    /** 攻擊 3：每次一打開就（沒有點按）把人帶到 Chrome，重複 5 次，從來沒被問過。 */
    @Test fun a3_repeatedInstantBrowserJumpsAreEventuallyQuestioned() {
        using(GAME)
        val ds = (1..5).map {
            win(HOME, dt = 5_000); win(GAME, dt = 1_000)
            val r = win(CHROME, dt = 800)
            show("a3 #$it", r)
            r!!.second.decision
        }
        assertTrue("5 instant, tap-less browser jumps: at least one must be ASK/BLOCK, got $ds", ds.any { it != Decision.ALLOW })
    }

    /** 同上，目的地是 YouTube。 */
    @Test fun a4_repeatedInstantVideoJumpsAreEventuallyQuestioned() {
        using(GAME)
        val ds = (1..5).map {
            win(HOME, dt = 5_000); win(GAME, dt = 1_000)
            val r = win(YT, dt = 800)
            show("a4 #$it", r)
            r!!.second.decision
        }
        assertTrue("got $ds", ds.any { it != Decision.ALLOW })
    }

    /** 對照：同樣模式跳 Play 商店會被擋（確認測試架構本身沒問題）。 */
    @Test fun c0_control_storeJumpIsBlocked() {
        using(GAME)
        win(HOME, dt = 5_000); win(GAME, dt = 1_000)
        val r = win(PLAY, dt = 800)
        show("c0", r)
        assertTrue(r!!.second.decision != Decision.ALLOW)
    }
}
