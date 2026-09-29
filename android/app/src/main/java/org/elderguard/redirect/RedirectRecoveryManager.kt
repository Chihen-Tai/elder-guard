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
        engine.expectRecovery(tr.to, System.currentTimeMillis(), returnTo = tr.from)
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
        val front = engine.tracker.current
        if (front != tr.to) {
            // BACK during the target's splash screen can end on the home screen instead of the app the user was in
            // (vivo field test: YouTube -> Shopee, blocked, user left on the home screen). Bring the source back.
            if (front != null && front != tr.from && front in engine.sys.launchers) {
                engine.expectRecovery(front, now, returnTo = tr.from)
                reopen(tr.from)
                h.postDelayed({ engine.recoveredTo(engine.tracker.current, tr.to, System.currentTimeMillis()) }, CHECK_MS)
                return
            }
            engine.recoveredTo(front, tr.to, now); return
        }
        engine.expectRecovery(tr.to, now, returnTo = tr.from)
        when (n) {
            2 -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); h.postDelayed({ step(tr, 3) }, CHECK_MS) }
            3 -> {
                if (!reopen(tr.from)) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                h.postDelayed({ step(tr, 4) }, CHECK_MS)
            }
            else -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                h.postDelayed({ engine.recoveredTo(engine.tracker.current, tr.to, System.currentTimeMillis()) }, CHECK_MS)
            }
        }
    }

    /** Brings [pkg]'s existing task back to the front (its screen state is kept: no CLEAR / RESET flags). */
    private fun reopen(pkg: String): Boolean {
        val launch = svc.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { svc.startActivity(launch) }.isSuccess
    }

    companion object { const val CHECK_MS = 700L }
}
