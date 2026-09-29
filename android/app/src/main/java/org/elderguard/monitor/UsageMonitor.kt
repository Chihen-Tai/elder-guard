package org.elderguard.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.elderguard.detect.Intrusion
import org.elderguard.detect.IntrusionDetector
import org.elderguard.detect.IntrusionSummary
import org.elderguard.detect.ScreenEvent
import org.elderguard.scan.AppScanner

/**
 * Reads the system's own screen-change history (UsageStatsManager, needs "usage access") and finds apps that put
 * full-screen pages over other apps. Works retroactively: the system keeps these events even while the guard is not
 * running, so "find the ad that just appeared" works right after the fact.
 */
class UsageMonitor(private val c: Context) {
    private val usm = c.getSystemService(UsageStatsManager::class.java)
    private val scanner = AppScanner(c)
    private val hiddenCache = HashMap<String, Boolean>()

    fun screenEvents(fromMs: Long, toMs: Long = System.currentTimeMillis()): List<ScreenEvent> {
        val out = mutableListOf<ScreenEvent>()
        val it = usm.queryEvents(fromMs, toMs) ?: return out
        val e = UsageEvents.Event()
        while (it.hasNextEvent()) {
            it.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) out += ScreenEvent(e.timeStamp, e.packageName, e.className)
        }
        return out
    }

    private val launcherCache = HashMap<String, Set<String>>()

    fun detector() = IntrusionDetector(ignoredPackages(),
        homePackages = homes(),
        hasHiddenIcon = { pkg -> hiddenCache.getOrPut(pkg) { runCatching { scanner.launcherState(pkg).first }.getOrDefault(false) } },
        isLauncherEntry = { pkg, cls -> cls != null && cls in launcherCache.getOrPut(pkg) { runCatching { scanner.launcherClasses(pkg) }.getOrDefault(emptySet()) } },
        userExitTimes = org.elderguard.data.Store(c).readLog(System.currentTimeMillis() - 24 * 3600 * 1000L)
            .filter { it.optString("type") == "user_exit" }.map { it.optLong("t") },
    )

    fun findIntrusions(windowMs: Long): Pair<List<Intrusion>, List<IntrusionSummary>> {
        val d = detector()
        val list = d.detect(screenEvents(System.currentTimeMillis() - windowMs))
        return list to d.summarize(list)
    }

    /**
     * Our own app, System UI, permission and installer dialogs, and the trusted home screens (system ones and the
     * default) never count as sources — not every app that declares HOME (review B8).
     */
    private fun homes(): Set<String> = TrustedRoles(c).homes()

    private fun ignoredPackages(): Set<String> {
        val roles = TrustedRoles(c)
        // fixed names only when that package really is a system app here: a missing one could be taken by a sideloaded app
        return setOf(c.packageName, "com.android.systemui") +
            TrustedRoles.SYSTEM_DIALOGS.filter { it == "android" || roles.isSystemApp(it) } + roles.homes()
    }
}
