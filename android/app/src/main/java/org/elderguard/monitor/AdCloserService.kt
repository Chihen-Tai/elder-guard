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
 * The guard's accessibility service (off until the family turns it on in Android's accessibility settings). Two jobs:
 *  - pop-up closer: closes a full-screen ad page another app put over the user's app, from the 2nd time the same
 *    app does it within 30 minutes (PopupPolicy);
 *  - anti-redirect (RedirectGuard): notices when a protected app sends the user to another app without being asked.
 *
 * Minimal footprint by design: it uses window changes (package + class name) and tap / scroll events (package + view
 * class name only). canRetrieveWindowContent is false, so the screen's content tree is not available. Android still
 * attaches the tapped view's text to a click event; this service never reads it (no getText / getSource anywhere),
 * and nothing about the event except package, class and time is kept. Its only actions are BACK, HOME, reopening the app the user was in, and asking.
 * The class name stays "AdCloserService": renaming it would silently switch the service off for existing users.
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
        guard = runCatching { org.elderguard.redirect.RedirectGuard(this) }.getOrNull()
        org.elderguard.redirect.RedirectGuard.instance = guard
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        connected = true
    }

    private var guard: org.elderguard.redirect.RedirectGuard? = null
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { guard?.onScreenOff() }
    }

    private var lastAdPkg: String? = null
    private var lastAdMs = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString()
        when (e.eventType) {
            // only WHO was tapped (package + view class), never the text: enough to tell a tap from an automatic jump
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> { guard?.engine?.onClick(pkg, cls, System.currentTimeMillis()); return }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { guard?.engine?.onScroll(pkg, System.currentTimeMillis()); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {}
            else -> return
        }
        val before = policy.current()
        val d = policy.onWindow(pkg, cls, System.currentTimeMillis())
        // Every ad window and the decision taken: lets a field log explain a missed or wrong close.
        val now = System.currentTimeMillis()
        val isAd = org.elderguard.detect.AdSdkCatalog.isAdActivity(cls)
        if (isAd) { lastAdPkg = pkg; lastAdMs = now }
        // Also the other windows of an app that just showed an ad (overlays, dialogs): they explain chained pop-ups.
        if (isAd || (pkg == lastAdPkg && now - lastAdMs < 5 * 60_000L))
            store.diag("win", pkg, mapOf("cls" to cls?.substringAfterLast('.'), "prev" to before, "intr" to d.intrusion, "n" to d.count, "block" to d.block))
        // the pop-up closer acts first; when it closes the window, anti-redirect only records the switch
        guard?.onWindow(pkg, cls, now, popupHandled = d.block)
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

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (::store.isInitialized) store.diag("closer", fields = mapOf("state" to "off"))
        guard?.stop(); guard = null
        org.elderguard.redirect.RedirectGuard.instance = null
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    companion object {
        /** True while Android has the service bound (the setting alone can say "on" while an OEM has stopped it). */
        @Volatile var connected = false

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
