package org.elderguard.redirect

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Undoes a blocked switch. What an accessibility service can really do (no pre-launch interception exists):
 *  1. BACK — the target's page closes and Android returns to the task underneath (normally the source);
 *  2. if the target is still in front 0.7 s later: BACK again (some apps open two pages at once);
 *  3. still there: bring the source app back with its launcher intent (allowed: accessibility services are exempt
 *     from Android's background-activity-start limits);
 *  4. no launcher intent: HOME.
 * Loops go straight to HOME (going back into the source would just redirect again).
 * Returns a short outcome code for the history; the checks after step 1 run later on the main thread.
 */
class RedirectRecoveryManager(private val svc: AccessibilityService, private val engine: RedirectEngine) {
    private val h = Handler(Looper.getMainLooper())

    fun recover(tr: Transition, goHome: Boolean): String {
        if (goHome) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            h.postDelayed({ engine.recoveredTo(engine.tracker.current, tr.to, System.currentTimeMillis()) }, CHECK_MS)
            return "home"
        }
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        h.postDelayed({ step(tr, 2) }, CHECK_MS)
        return "back"
    }

    private fun step(tr: Transition, n: Int) {
        val now = System.currentTimeMillis()
        if (engine.tracker.current != tr.to) { engine.recoveredTo(engine.tracker.current, tr.to, now); return }
        when (n) {
            2 -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); h.postDelayed({ step(tr, 3) }, CHECK_MS) }
            3 -> {
                val launch = svc.packageManager.getLaunchIntentForPackage(tr.from)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val ok = launch != null && runCatching { svc.startActivity(launch) }.isSuccess
                if (!ok) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                h.postDelayed({ step(tr, 4) }, CHECK_MS)
            }
            else -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                h.postDelayed({ engine.recoveredTo(engine.tracker.current, tr.to, System.currentTimeMillis()) }, CHECK_MS)
            }
        }
    }

    companion object { const val CHECK_MS = 700L }
}
