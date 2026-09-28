package org.elderguard.redirect

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast
import org.elderguard.data.Store
import org.elderguard.detect.AdSdkCatalog
import org.elderguard.monitor.Alerts

/**
 * Anti-redirect, running inside the accessibility service (the only place a normal app learns about app switches
 * and taps as they happen). Android gives NO normal app a way to stop another app's startActivity() before it
 * happens; what this does is: notice the switch within ~0.3 s, judge it, and undo it (BACK, then relaunch the source,
 * then HOME) or ask. Launch-time prevention needs Device Owner strict mode.
 */
class RedirectGuard(private val svc: AccessibilityService) {
    private val h = Handler(Looper.getMainLooper())
    private val store = Store(svc)
    private val rules = RedirectRuleRepository.get(svc)
    private val history = RedirectHistoryRepository.get(svc)
    private val apps = ProtectedAppManager.get(svc)
    /** source -> last block time, seeded from history so "this app redirected before" survives a service restart. */
    private val lastBlock = HashMap<String, Long>().apply {
        history.read(System.currentTimeMillis() - 24 * 3600_000L).filter { it.decision == Decision.BLOCK && it.reasons.isNotEmpty() }
            .forEach { put(it.source, maxOf(get(it.source) ?: 0, it.timeMs)) }
    }
    private val lastNotified = HashMap<String, Long>()

    private val env = object : RedirectEnv {
        override fun now() = System.currentTimeMillis()
        override fun isProtected(pkg: String) = apps.isProtected(pkg)
        override fun isAdPage(cls: String?) = AdSdkCatalog.isAdActivity(cls)
        override fun rule(source: String, target: String) = rules.find(source, target, now())
        override fun consumeGrant(source: String, target: String) = rules.consumeGrant(source, target, now())
        override fun hadBlocksRecently(source: String) = (lastBlock[source] ?: 0) > now() - 24 * 3600_000L
        override fun hasNotification(pkg: String) = pkg in activeNotificationPackages
    }

    val engine = RedirectEngine(buildClassifier(svc), env, unlock = sharedUnlock)

    /**
     * Test trace: one logcat line per window / verdict (package + class names, never screen content). Off unless a
     * debuggable build AND the test harness switched it on (the APKs handed out are debug builds).
     */
    private val trace = (svc.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
        svc.getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("trace", false)

    /**
     * After the service (re)connects (reboot, app update, OEM restart) the tracker does not know which app is in
     * front until the next switch, so the first redirect would have no source and slip through (emulator test:
     * reinstall -> game -> auto-jump to shop was not judged). Seed it from the system's screen history.
     */
    private fun seedForeground(exceptPkg: String, t: Long) {
        val usm = svc.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return
        val events = runCatching { usm.queryEvents(t - 30 * 60_000L, t) }.getOrNull() ?: return
        val e = android.app.usage.UsageEvents.Event()
        var last: Triple<String, String?, Long>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != exceptPkg &&
                !engine.sys.isTransparent(e.packageName, e.className)) last = Triple(e.packageName, e.className, e.timeStamp)
        }
        // its real "came to front" time, so a seeded source does not look like it jumped away instantly
        last?.let { engine.tracker.setForeground(it.first, it.second, it.third) }
    }

    fun onWindow(pkg: String, cls: String?, t: Long, popupHandled: Boolean) {
        if (engine.tracker.current == null) seedForeground(pkg, t)
        val before = engine.tracker.current
        val r = engine.onWindow(pkg, cls, t)
        if (trace) android.util.Log.d("EG-REDIRECT", "win $pkg/${cls?.substringAfterLast('.')} front:$before->${engine.tracker.current} " +
            (r?.let { "${it.second.decision} ${it.second.reasons} score=${it.second.score} dwell=${it.first.fromDwellMs} ia=${it.second.interactionAgeMs}" } ?: "-"))
        updateShakeSensor()
        if (r == null || !apps.enabled) return
        val (tr, v) = r
        // Only switches FROM a protected app are judged or recorded: opening apps from the home screen, recents or
        // the share sheet must not turn the history into a timeline of everything the user opens (review L2).
        if (!apps.isProtected(tr.from)) return
        if (v.decision == Decision.ALLOW) { log(tr, v, null); return }
        if (popupHandled) { log(tr, v, "popup_handled"); return } // the pop-up closer already acted on this window
        // Wait a moment: a notification tap or a click event can arrive a little after the window event.
        h.postDelayed({ act(tr) }, SETTLE_MS)
    }

    private fun act(tr: Transition) {
        if (engine.tracker.current != tr.to) return // the user (or the app) already left the target
        val v = engine.judge(tr)
        when (v.decision) {
            Decision.ALLOW -> log(tr, v, "settled_allow")
            Decision.ASK -> { log(tr, v, "prompt"); RedirectPromptActivity.ask(svc, tr, v) }
            Decision.BLOCK -> {
                val acted = engine.recordBlocked(tr, v)
                lastBlock[tr.from] = tr.timeMs
                val outcome = RedirectRecoveryManager(svc, engine).recover(tr, acted.goHome)
                log(tr, acted, outcome)
                notifyBlocked(tr, acted)
            }
        }
    }

    private fun log(tr: Transition, v: Verdict, outcome: String?) {
        history.add(RedirectEvent(tr.timeMs, tr.from, tr.to, v.decision, v.reasons, v.risk, v.score, v.interactionAgeMs, v.matchedRule, outcome))
        if (v.decision != Decision.ALLOW) store.diag("redirect", tr.from, mapOf("to" to tr.to, "d" to v.decision.name,
            "r" to v.reasons.joinToString(",") { it.name }, "score" to v.score, "ia" to v.interactionAgeMs, "o" to outcome,
            "fromCls" to tr.fromClass?.substringAfterLast('.'), "dwell" to tr.fromDwellMs))
    }

    /** Calm notification with "open anyway"; at most once per pair per [NOTIFY_COOLDOWN_MS], none during a loop. */
    private fun notifyBlocked(tr: Transition, v: Verdict) {
        val key = "${tr.from}>${tr.to}"
        val now = System.currentTimeMillis()
        val src = Alerts.label(svc, tr.from); val dst = Alerts.label(svc, tr.to)
        if (now - (lastNotified[key] ?: 0) < NOTIFY_COOLDOWN_MS) return
        lastNotified[key] = now
        Toast.makeText(svc, if (v.goHome) "「$src」一直打開「$dst」，已回到桌面" else "已擋下「$dst」", Toast.LENGTH_SHORT).show()
        ensureChannel(svc)
        val open = PendingIntent.getActivity(svc, key.hashCode(),
            RedirectPromptActivity.openAnywayIntent(svc, tr.from, tr.to), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (v.goHome) "已停止一直跳出的 App" else "已阻止疑似廣告跳轉"
        val text = "「$src」\n企圖開啟「$dst」"
        svc.getSystemService(NotificationManager::class.java).notify(key.hashCode(), Notification.Builder(svc, CH_REDIRECT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.replace('\n', ' '))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(null, "仍然開啟", open).build())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build())
    }

    // ------------------------------------------------------------ shake sensor (only while a protected app is in front)

    private val sensors = svc.getSystemService(SensorManager::class.java)
    private var sensorOn = false
    private val shake = ShakeDetector { t -> engine.onShake(t) }
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) = shake.onSample(e.values[0], e.values[1], e.values[2], System.currentTimeMillis())
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun updateShakeSensor() {
        val front = engine.tracker.current
        val want = apps.enabled && front != null && apps.isProtected(front) && svc.getSystemService(PowerManager::class.java).isInteractive
        if (want == sensorOn) return
        val acc = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (want) sensors.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_UI)
        else sensors.unregisterListener(sensorListener)
        sensorOn = want
    }

    fun onScreenOff() { if (sensorOn) { sensors?.unregisterListener(sensorListener); sensorOn = false } }

    fun stop() { onScreenOff(); h.removeCallbacksAndMessages(null) }

    // ------------------------------------------------------------ answers from the prompt

    fun answered(tr: Transition, answer: RedirectPromptActivity.Answer) {
        val now = System.currentTimeMillis()
        when (answer) {
            RedirectPromptActivity.Answer.YES -> { engine.userAllowed(tr, now); history.outcome(now, tr.from, tr.to, "user_allow") }
            RedirectPromptActivity.Answer.ALWAYS -> {
                rules.put(tr.from, tr.to, Decision.ALLOW); engine.userAllowed(tr, now); history.outcome(now, tr.from, tr.to, "user_allow_always")
            }
            RedirectPromptActivity.Answer.NO -> {
                history.outcome(now, tr.from, tr.to, "user_block")
                // the prompt closes first; then the target underneath gets BACK
                h.postDelayed({ if (engine.tracker.current == tr.to) RedirectRecoveryManager(svc, engine).recover(tr, goHome = false) }, 350)
            }
            RedirectPromptActivity.Answer.DISMISSED -> history.outcome(now, tr.from, tr.to, "dismissed")
        }
    }

    companion object {
        const val SETTLE_MS = 350L
        const val NOTIFY_COOLDOWN_MS = 10_000L
        const val CH_REDIRECT = "redirect_blocked"

        /** The running guard (same process as the prompt activity), or null when the accessibility service is off. */
        @Volatile var instance: RedirectGuard? = null

        /** Shared with the UI's "pause protection" buttons; memory only, so a reboot always ends a pause. */
        val sharedUnlock = TemporaryUnlockManager(System::currentTimeMillis)

        /** Packages with a notification in the shade (kept by NotificationWatcher). */
        @Volatile var activeNotificationPackages: Set<String> = emptySet()

        fun ensureChannel(c: Context) {
            val ch = NotificationChannel(CH_REDIRECT, "已阻止的 App 跳轉", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) }
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /**
         * System flows from roles and intent handlers on THIS phone (not a hardcoded brand list): home, dialer, SMS,
         * camera, documents / photo picker, installers, settings, keyboards, share sheet, WebView provider, browsers.
         */
        fun buildClassifier(c: Context): SystemFlowClassifier {
            val pm = c.packageManager
            fun handlers(i: Intent, flags: Int = PackageManager.MATCH_DEFAULT_ONLY) =
                runCatching { pm.queryIntentActivities(i, flags).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
            val homes = handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).toSet()
            val imes = runCatching { c.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName } }.getOrDefault(emptyList()).toSet()
            val sys = HashSet(SystemFlowClassifier.CORE_SYSTEM)
            sys += handlers(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            runCatching { c.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()?.let { sys += it }
            runCatching { Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull()?.let { sys += it }
            sys += handlers(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            sys += handlers(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
            sys += handlers(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
            sys += handlers(Intent(MediaStore.ACTION_PICK_IMAGES))
            sys += handlers(Intent(Settings.ACTION_SETTINGS))
            sys += handlers(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(Uri.parse("content://x/y.apk")))
            sys += handlers(Intent(Intent.ACTION_DELETE, Uri.parse("package:x")))
            sys += handlers(Intent("android.content.pm.action.REQUEST_PERMISSIONS"))
            runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()?.let { sys += it }
            val browsers = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL).toSet() -
                setOf(c.packageName)
            // A browser is also a normal "place" (ads open it): it is not a system target, even if preinstalled.
            return SystemFlowClassifier(homes, imes, sys - browsers - SystemFlowClassifier.KNOWN_STORES, self = c.packageName, browsers = browsers)
        }
    }
}
