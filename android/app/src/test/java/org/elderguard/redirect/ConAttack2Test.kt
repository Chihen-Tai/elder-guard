package org.elderguard.redirect

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全審查第二輪。buildClassifier() 用 queryIntentActivities / inputMethodList 決定 launchers、imes、systemTargets：
 * 任何使用者安裝的 App 只要在 manifest 宣告對應的 intent-filter（HOME、IMAGE_CAPTURE、GET_CONTENT image/(任意)、
 * OPEN_DOCUMENT、INSTALL_PACKAGE…）或一個 IME service，就會被放進這些集合。這裡直接把 App 放進集合，
 * 重現 buildClassifier 在那種手機上會產生的結果。失敗＝漏洞存在。
 */
class ConAttack2Test {
    private val LINE = "jp.naver.line.android"
    private val SHOPEE = "com.shopee.tw"
    private val CHROME = "com.android.chrome"
    private val HOME = "com.google.android.apps.nexuslauncher"
    private val PHOTO = "com.photo.editor.beauty"      // 相簿／修圖 App，宣告 GET_CONTENT image/(任意)，含廣告 SDK
    private val LAUNCHER2 = "com.theme.launcher.free"  // 主題桌面 App，宣告 HOME，沒被選為預設
    private val KEYBOARD = "com.fancy.keyboard.emoji"  // 表情鍵盤，宣告 IME service（不一定被啟用）

    private var now = 1_000_000L
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in setOf(LINE, PHOTO, LAUNCHER2, KEYBOARD)
        override fun isAdPage(cls: String?) = false
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
    }

    private fun engine(launchers: Set<String> = setOf(HOME), imes: Set<String> = emptySet(), extraSys: Set<String> = emptySet()) =
        RedirectEngine(testClassifier(launchers = launchers, imes = imes, systemTargets = SystemFlowClassifier.CORE_SYSTEM + extraSys,
            self = "org.elderguard", browsers = setOf(CHROME)), env)

    private fun RedirectEngine.win(pkg: String, dt: Long) = run { now += dt; onWindow(pkg, "$pkg.MainActivity", now) }
    private fun RedirectEngine.click(pkg: String, dt: Long) { now += dt; onClick(pkg, "android.widget.TextView", now) }
    private fun show(tag: String, r: Pair<Transition, Verdict>?) =
        println("CON2 $tag -> ${r?.first?.from}>${r?.first?.to} ${r?.second?.decision} ${r?.second?.score} ${r?.second?.reasons}")

    /** b5：修圖 App（宣告 GET_CONTENT image/(任意)）被當成 systemTarget → 它發起的跳轉全部 SYSTEM_FLOW。 */
    @Test fun b5_photoAppDeclaringGetContentIsStillJudged() {
        val e = engine(extraSys = setOf(PHOTO))
        e.win(HOME, 1_000); e.win(PHOTO, 1_000); e.click(PHOTO, 5_000); now += 30_000
        val r = e.win(SHOPEE, 100)
        show("b5", r)
        assertTrue(r != null && r.second.decision != Decision.ALLOW)
    }

    /** b6：主題桌面 App（宣告 HOME，不是預設桌面）被當成 launcher → 它的跳轉是 LAUNCHER_FLOW。 */
    @Test fun b6_nonDefaultLauncherAppIsStillJudged() {
        val e = engine(launchers = setOf(HOME, LAUNCHER2))
        e.win(HOME, 1_000); e.win(LAUNCHER2, 1_000); e.click(LAUNCHER2, 5_000); now += 30_000
        val r = e.win(SHOPEE, 100)
        show("b6", r)
        assertTrue(r != null && r.second.decision != Decision.ALLOW)
    }

    /** b7：鍵盤 App（有 IME service）開自己的 Activity → 整個套件透明 → 它的跳轉被算在前一個 App 身上。 */
    @Test fun b7_imePackageActivityIsNotTransparent() {
        val e = engine(imes = setOf(KEYBOARD))
        e.win(HOME, 1_000)
        val entered = e.win(KEYBOARD, 1_000) // 鍵盤 App 的「主題商店」Activity，從桌面打開
        show("b7 enter", entered)
        e.click(KEYBOARD, 5_000); now += 30_000
        val r = e.win(SHOPEE, 100)
        show("b7 jump", r)
        assertTrue(r != null && r.second.decision != Decision.ALLOW)
    }

    /**
     * b8：REPEATED_TAPLESS 的計數在「抖動合併」之前。一次真的開啟（目標啟動時焦點來回兩次）被記成 2 次，
     * 下一次正常的開啟就會被問。期望：抖動只算一次開啟。
     */
    @Test fun b8_startupFlappingCountsAsOneQuietJump() {
        val e = engine()
        e.win(LINE, 5_000); e.click(LINE, 20_000); now += 30_000
        show("b8 open#1", e.win(CHROME, 100))           // 使用者按了一個不回報點按的元件
        show("b8 bounce", e.win(LINE, 300))              // 啟動抖動
        show("b8 bounce2", e.win(CHROME, 300))
        e.win(LINE, 60_000)                              // 看完網頁，回到 LINE（回程）
        now += 60_000
        val r = e.win(CHROME, 100)                       // 第二次真的開連結
        show("b8 open#2", r)
        assertTrue("second genuine open must not be questioned: ${r?.second}", r != null && r.second.decision == Decision.ALLOW)
    }
}
