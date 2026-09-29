package org.elderguard.redirect

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全審查第四輪：AUTO 學習會不會把「一次沒回報的真人點按」變成永久誤擋。
 * 情境：一般通訊 App（沒有廣告 SDK，原本不受保護），它平常會回報點按，但聊天室裡「連結預覽卡片」那個元件不回報
 * （和 §9.1 第 10 點愛奇藝同一種現象）。長輩兩次點朋友傳來的蝦皮連結。
 */
class ConAttack4Test {
    private val HOME = "com.google.android.apps.nexuslauncher"
    private val CHAT = "com.example.chatapp"
    private val SHOPEE = "com.shopee.tw"
    private var now = 1_000_000L
    private val protected = HashSet<String>()
    private val learned = ArrayList<String>()
    private val env = object : RedirectEnv {
        override fun now() = now
        override fun isProtected(pkg: String) = pkg in protected
        override fun isAdPage(cls: String?) = false
        override fun rule(source: String, target: String): RedirectRule? = null
        override fun consumeGrant(source: String, target: String) = false
        override fun hadBlocksRecently(source: String) = false
        override fun hasNotification(pkg: String) = false
        override fun learnable(pkg: String) = true
        override fun learnSource(pkg: String) { learned += pkg; protected += pkg }
    }
    private val e = RedirectEngine(testClassifier(setOf(HOME), emptySet(), SystemFlowClassifier.CORE_SYSTEM, self = "org.elderguard"), env)
    private fun win(pkg: String, dt: Long) = run { now += dt; e.onWindow(pkg, "$pkg.MainActivity", now) }

    @Test fun b10_oneUnreportedTapDoesNotTurnIntoPermanentBlocking() {
        win(HOME, 1_000); win(CHAT, 1_000)
        now += 5_000; e.onClick(CHAT, "android.widget.TextView", now); now += 20_000   // 打字、點別的東西：會回報
        val first = win(SHOPEE, 100)   // 點連結卡片（沒回報）
        println("CON4 b10 first=${first?.second} learned=$learned")
        win(HOME, 60_000); win(CHAT, 3_600_000)                                        // 一小時後再開聊天
        now += 5_000; e.onClick(CHAT, "android.widget.TextView", now); now += 20_000
        val second = win(SHOPEE, 100)  // 又點了一個朋友傳的蝦皮連結（沒回報）
        println("CON4 b10 second=${second?.second}")
        assertTrue("a single tap-less shop jump must not make the next genuine one a BLOCK: ${second?.second}",
            second == null || second.second.decision != Decision.BLOCK)
    }
}
