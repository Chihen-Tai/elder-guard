package org.elderguard.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/**
 * Which apps may be exempted from the guard's judgement (review B1/B5/B8). One rule for every detector (jump
 * protection, the pop-up closer, the usage-history monitor): trust only what an app cannot give itself —
 *  - the SYSTEM flag (preinstalled apps);
 *  - the home screen the user actually uses (the default HOME), not every app that DECLARES a HOME filter;
 *  - the keyboards the user ENABLED, and only their input window, never their activities (theme stores, ads).
 * Names, class names and intent-filters are candidates at most.
 */
class TrustedRoles(private val c: Context) {
    private val pm = c.packageManager
    private val sysCache = HashMap<String, Boolean>()
    private val activityCache = HashMap<String, Boolean>()

    val isSystemApp: (String) -> Boolean = { p ->
        synchronized(sysCache) {
            sysCache.getOrPut(p) { runCatching { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false) }
        }
    }

    /** Whether (pkg, cls) is a declared Activity of pkg (a keyboard's input window is not). */
    val isActivity: (String, String?) -> Boolean = { p, cls ->
        cls != null && synchronized(activityCache) {
            activityCache.getOrPut("$p/$cls") { runCatching { pm.getActivityInfo(ComponentName(p, cls), 0); true }.getOrDefault(false) }
        }
    }

    private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** Every app declaring HOME: candidates only. */
    fun declaredHomes(): Set<String> = runCatching {
        pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    }.getOrDefault(emptyList()).toSet()

    /** The home screen in use; null when none is chosen ("android" = the chooser). */
    fun defaultHome(): String? = runCatching { pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
        .getOrNull()?.takeIf { it != "android" }

    /** Trusted home screens: system ones and the default. */
    fun homes(): Set<String> { val d = defaultHome(); return declaredHomes().filter { isSystemApp(it) || it == d }.toSet() }

    companion object {
        /** Permission / installer / settings dialogs that open over apps. Trusted only if FLAG_SYSTEM on this phone. */
        val SYSTEM_DIALOGS = listOf("android", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.settings")
    }

    fun enabledImes(): Set<String> = runCatching {
        c.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
    }.getOrDefault(emptyList()).toSet()
}
