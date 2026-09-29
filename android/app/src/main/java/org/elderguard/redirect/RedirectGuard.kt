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
    /** source -> last block time; process-wide (see [sharedEngine]), seeded from history on the first start. */
    private val lastBlock = sharedLastBlock ?: HashMap<String, Long>().apply {
        history.read(System.currentTimeMillis() - 24 * 3600_000L).filter { it.decision == Decision.BLOCK && it.reasons.isNotEmpty() }
            .forEach { put(it.source, maxOf(get(it.source) ?: 0, it.timeMs)) }
    }.also { sharedLastBlock = it }
    private val lastNotified = HashMap<String, Long>()

    private val env = object : RedirectEnv {
        override fun now() = System.currentTimeMillis()
        override fun isProtected(pkg: String) = apps.isProtected(pkg)
        override fun isAdPage(cls: String?) = AdSdkCatalog.isAdActivity(cls)
        override fun rule(source: String, target: String) = rules.find(source, target, now())
        override fun consumeGrant(source: String, target: String) = rules.consumeGrant(source, target, now())
        override fun hadBlocksRecently(source: String) = (lastBlock[source] ?: 0) > now() - 24 * 3600_000L
        override fun hasNotification(pkg: String) = pkg in activeNotificationPackages
        override fun learnable(pkg: String) = apps.enabled && apps.learnable(pkg)
        override fun learnSource(pkg: String) {
            apps.learn(pkg)
            store.diag("redirect_learn", pkg = pkg) // consented diagnostics only; the app now shows as protected
        }
    }

    /**
     * One engine per PROCESS, not per service connection: the accessibility service can be unbound and rebound while
     * the process lives (vivo field test: it happened every 10–20 s while a UI-automation tool was connected; some
     * OEMs do it too). A fresh engine forgot which apps report taps and which pairs were just blocked, so "repeated"
     * jumps were only asked about. The system-flow sets are rebuilt (apps may have changed); the memory is kept.
     */
    val engine: RedirectEngine = sharedEngine?.also { it.env = env; it.sys = buildClassifier(svc); it.tracker.forgetFront() }
        ?: RedirectEngine(buildClassifier(svc), env, unlock = sharedUnlock).also { sharedEngine = it }

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

    /** The first unwanted-looking switch, waiting [SETTLE_MS] for the phone to settle before acting. */
    private var pending: Transition? = null
    private var pendingStart = 0L
    private val settleTask = Runnable { val tr = pending; pending = null; if (tr != null) act(tr) }

    fun onWindow(pkg: String, cls: String?, t: Long, popupHandled: Boolean) {
        if (engine.tracker.current == null) seedForeground(pkg, t)
        val before = engine.tracker.current
        val r = engine.onWindow(pkg, cls, t)
        if (trace) android.util.Log.d("EG-REDIRECT", "win $pkg/${cls?.substringAfterLast('.')} front:$before->${engine.tracker.current} " +
            (r?.let { "${it.second.decision} ${it.second.reasons} score=${it.second.score} dwell=${it.first.fromDwellMs} ia=${it.second.interactionAgeMs}" } ?: "-"))
        updateShakeSensor()
        if (r == null || !apps.enabled) return
        val (tr, v) = r
        // Start-up flapping: while an app starts, focus can bounce between it and the previous app for a moment
        // (vivo, YouTube -> Shopee splash -> YouTube -> Shopee main within 1.2 s; each bounce raised its own
        // question). Keep the FIRST switch and look again once things settle.
        val p = pending
        if (p != null && t - pendingStart < MAX_SETTLE_MS && setOf(tr.from, tr.to) == setOf(p.from, p.to)) {
            h.removeCallbacks(settleTask); h.postDelayed(settleTask, SETTLE_MS)
            return
        }
        // After acting on a pair (question shown / sent back), bounces between the same two apps for a few seconds
        // are the start-up and the recovery settling, not new switches (vivo: a second question appeared).
        if (t < actedUntil && setOf(tr.from, tr.to) == actedPair) return
        // Same after an ALLOWED switch: the target's start-up bounce is part of that same (allowed) opening, not a new
        // "instant" jump (vivo: YouTube -> Chrome allowed, then the bounce was questioned as INSTANT_LAUNCH).
        // Only after ALLOW — a re-jump right after a BLOCK must still be judged (loop protection).
        if (t < allowedUntil && setOf(tr.from, tr.to) == allowedPair) return
        // Only switches FROM a protected app are judged or recorded: opening apps from the home screen, recents or
        // the share sheet must not turn the history into a timeline of everything the user opens (review L2).
        if (!apps.isProtected(tr.from) || v.primary == ReasonCode.GUARD_RECOVERY) return
        if (v.decision == Decision.ALLOW) {
            // Fold the target's start-up bounce only after the user clearly OPENED it (tap, notification, share, a rule).
            // Not after a return or a merely low-score switch: vivo test, "Shopee -> back to the tester" was allowed as
            // a return and the tester's jump to Shopee 1 s later was then ignored as a "bounce".
            if (v.primary in USER_OPENED) { allowedPair = setOf(tr.from, tr.to); allowedUntil = t + MAX_SETTLE_MS }
            // going to the home screen / a system screen is not an app opening another app: not worth a history line
            // (vivo: the history was full of "YouTube → 系統桌面 允許")
            if (v.primary != ReasonCode.SYSTEM_FLOW) log(tr, v, null)
            return
        }
        if (popupHandled) { log(tr, v, "popup_handled"); return } // the pop-up closer already acted on this window
        // Wait a moment: a notification tap or a click event can arrive a little after the window event.
        pending = tr; pendingStart = t
        h.removeCallbacks(settleTask); h.postDelayed(settleTask, SETTLE_MS)
    }

    private var allowedPair: Set<String> = emptySet()
    private var allowedUntil = 0L
    private var actedPair: Set<String> = emptySet()
    private var actedUntil = 0L

    private fun act(tr: Transition) {
        if (engine.tracker.current != tr.to) return // the user (or the app) already left the target
        val v = engine.judge(tr)
        when (v.decision) {
            Decision.ALLOW -> log(tr, v, "settled_allow")
            Decision.ASK -> {
                log(tr, v, "prompt")
                // While the question is up, bounces between the two apps are settling, not new switches; the answer
                // decides. (Not after a BLOCK: a re-jump right after being sent back must still count, for loops.)
                actedPair = setOf(tr.from, tr.to); actedUntil = System.currentTimeMillis() + QUIET_MS
                RedirectPromptActivity.ask(svc, tr, v)
            }
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
        if (answer != RedirectPromptActivity.Answer.COVERED && answer != RedirectPromptActivity.Answer.NO) actedUntil = 0 // new jumps are judged again
        when (answer) {
            RedirectPromptActivity.Answer.YES -> {
                engine.userAllowed(tr, now); history.outcome(now, tr.from, tr.to, "user_allow")
                allowedPair = setOf(tr.from, tr.to); allowedUntil = now + USER_ALLOWED_MS // the start-up bounce is part of what the user allowed
            }
            RedirectPromptActivity.Answer.ALWAYS -> {
                rules.put(tr.from, tr.to, Decision.ALLOW); engine.userAllowed(tr, now); history.outcome(now, tr.from, tr.to, "user_allow_always")
                allowedPair = setOf(tr.from, tr.to); allowedUntil = now + USER_ALLOWED_MS
            }
            RedirectPromptActivity.Answer.NO -> {
                history.outcome(now, tr.from, tr.to, "user_block")
                // The user says it was unwanted: it counts like a block (repeat / loop / "this app redirected before"),
                // so the next jump of this pair is blocked without asking again.
                engine.recordBlocked(tr, Verdict(Decision.BLOCK, listOf(ReasonCode.USER_BLOCKED), RiskLevel.HIGH, 100, null))
                lastBlock[tr.from] = now
                // Bounces between the two apps while the question closes and BACK runs are not new switches
                // (vivo: a second question flashed). But the pair is checked again right after: if the target is
                // still (or again) in front, it is blocked and counted for loop protection.
                actedPair = setOf(tr.from, tr.to); actedUntil = now + ANSWER_QUIET_MS
                h.postDelayed({ if (engine.tracker.current == tr.to) RedirectRecoveryManager(svc, engine).recover(tr, goHome = false) }, 350)
                h.postDelayed({
                    if (engine.tracker.current != tr.to) return@postDelayed
                    val v = engine.recordBlocked(tr, Verdict(Decision.BLOCK, listOf(ReasonCode.USER_BLOCKED), RiskLevel.HIGH, 100, null))
                    log(tr, v, RedirectRecoveryManager(svc, engine).recover(tr, v.goHome))
                }, ANSWER_QUIET_MS + 100)
            }
            RedirectPromptActivity.Answer.DISMISSED -> history.outcome(now, tr.from, tr.to, "dismissed")
            RedirectPromptActivity.Answer.SUPERSEDED -> history.outcome(now, tr.from, tr.to, "superseded")
            RedirectPromptActivity.Answer.COVERED -> {
                // Real apps open a second page right after their splash screen, which buried the question (vivo field
                // test, LINE -> Shopee). If the target is still in front, ask again once it has settled (max 2 times);
                // if the user left for home or another app, that is their answer.
                val key = "${tr.from}>${tr.to}@${tr.timeMs}"
                val n = reraised[key] ?: 0
                if (n < 2 && now - tr.timeMs < 15_000) {
                    reraised[key] = n + 1
                    h.postDelayed({
                        if (engine.tracker.current == tr.to) RedirectPromptActivity.ask(svc, tr, engine.judge(tr))
                        else history.outcome(System.currentTimeMillis(), tr.from, tr.to, "left")
                    }, 900)
                } else if (engine.tracker.current == tr.to) {
                    // The target keeps pushing itself over the question (3rd time): that is how an ad would bury it.
                    // Treat it as unwanted — back to the source; "仍然開啟" in the notification still works.
                    val v = engine.recordBlocked(tr, Verdict(Decision.BLOCK, listOf(ReasonCode.REPEATED_REDIRECT), RiskLevel.HIGH, 100, null))
                    lastBlock[tr.from] = now
                    log(tr, v, "covered_block:" + RedirectRecoveryManager(svc, engine).recover(tr, v.goHome))
                    notifyBlocked(tr, v)
                } else history.outcome(now, tr.from, tr.to, "dismissed")
            }
        }
    }

    private val reraised = HashMap<String, Int>()

    companion object {
        const val SETTLE_MS = 350L
        /** Longest time start-up flapping between the same two apps is folded into the first switch. */
        const val MAX_SETTLE_MS = 2_500L
        /** While a question is showing, bounces between the same two apps are ignored for up to this long. */
        const val QUIET_MS = 3_000L
        /** After "不要": long enough for the question to close and BACK (x2) to run. */
        const val ANSWER_QUIET_MS = 2_500L
        /** After "要／以後都允許", bounces between the pair are part of what the user allowed. */
        const val USER_ALLOWED_MS = 10_000L
        const val NOTIFY_COOLDOWN_MS = 10_000L
        const val CH_REDIRECT = "redirect_blocked"

        /** The running guard (same process as the prompt activity), or null when the accessibility service is off. */
        @Volatile var instance: RedirectGuard? = null

        private val USER_OPENED = setOf(ReasonCode.USER_CLICK, ReasonCode.NOTIFICATION_CLICK, ReasonCode.SHARE_FLOW,
            ReasonCode.KNOWN_ALLOW_RULE, ReasonCode.USER_ALLOW_ONCE, ReasonCode.USER_ALLOW_ALWAYS, ReasonCode.AUTH_FLOW)

        @Volatile private var sharedEngine: RedirectEngine? = null
        @Volatile private var sharedLastBlock: HashMap<String, Long>? = null

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
            // Candidates come from declarations; the classifier trusts only what an app cannot declare for itself
            // (review B1/B5): the SYSTEM flag, the home screen actually in use, the keyboards actually enabled, and the
            // default phone / SMS apps the user chose. Same rule as the pop-up detectors (TrustedRoles).
            val roles = org.elderguard.monitor.TrustedRoles(c)
            val candidates = HashSet(SystemFlowClassifier.CORE_SYSTEM)
            systemFlowIntents().values.forEach { candidates += handlers(it) }
            runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()?.let { candidates += it }
            val userDefaults = listOfNotNull(
                runCatching { c.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull(),
                runCatching { Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull(),
            ).toSet()
            val browsers = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL).toSet() - setOf(c.packageName)
            val systemUis = runCatching {
                pm.getInstalledApplications(0).filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && it.packageName.contains("systemui") }
                    .map { it.packageName }
            }.getOrDefault(emptyList()).toSet() + SystemFlowClassifier.SYSTEM_UI
            // A browser is also a normal "place" (ads open it): it is not a system target, even if preinstalled.
            return SystemFlowClassifier(roles.declaredHomes(), roles.enabledImes(), candidates - browsers - SystemFlowClassifier.KNOWN_STORES,
                self = c.packageName, browsers = browsers, systemUis = systemUis, isActivity = roles.isActivity,
                isSystemApp = roles.isSystemApp, defaultHome = roles.defaultHome(), userDefaults = userDefaults - browsers)
        }

        /** The system flows whose handlers are exemption CANDIDATES (only system apps among them are trusted). */
        private fun systemFlowIntents(): Map<String, Intent> = linkedMapOf(
            "dial" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")),
            "camera" to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            "open_document" to Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            "get_content_image" to Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            "pick_images" to Intent(MediaStore.ACTION_PICK_IMAGES),
            "settings" to Intent(Settings.ACTION_SETTINGS),
            "install" to Intent(Intent.ACTION_INSTALL_PACKAGE).setData(Uri.parse("content://x/y.apk")),
            "delete" to Intent(Intent.ACTION_DELETE, Uri.parse("package:x")),
            "permissions" to Intent("android.content.pm.action.REQUEST_PERMISSIONS"),
        )

        /**
         * Debug audit (review B5): how many packages DECLARE each exemption vs how many are trusted on this phone.
         * Counts only; no package names, so the output can be quoted in the public repo's discussion.
         */
        fun auditExemptions(c: Context): String {
            val pm = c.packageManager
            val sys = buildClassifier(c)
            fun isSystem(p: String) = runCatching { (pm.getApplicationInfo(p, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false)
            fun handlers(i: Intent) = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName } }
                .getOrDefault(emptyList()).toSet()
            val out = StringBuilder()
            fun line(name: String, declared: Set<String>, trusted: Set<String>) {
                val nonSys = declared.filterNot { isSystem(it) }
                out.append("$name: declared=${declared.size} trusted=${declared.count { it in trusted }} " +
                    "declared_non_system=${nonSys.size} non_system_trusted=${nonSys.count { it in trusted }}\n")
            }
            val homes = handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) - "android"
            line("launchers(HOME)", homes, sys.launchers)
            val allImes = runCatching { c.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName } }.getOrDefault(emptyList()).toSet()
            out.append("imes: declared=${allImes.size} enabled=${sys.imes.size} declared_non_system=${allImes.count { !isSystem(it) }} " +
                "enabled_non_system=${sys.imes.count { !isSystem(it) }} (only the input window is transparent, never activities)\n")
            val targets = HashSet<String>()
            systemFlowIntents().forEach { (name, i) -> val h = handlers(i) - sys.browsers; targets += h; line("  target:$name", h, sys.systemTargets) }
            line("systemTargets(all flows)", targets, sys.systemTargets)
            out.append("source-exempt among declared targets: ${targets.count { sys.isSystemSource(it) }} (all system: ${targets.filter { sys.isSystemSource(it) }.all { isSystem(it) }})\n")
            out.append("user defaults (dialer/SMS) trusted as targets: ${sys.systemTargets.count { !isSystem(it) }}, source-exempt: ${sys.systemTargets.count { !isSystem(it) && sys.isSystemSource(it) }}\n")
            out.append("systemUis: ${sys.systemUis.size} (non-system: ${sys.systemUis.count { !isSystem(it) && it != SystemFlowClassifier.SYSTEM_UI }})")
            return out.toString()
        }
    }
}
