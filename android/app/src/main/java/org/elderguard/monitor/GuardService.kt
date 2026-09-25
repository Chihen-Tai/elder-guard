package org.elderguard.monitor

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.elderguard.MainActivity
import org.elderguard.data.Diagnostics
import org.elderguard.data.Store
import org.elderguard.detect.IntrusionSummary
import org.elderguard.detect.RiskLevel
import org.elderguard.detect.StaticRisk
import org.elderguard.scan.AppScanner

/**
 * Foreground "monitor" service:
 *  - every 5 s while the screen is on: reads screen-change events and alerts on repeated full-screen pages over
 *    other apps (needs usage access; without it the monitor says so instead of pretending);
 *  - on every install/update: scans the new app now and again after 2 and 30 minutes (icon hiding happens after the
 *    first launch);
 *  - writes a heartbeat so the UI can show "stopped" when the system kills it.
 */
class GuardService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var store: Store
    private lateinit var usage: UsageMonitor
    private val scanner by lazy { AppScanner(this) }

    private val poll = object : Runnable {
        override fun run() {
            tick()
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            h.postDelayed(this, if (interactive) 5_000 else 60_000)
        }
    }

    /** When the screen went off; a heartbeat gap that started after it is just the phone sleeping. */
    @Volatile private var screenOffMs = 0L

    /**
     * Screen on: check right away. The poll is an uptime-based delay that does not advance while the phone sleeps, so
     * without this the heartbeat stayed stale for ~20 s after unlocking and the home screen briefly showed
     * "monitoring not fully working" (vivo overnight log, 2026-09-26).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffMs = System.currentTimeMillis()
                Intent.ACTION_SCREEN_ON -> { h.removeCallbacks(poll); h.post(poll) }
            }
        }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            store.diag("installed", pkg, mapOf("replacing" to i.getBooleanExtra(Intent.EXTRA_REPLACING, false)))
            for (delay in listOf(3_000L, 2 * 60_000L, 30 * 60_000L)) h.postDelayed({ checkNewApp(pkg) }, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        store = Store(this)
        usage = UsageMonitor(this)
        Alerts.ensureChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, Alerts.CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("守門員正在注意彈出廣告")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        thread = HandlerThread("guard").apply { start() }
        h = Handler(thread.looper)
        registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        store.diag("svc_start", fields = mapOf("sinceLastHeartbeatSec" to store.heartbeatMs.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 }))
        h.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        store.diag("svc_stop")
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        h.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        val now = System.currentTimeMillis()
        val prevBeat = store.heartbeatMs
        // A long gap while we were supposed to run means the phone froze or killed the monitor (common on some brands).
        if (prevBeat > 0 && now - prevBeat > 5 * 60_000L) store.diag("hb_gap", fields = mapOf("gapSec" to (now - prevBeat) / 1000,
            // true = the gap began after the screen went off (normal sleep), false = the monitor was frozen or killed
            "screenOff" to (screenOffMs in prevBeat..now)))
        store.heartbeatMs = now
        val caps = Capabilities.read(this)
        val snap = "usage=${caps.usageAccess} notif=${caps.notificationAccess} post=${caps.postNotifications} battery=${caps.batteryUnrestricted} closer=${AdCloserService.isEnabled(this)}"
        if (snap != store.lastCapsSnapshot) { store.diag("caps", fields = mapOf("now" to snap, "before" to store.lastCapsSnapshot)); store.lastCapsSnapshot = snap }
        if (!caps.usageAccess) return // shown as "not monitoring pop-ups" in the UI
        val (list, summaries) = runCatching { usage.findIntrusions(WINDOW_MS) }.getOrElse { return }
        val last = store.lastUsagePollMs
        list.filter { it.timeMs > last }.forEach {
            store.log("intrusion", it.packageName, mapOf("cls" to it.className, "over" to it.coveredPackage, "kind" to it.kind.name), it.timeMs)
            store.diag("seen", it.packageName, mapOf("lagMs" to now - it.timeMs))
        }
        store.lastUsagePollMs = now
        summaries.filter { it.level == IntrusionSummary.Level.CONFIRMED && it.lastMs > last }
            .forEach { Alerts.maybeAlertIntrusion(this, it.packageName, it.count) }
    }

    private fun checkNewApp(pkg: String) {
        val e = runCatching { scanner.scan(pkg) }.getOrNull() ?: return
        val v = StaticRisk.assess(e.facts)
        store.diag("scan", pkg, Diagnostics.scanFields(e.facts, v) + ("src" to "new_app"))
        if (v.level == RiskLevel.HIGH || v.level == RiskLevel.MEDIUM) Alerts.maybeAlertNewApp(this, pkg, v.reasons.firstOrNull() ?: "")
    }

    companion object {
        @Volatile var running = false
        const val WINDOW_MS = 30 * 60 * 1000L

        fun start(c: Context) {
            val i = Intent(c, GuardService::class.java)
            runCatching { c.startForegroundService(i) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) GuardService.start(c)
    }
}
