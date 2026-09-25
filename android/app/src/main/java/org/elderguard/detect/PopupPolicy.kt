package org.elderguard.detect

/**
 * Real-time decision for the optional ad-closer (accessibility service): given a stream of "window became active"
 * events, decide whether the new window is a page an app put over another app, and whether to close it.
 *
 * Policy requested by the user (2026-09-25): do NOT act on the first pop-up; act only when the same app pops up
 * over other apps again ("連續跳出才攔截"): the [blockFrom]-th intrusion within [windowMs] and later are closed.
 *
 * An intrusion needs all of:
 *  - the new window belongs to a different app than the one the user was in;
 *  - the user did not come from the notification shade (tapping a notification is a user choice);
 *  - the page is an ad SDK's full-screen activity, or the app is user-installed with a hidden launcher icon.
 * In-app ads (the app's own screen -> its own ad) never match, because the previous window is the same app.
 */
class PopupPolicy(
    /** Packages that can be covered but never be a source (our app, launchers). */
    private val neverSource: Set<String>,
    /** Windows from these packages mean "the user is acting on the system UI" (notification shade, etc.). */
    private val userInitiatedSources: Set<String> = setOf("com.android.systemui"),
    /** Input methods pop up windows constantly; they never change "which app the user is in". */
    private val transparentPackages: Set<String> = emptySet(),
    private val hiddenIconUserApp: (String) -> Boolean = { false },
    private val isLauncherEntry: (String, String?) -> Boolean = { _, _ -> false },
    /** True when the user closed the guard with Back just before [timeMs]: the page underneath is a return, not a pop-up. */
    private val userJustLeftGuard: (Long) -> Boolean = { false },
    private val windowMs: Long = 30 * 60 * 1000L,
    private val blockFrom: Int = 2,
) {
    data class Decision(val intrusion: Boolean, val count: Int, val block: Boolean, val coveredPackage: String?) {
        companion object { val NONE = Decision(false, 0, false, null) }
    }

    private var prev: String? = null
    /** The app that is in front uninvited (it popped up over [intruderCovered]) until the user returns elsewhere. */
    private var intruder: String? = null
    private var intruderCovered: String? = null
    /** Time of each app's latest ad page (not any window: see [onWindow]). */
    private val lastAdSeen = HashMap<String, Long>()
    private val seen = HashMap<String, ArrayDeque<Long>>()

    /** Pre-load intrusions already seen by other monitors (e.g. the usage-history monitor) so counting continues. */
    fun seed(pkg: String, timeMs: Long) {
        val q = seen.getOrPut(pkg) { ArrayDeque() }
        // two monitors log the same pop-up; entries within 3 s are one pop-up
        if (q.none { kotlin.math.abs(it - timeMs) < 3_000 }) { q.addLast(timeMs); q.sortedBy { it }.let { q.clear(); q.addAll(it) } }
    }

    fun onWindow(pkg: String, className: String?, timeMs: Long): Decision {
        if (pkg in transparentPackages) return Decision.NONE
        val p = prev
        prev = pkg
        val isAd = AdSdkCatalog.isAdActivity(className)
        // Only ad pages restart the "same pop-up" gap: the app's own overlay window, shown 0.1 s before its next ad,
        // otherwise hid that ad (emulator 0.5.0 field-log replay: TextView overlay -> TTFullScreenExpressVideoActivity).
        val last = if (isAd) lastAdSeen.put(pkg, timeMs) else lastAdSeen[pkg]
        if (pkg in userInitiatedSources || pkg in neverSource || p == null || p in userInitiatedSources) {
            intruder = null
            return Decision.NONE
        }
        if (pkg == p) {
            // Same app again. Normally in-app navigation — except when this app is in front uninvited and, after a
            // pause, shows another ad page on top of its own ad: from the user's view it "popped up again"
            // (emulator test 0.3.2: the 1st ad was left open, the 2nd came on top of it and was missed).
            val repop = intruder == pkg && isAd && last != null && timeMs - last >= REPOP_GAP_MS
            return if (repop) record(pkg, timeMs, intruderCovered) else Decision.NONE
        }
        // vivo 0.5.0 field log: alert -> guard -> Back landed on the app's already-open ad page and was counted.
        if (userJustLeftGuard(timeMs)) {
            intruder = null
            return Decision.NONE
        }
        val suspicious = isAd || (!isLauncherEntry(pkg, className) && hiddenIconUserApp(pkg))
        if (!suspicious) {
            intruder = null
            return Decision.NONE
        }
        intruder = pkg
        intruderCovered = p
        return record(pkg, timeMs, p)
    }

    private fun record(pkg: String, timeMs: Long, covered: String?): Decision {
        val q = seen.getOrPut(pkg) { ArrayDeque() }
        q.addLast(timeMs)
        while (q.isNotEmpty() && timeMs - q.first() > windowMs) q.removeFirst()
        return Decision(true, q.size, q.size >= blockFrom, covered)
    }

    /** Which app is currently considered in front. */
    fun current(): String? = prev

    /**
     * Tell the policy which app is really in front. Needed after our own BACK/HOME: Android does not always send a
     * window-state event for the app that comes back, and a stale "front app" made later pop-ups of the same app look
     * like in-app navigation (bug found in the emulator test of 0.3.1).
     */
    fun setForeground(pkg: String?) {
        if (pkg == null) return
        if (pkg != prev) intruder = null
        prev = pkg
    }

    companion object {
        /** Ad pages chained within this gap belong to the same pop-up (e.g. web ad -> video ad). */
        const val REPOP_GAP_MS = 5_000L
    }
}
