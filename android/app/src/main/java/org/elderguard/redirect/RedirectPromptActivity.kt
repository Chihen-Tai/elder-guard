package org.elderguard.redirect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.elderguard.monitor.Alerts
import org.elderguard.ui.GuardTheme
import org.elderguard.ui.Palette
import org.elderguard.ui.PrimaryButton
import org.elderguard.ui.QuietButton
import org.elderguard.ui.SecondaryButton
import org.elderguard.ui.ShieldMark
import org.elderguard.ui.ShieldState

/**
 * The one question the guard asks when it cannot tell whether a switch was wanted (medium risk):
 * "「WeChat」剛剛打開了「Bilibili」。是您要打開的嗎？"
 * The safe choice (go back) is the primary button; there is no timer. Also the "仍然開啟" trampoline from the
 * "blocked" notification (grants this one pair once, for 30 s, then opens the target).
 */
class RedirectPromptActivity : ComponentActivity() {
    /** DISMISSED = the user pressed Back; COVERED = something else came on top (not a user answer). */
    enum class Answer { YES, NO, ALWAYS, DISMISSED, COVERED, SUPERSEDED }

    private var answered = false
    private var talkBack = false
    private lateinit var tr: Transition

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val from = i.getStringExtra(EXTRA_FROM) ?: return finish()
        val to = i.getStringExtra(EXTRA_TO) ?: return finish()
        if (i.action == ACTION_OPEN_ANYWAY) { openAnyway(from, to); return }
        tr = Transition(from, i.getStringExtra(EXTRA_FROM_CLS), to, i.getStringExtra(EXTRA_TO_CLS), i.getLongExtra(EXTRA_T, 0), i.getLongExtra(EXTRA_DWELL, 0))
        // No other app may draw over this question or pass taps through to it (review L8: tap-jacking "以後都允許").
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        // App names are chosen by the apps themselves: keep them short so they cannot fill the question with text
        talkBack = runCatching { getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled }.getOrDefault(false)
        val src = Alerts.label(this, from).take(24)
        val dst = Alerts.label(this, to).take(24)
        setContent {
            GuardTheme {
                BackHandler { answer(Answer.DISMISSED) }
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.BottomCenter) {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Palette.Paper, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            ShieldMark(ShieldState.Brand, 56.dp)
                            Text("這是您要\n打開的嗎？", style = MaterialTheme.typography.headlineMedium, color = Palette.Trust,
                                modifier = Modifier.semantics { heading() })
                            Text("「$src」\n剛剛打開了「$dst」", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
                            Text("如果不是您按的，\n可能是廣告。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                            // a plain sentence, not a ticking countdown: the guard must not look like a scam pop-up
                            if (!talkBack) Text("10 秒內沒有選擇，\n會自動帶您回到「$src」。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                            PrimaryButton("不要，回到「$src」", { answer(Answer.NO) })
                            SecondaryButton("要，繼續使用") { answer(Answer.YES) }
                            QuietButton("以後都允許") { answer(Answer.ALWAYS) }
                        }
                    }
                }
            }
        }
    }

    /**
     * singleTask: a second question arrives here while the first is showing (vivo test: the screen kept showing the
     * old pair). The old question is left unanswered ("covered") and the page is rebuilt for the new one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!answered && ::tr.isInitialized) RedirectGuard.instance?.answered(tr, Answer.SUPERSEDED)
        setIntent(intent)
        recreate()
    }

    /**
     * No answer in [TIMEOUT_MS] = "不要" (user decision 2026-09-29): the phone goes back to the app the user was in.
     * Runs only while the question is on screen; a question that comes back after being covered starts again.
     */
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable { if (!answered && ::tr.isInitialized) answer(Answer.NO) }

    override fun onResume() {
        super.onResume()
        h.removeCallbacks(timeout)
        // With TalkBack, reading the question aloud alone can take longer than 10 s: no timeout then (review N5).
        if (!talkBack) h.postDelayed(timeout, TIMEOUT_MS)
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(timeout) }

    private fun answer(a: Answer) {
        if (answered) return
        answered = true
        RedirectGuard.instance?.answered(tr, a) ?: run {
            // The accessibility service is reconnecting right now (OEMs / other tools can unbind it for a moment).
            // Still honour the answer: save "always", and for "no" bring the app the user was in back ourselves.
            val now = System.currentTimeMillis()
            when (a) {
                Answer.ALWAYS -> RedirectRuleRepository.get(this).put(tr.from, tr.to, Decision.ALLOW)
                Answer.NO -> {
                    RedirectHistoryRepository.get(this).outcome(now, tr.from, tr.to, "user_block")
                    packageManager.getLaunchIntentForPackage(tr.from)?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                else -> {}
            }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Something came on top without an answer: the user went home, or the target app itself opened its next
        // page over the question (vivo, Shopee: splash -> main hid the question). The guard decides which.
        if (!answered && ::tr.isInitialized && !isChangingConfigurations) { answered = true; RedirectGuard.instance?.answered(tr, Answer.COVERED); finish() }
    }

    private fun openAnyway(from: String, to: String) {
        RedirectRuleRepository.get(this).grantOnce(from, to, System.currentTimeMillis())
        RedirectHistoryRepository.get(this).outcome(System.currentTimeMillis(), from, to, "user_open_anyway")
        packageManager.getLaunchIntentForPackage(to)?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        finish()
    }

    companion object {
        const val ACTION_OPEN_ANYWAY = "org.elderguard.redirect.OPEN_ANYWAY"
        const val TIMEOUT_MS = 10_000L
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"
        private const val EXTRA_FROM_CLS = "fromCls"
        private const val EXTRA_TO_CLS = "toCls"
        private const val EXTRA_T = "t"
        private const val EXTRA_DWELL = "dwell"

        fun ask(c: Context, tr: Transition, @Suppress("UNUSED_PARAMETER") v: Verdict) {
            c.startActivity(Intent(c, RedirectPromptActivity::class.java)
                .putExtra(EXTRA_FROM, tr.from).putExtra(EXTRA_TO, tr.to).putExtra(EXTRA_FROM_CLS, tr.fromClass).putExtra(EXTRA_TO_CLS, tr.toClass)
                .putExtra(EXTRA_T, tr.timeMs).putExtra(EXTRA_DWELL, tr.fromDwellMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }

        fun openAnywayIntent(c: Context, from: String, to: String): Intent = Intent(c, RedirectPromptActivity::class.java)
            .setAction(ACTION_OPEN_ANYWAY).putExtra(EXTRA_FROM, from).putExtra(EXTRA_TO, to)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
