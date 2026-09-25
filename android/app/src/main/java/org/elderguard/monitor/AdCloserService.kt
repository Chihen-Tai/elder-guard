package org.elderguard.monitor

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import org.elderguard.data.Store
import org.elderguard.detect.PopupPolicy
import org.elderguard.scan.AppScanner

/**
 * OPTIONAL ad-closer (off until the family turns it on in Android's accessibility settings).
 *
 * Minimal footprint by design: it only receives "window state changed" events (package + class name) and
 * canRetrieveWindowContent is false, so it never reads what is on screen. Its only actions are BACK and, if an ad
 * page refuses to close, HOME.
 *
 * It closes a pop-up only from the 2nd time the same app pops up over other apps within 30 minutes (PopupPolicy).
 */
class AdCloserService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var policy: PopupPolicy
    private val scanner by lazy { AppScanner(this) }
    /** pkg -> (checkedAtMs, hidden). Short-lived: apps often hide their icon only after the first launch. */
    private val hiddenCache = HashMap<String, Pair<Long, Boolean>>()

    override fun onServiceConnected() {
        store = Store(this)
        val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        val imes = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        policy = PopupPolicy(
            neverSource = setOf(packageName, "android", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
                "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.settings") + homes,
            // System UI windows (heads-up notifications, the shade) say nothing about which app the user is in;
            // treating them as "user action" let every heads-up reset the count (emulator test 0.3.3).
            userInitiatedSources = emptySet(),
            transparentPackages = imes.toSet() + "com.android.systemui",
            isLauncherEntry = { pkg, cls -> cls != null && cls in runCatching { scanner.launcherClasses(pkg) }.getOrDefault(emptySet()) },
            userJustLeftGuard = { t -> org.elderguard.detect.IntrusionDetector.isReturnAfterExit(listOf(org.elderguard.MainActivity.userLeftAtMs), t) },
            hiddenIconUserApp = { pkg ->
                val now = System.currentTimeMillis()
                val c = hiddenCache[pkg]
                if (c != null && now - c.first < 60_000) c.second else hiddenUserApp(pkg).also { hiddenCache[pkg] = now to it }
            },
        )
        // continue counting from pop-ups the usage-history monitor already logged
        store.readLog(System.currentTimeMillis() - 30 * 60 * 1000L).filter { it.optString("type") == "intrusion" }
            .forEach { policy.seed(it.optString("pkg"), it.optLong("t")) }
        store.diag("closer", fields = mapOf("state" to "on"))
    }

    private var lastAdPkg: String? = null
    private var lastAdMs = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString()
        val before = policy.current()
        val d = policy.onWindow(pkg, cls, System.currentTimeMillis())
        // Every ad window and the decision taken: lets a field log explain a missed or wrong close.
        val now = System.currentTimeMillis()
        val isAd = org.elderguard.detect.AdSdkCatalog.isAdActivity(cls)
        if (isAd) { lastAdPkg = pkg; lastAdMs = now }
        // Also the other windows of an app that just showed an ad (overlays, dialogs): they explain chained pop-ups.
        if (isAd || (pkg == lastAdPkg && now - lastAdMs < 5 * 60_000L))
            store.diag("win", pkg, mapOf("cls" to cls?.substringAfterLast('.'), "prev" to before, "intr" to d.intrusion, "n" to d.count, "block" to d.block))
        if (!d.intrusion) return
        store.log("intrusion", pkg, mapOf("cls" to cls, "over" to d.coveredPackage, "kind" to "AdOverOtherApp", "src" to "closer"))
        if (!d.block) return // first pop-up: record only (user's policy: close only repeated pop-ups)
        close(pkg, cls, d.count, attempt = 1)
    }

    private fun close(pkg: String, cls: String?, count: Int, attempt: Int) {
        val action = if (attempt <= 2) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
        val ok = performGlobalAction(action)
        store.log("blocked", pkg, mapOf("cls" to cls, "n" to count, "attempt" to attempt,
            "action" to if (action == GLOBAL_ACTION_BACK) "back" else "home", "ok" to ok))
        if (attempt == 1) Alerts.maybeAlertIntrusion(this, pkg, count)
        h.postDelayed({
            // Ask the system's screen history who is really in front now (window events are not reliable here).
            val front = foregroundPackage()
            if (front == pkg && attempt < 3) close(pkg, cls, count, attempt + 1)
            else {
                policy.setForeground(front)
                store.diag("closed", pkg, mapOf("front" to front, "attempts" to attempt, "stillOnTop" to (front == pkg)))
            }
        }, 800)
    }

    /** Latest resumed activity's package from UsageStatsManager (needs usage access, which the guard already asks for). */
    private fun foregroundPackage(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val it = usm.queryEvents(now - 60_000, now) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (it.hasNextEvent()) {
            it.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    private fun hiddenUserApp(pkg: String): Boolean = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && scanner.launcherState(pkg).first
    } catch (e: PackageManager.NameNotFoundException) { false }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::store.isInitialized) store.diag("closer", fields = mapOf("state" to "off"))
        super.onDestroy()
    }

    companion object {
        fun isEnabled(c: Context): Boolean {
            val cn = ComponentName(c, AdCloserService::class.java).flattenToString()
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(cn, ignoreCase = true) }
        }

        /**
         * The per-service page (ACCESSIBILITY_DETAILS_SETTINGS) needs the privileged OPEN_ACCESSIBILITY_DETAILS_SETTINGS
         * permission — using it crashed the app in the emulator test. Normal apps must open the accessibility list.
         */
        fun settingsIntent(c: Context): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
