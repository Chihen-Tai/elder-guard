package org.elderguard.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.elderguard.MainActivity
import org.elderguard.data.Store

/** Calm, specific alerts that name the source app and open its detail page (never scary, never a countdown). */
object Alerts {
    const val CH_ALERT = "source_alerts"
    const val CH_STATUS = "monitor_status"
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "找到干擾來源", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "守門員運作中", NotificationManager.IMPORTANCE_MIN))
    }

    fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    /** Full-screen pages shown over other apps, repeated. */
    fun maybeAlertIntrusion(c: Context, pkg: String, count: Int) {
        val title = "「${label(c, pkg)}」疑似反覆跳出廣告"
        val text = "它在您使用其他 App 時跳出全螢幕畫面 $count 次。點這裡看證據，並前往設定移除。"
        post(c, "$pkg#intrusion", title, text, "intrusion")
    }

    fun maybeAlertNotification(c: Context, pkg: String, site: String?) {
        val title = if (site != null) "網站「$site」發出假警告通知" else "「${label(c, pkg)}」發出假警告通知"
        val text = "它的通知假裝手機有問題。點這裡看怎麼關掉。"
        post(c, "$pkg#notification" + (site?.let { "#$it" } ?: ""), title, text, "notification")
    }

    fun maybeAlertNewApp(c: Context, pkg: String, reason: String) =
        post(c, "$pkg#static", "剛裝的「${label(c, pkg)}」需要注意", reason, "static")

    /**
     * [key] = "pkg#kind[#site]": each kind of evidence is throttled separately, so an early weak alert (static scan)
     * never suppresses a later strong one (confirmed pop-ups) — bug found in emulator test 2.
     */
    private fun post(c: Context, key: String, title: String, text: String, kind: String) {
        val store = Store(c)
        val now = System.currentTimeMillis()
        val pkg = key.substringBefore('#')
        if (store.isKept(pkg) || now - store.lastAlertMs(key) < MIN_INTERVAL_MS) return
        store.setLastAlert(key, now)
        store.diag("alert", pkg, mapOf("kind" to kind, "channelOn" to c.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()))
        ensureChannels(c)
        val open = PendingIntent.getActivity(
            c, key.hashCode(),
            // Unique data per app + SINGLE_TOP so a running instance always receives the new intent in onNewIntent.
            Intent(c, MainActivity::class.java).setData(android.net.Uri.parse("elderguard://app/$pkg"))
                .putExtra(MainActivity.EXTRA_OPEN_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.cancel(key.hashCode()) // a fresh post re-alerts (M0 finding B)
        nm.notify(key.hashCode(), n)
    }
}
