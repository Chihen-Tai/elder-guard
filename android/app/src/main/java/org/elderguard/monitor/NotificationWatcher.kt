package org.elderguard.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.elderguard.data.Store
import org.elderguard.detect.NotificationClassifier

/**
 * Attributes notifications to the app that posted them (reliable: StatusBarNotification.packageName) and to the web
 * site for Chrome web-push channels. Text is classified in memory and discarded; only flags are logged.
 *
 * This does NOT block anything by itself: the user is guided to that app's notification settings (or Chrome's
 * site settings). Turning notifications off does not stop full-screen pages or overlays — those are handled by
 * UsageMonitor / the app detail screen.
 */
class NotificationWatcher : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val r = NotificationClassifier.classify(
            ex.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            n.channelId,
            n.fullScreenIntent != null,
        )
        if (!r.disguisedWarning && !r.fullScreen && r.webHost == null) return
        val senderIsSystem = runCatching {
            (packageManager.getApplicationInfo(sbn.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)
        if (!NotificationClassifier.countsAgainstSender(senderIsSystem, r.webHost)) return
        val store = Store(this)
        store.log("notif", sbn.packageName, mapOf("warn" to r.disguisedWarning, "fsi" to r.fullScreen, "site" to r.webHost))
        if (r.disguisedWarning) Alerts.maybeAlertNotification(this, sbn.packageName, r.webHost)
    }
}
