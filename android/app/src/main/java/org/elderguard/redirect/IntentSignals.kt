package org.elderguard.redirect

/**
 * Short-lived memory of what the USER did, from accessibility events: taps (TYPE_VIEW_CLICKED / LONG_CLICKED),
 * scrolls, notification taps (NotificationListener, REASON_CLICK) and shakes. Only package, class name and time are
 * kept — never the text on screen, never what was typed — and only for [KEEP_MS].
 *
 * Android gives a normal app no way to see raw touches without taking them over (touch exploration / motion-event
 * interception would change how the phone responds to the user), so games drawn on a SurfaceView (Unity etc.) send
 * no click events at all. [observable] tells the engine whether "no click" means anything for a given app.
 */
class UserIntentTracker {
    data class Click(val pkg: String, val cls: String?, val timeMs: Long)

    private val clicks = ArrayDeque<Click>()
    private val notificationTaps = ArrayDeque<Pair<String, Long>>()
    /** Packages that produced any click / scroll event, with the last time: proves click events work there. */
    private val interactionSeen = HashMap<String, Long>()
    @Volatile var lastShakeMs = 0L
        private set
    @Volatile private var lastSystemUiMs = 0L

    fun onSystemUi(t: Long) { lastSystemUiMs = t }
    fun systemUiWithin(t: Long, windowMs: Long) = lastSystemUiMs != 0L && t - lastSystemUiMs in 0..windowMs

    @Synchronized fun onClick(pkg: String, cls: String?, t: Long) {
        clicks.addLast(Click(pkg, cls, t)); trim(t)
        interactionSeen[pkg] = t
    }

    /** Scrolls prove the user is there and that this app reports interaction, but are not a request to open anything. */
    @Synchronized fun onScroll(pkg: String, t: Long) { interactionSeen[pkg] = t }

    @Synchronized fun onNotificationTap(pkg: String, t: Long) { notificationTaps.addLast(pkg to t); trim(t) }

    fun onShake(t: Long) { lastShakeMs = t }

    /** The last click inside [pkg] in [windowMs] before [t], or null. */
    @Synchronized fun lastClick(pkg: String, t: Long, windowMs: Long): Click? =
        clicks.lastOrNull { it.pkg == pkg && t - it.timeMs in 0..windowMs }

    @Synchronized fun notificationTapped(target: String, t: Long, windowMs: Long = NOTIFICATION_WINDOW_MS) =
        notificationTaps.any { it.first == target && t - it.second in 0..windowMs }

    /** True when [pkg] has reported clicks/scrolls within [sinceMs] — so a missing click there is meaningful. */
    @Synchronized fun observable(pkg: String, t: Long, sinceMs: Long = OBSERVABLE_MS) = interactionSeen[pkg]?.let { t - it <= sinceMs } == true

    /** A shake up to [windowMs] before [t] — or just after it: the ad reacts to the first jolt, the sensor is slower. */
    fun shookWithin(t: Long, windowMs: Long) = lastShakeMs != 0L && t - lastShakeMs in -SHAKE_LATE_MS..windowMs

    private fun trim(now: Long) {
        while (clicks.isNotEmpty() && now - clicks.first().timeMs > KEEP_MS) clicks.removeFirst()
        while (notificationTaps.isNotEmpty() && now - notificationTaps.first().second > KEEP_MS) notificationTaps.removeFirst()
        if (clicks.size > 64) clicks.removeFirst()
    }

    companion object {
        const val KEEP_MS = 60_000L
        const val NOTIFICATION_WINDOW_MS = 6_000L
        /** An app that reported a click or scroll in the last 30 minutes reports clicks. */
        const val OBSERVABLE_MS = 30 * 60_000L
        const val SHAKE_LATE_MS = 500L
    }
}

/**
 * Shake detection from accelerometer samples (m/s², gravity included): two jolts of more than [threshold] away from
 * 1 g within [windowMs], or ONE jolt beyond [strong] (emulator test: the ad jumped on the first jolt, before a second
 * one arrived). Ads' "搖一搖" triggers vary by SDK, so thresholds are low; a shake is only a SIGNAL, never enough to
 * block on its own.
 */
class ShakeDetector(
    private val threshold: Float = 4.5f,
    private val strong: Float = 9f,
    private val windowMs: Long = 1_000,
    private val onShake: (Long) -> Unit,
) {
    private var firstJoltMs = 0L
    private var jolts = 0

    fun onSample(x: Float, y: Float, z: Float, tMs: Long) {
        val g = kotlin.math.sqrt(x * x + y * y + z * z)
        val dev = kotlin.math.abs(g - 9.81f)
        if (dev < threshold) return
        if (dev >= strong) { jolts = 0; firstJoltMs = 0; onShake(tMs); return }
        if (tMs - firstJoltMs > windowMs) { firstJoltMs = tMs; jolts = 0 }
        if (++jolts >= 2) { jolts = 0; firstJoltMs = 0; onShake(tMs) }
    }
}

/**
 * Stops "game -> shop -> (guard goes back) -> game -> shop -> ..." loops. [LOOP_COUNT] blocks of the same pair within
 * [LOOP_WINDOW_MS] = a loop: the guard goes to the home screen instead of back into the game, and for [COOLDOWN_MS]
 * repeats of that pair are handled the same way without more notifications.
 */
class RedirectLoopDetector(private val clock: () -> Long) {
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val cooldownUntil = HashMap<String, Long>()

    @Synchronized fun inCooldown(source: String, target: String) = (cooldownUntil["$source>$target"] ?: 0) > clock()

    /** Records one blocked redirect; returns true when this one completes a loop. */
    @Synchronized fun recordBlock(source: String, target: String): Boolean {
        val k = "$source>$target"
        val now = clock()
        val q = hits.getOrPut(k) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > LOOP_WINDOW_MS) q.removeFirst()
        if (q.size >= LOOP_COUNT) { cooldownUntil[k] = now + COOLDOWN_MS; q.clear(); return true }
        return false
    }

    /** How many times this pair was blocked recently (for the "repeated" signal). */
    @Synchronized fun recentCount(source: String, target: String, windowMs: Long = 10 * 60_000L): Int {
        val now = clock()
        return hits["$source>$target"]?.count { now - it <= windowMs } ?: 0
    }

    companion object {
        const val LOOP_COUNT = 3
        const val LOOP_WINDOW_MS = 10_000L
        const val COOLDOWN_MS = 30_000L
    }
}

/**
 * "暫停跳轉保護": 30 s – 10 min, then protection comes back by itself. Kept in memory only, so a reboot (or the
 * process being killed) ends it — protection resumes; it never stays off by accident.
 */
class TemporaryUnlockManager(private val clock: () -> Long) {
    @Volatile var until = 0L
        private set

    fun unlock(ms: Long) { until = clock() + ms.coerceIn(10_000, 10 * 60_000L) }
    fun cancel() { until = 0 }
    fun active() = clock() < until
    fun remainingMs() = (until - clock()).coerceAtLeast(0)

    companion object {
        val CHOICES_MS = listOf(30_000L, 60_000L, 5 * 60_000L, 10 * 60_000L)
    }
}
