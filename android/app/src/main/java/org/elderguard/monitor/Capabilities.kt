package org.elderguard.monitor

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import org.elderguard.data.Store

/**
 * What the guard can actually do right now. The UI must show this honestly: a missing permission means a function
 * is OFF, and the home screen must never say "protected" when the monitor is not running.
 */
data class Capabilities(
    val usageAccess: Boolean,
    val notificationAccess: Boolean,
    val postNotifications: Boolean,
    val batteryUnrestricted: Boolean,
    val monitorAlive: Boolean,
    val heartbeatAgeSec: Long?,
) {
    val fullProtection get() = usageAccess && notificationAccess && postNotifications && monitorAlive

    companion object {
        const val HEARTBEAT_STALE_MS = 90_000L

        fun read(c: Context): Capabilities {
            val ops = c.getSystemService(AppOpsManager::class.java)
            val usage = (if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)) == AppOpsManager.MODE_ALLOWED
            val nm = c.getSystemService(NotificationManager::class.java)
            val listener = ComponentName(c, NotificationWatcher::class.java)
            val notifAccess = if (Build.VERSION.SDK_INT >= 27) nm.isNotificationListenerAccessGranted(listener)
            else Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true
            val post = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
            val hb = Store(c).heartbeatMs
            val age = if (hb == 0L) null else (System.currentTimeMillis() - hb) / 1000
            val alive = GuardService.running && hb != 0L && System.currentTimeMillis() - hb < HEARTBEAT_STALE_MS
            return Capabilities(usage, notifAccess, post && nm.areNotificationsEnabled(), battery, alive, age)
        }

        fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        fun notificationAccessIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        fun appNotificationSettings(c: Context, pkg: String = c.packageName) =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        @Suppress("BatteryLife")
        fun batteryIntent(c: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
        fun appInfo(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        fun overlaySettings(pkg: String) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
        fun uninstall(pkg: String) = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
    }
}
