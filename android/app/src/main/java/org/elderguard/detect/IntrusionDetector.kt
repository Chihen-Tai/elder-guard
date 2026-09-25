package org.elderguard.detect

/** One foreground (activity-resumed) event from UsageStatsManager, reduced to what detection needs. */
data class ScreenEvent(val timeMs: Long, val packageName: String, val className: String?)

/** A full-screen page that an app put on top while the user was in another app. */
data class Intrusion(
    val packageName: String,
    val timeMs: Long,
    val className: String?,
    /** The app the user was looking at when the page appeared (e.g. the launcher, Play Store, Settings). */
    val coveredPackage: String,
    val kind: Kind,
) {
    enum class Kind {
        /** An ad SDK's full-screen activity (strong evidence of an out-of-app ad). */
        AdOverOtherApp,
        /** Any activity of an app whose launcher icon is hidden (the user could not have opened it from the home screen). */
        HiddenAppOverOtherApp,
    }
}

/** Per-app conclusion over a time window. */
data class IntrusionSummary(
    val packageName: String,
    val count: Int,
    val firstMs: Long,
    val lastMs: Long,
    val coveredPackages: List<String>,
    val examples: List<Intrusion>,
    val level: Level,
) {
    /** CONFIRMED: repeated (>= [IntrusionDetector.CONFIRM_COUNT]) in the window. SUSPECTED: seen once. */
    enum class Level { CONFIRMED, SUSPECTED }
}

/**
 * Pure detection logic (no Android dependencies, unit-tested against real usage events).
 *
 * Rule: an activity of app P comes to the top right after an activity of a *different* app Q, and either
 *  - the activity class belongs to an ad SDK's full-screen ad page, or
 *  - P's launcher icon is hidden/disabled,
 * then P showed a page over Q without the user opening P. In-app interstitials (P's own screen -> P's ad) do not match,
 * because the previous page belongs to P itself.
 */
class IntrusionDetector(
    /** Packages whose pages never count as intrusions (our own app, system UI, permission dialogs, installers). */
    private val ignoredPackages: Set<String>,
    /** Returns true when the package currently has no enabled launcher icon. */
    private val hasHiddenIcon: (String) -> Boolean = { false },
    /** True when (pkg, class) is the app's own launcher entry: opening it is the user's choice, not a pop-up. */
    private val isLauncherEntry: (String, String?) -> Boolean = { _, _ -> false },
    /** Times the user closed the guard with Back; a page shown right after is the user returning, not a pop-up. */
    private val userExitTimes: List<Long> = emptyList(),
    /** Launcher apps: an ad right after one of them is often the user tapping an app whose old ad page was still open. */
    private val homePackages: Set<String> = emptySet(),
) {
    fun detect(events: List<ScreenEvent>): List<Intrusion> {
        val out = mutableListOf<Intrusion>()
        var prev: ScreenEvent? = null
        var beforePrev: ScreenEvent? = null
        var intruder: String? = null
        var covered: String? = null
        val sorted = events.sortedBy { it.timeMs }
        for ((i, e) in sorted.withIndex()) {
            val p = prev
            val pp = beforePrev
            beforePrev = p
            prev = e
            if (p == null || e.packageName in ignoredPackages) { intruder = null; continue }
            if (p.packageName == e.packageName) {
                // Same rule as PopupPolicy: an uninvited app showing another ad page after a pause popped up again.
                if (intruder == e.packageName && AdSdkCatalog.isAdActivity(e.className) && e.timeMs - p.timeMs >= PopupPolicy.REPOP_GAP_MS)
                    out += Intrusion(e.packageName, e.timeMs, e.className, covered ?: p.packageName, Intrusion.Kind.AdOverOtherApp)
                continue
            }
            if (isReturnAfterExit(userExitTimes, e.timeMs)) { intruder = null; continue }
            // The app's own permission request, then its ad: still inside the app (vivo 0.5.0, 22:33:59).
            if (p.packageName in PERMISSION_DIALOGS && pp?.packageName == e.packageName) { intruder = null; continue }
            // Tapping an app's icon brings its task to the front: an ad page left open there resumes for a moment,
            // then the app's own screen takes over (vivo 0.5.0: launcher -> MBRewardVideoActivity -> TrashActivity
            // within the same second). A real pop-up over the launcher stays on top instead.
            if (p.packageName in homePackages && AdSdkCatalog.isAdActivity(e.className) &&
                sorted.drop(i + 1).takeWhile { it.timeMs - e.timeMs <= LAUNCH_MS }
                    .any { it.packageName == e.packageName && !AdSdkCatalog.isAdActivity(it.className) }) { intruder = null; continue }
            val kind = when {
                AdSdkCatalog.isAdActivity(e.className) -> Intrusion.Kind.AdOverOtherApp
                // the icon state is read *now*; the app's own start screen was probably opened by the user before it
                // hid its icon (emulator 0.3.5: the first launch was miscounted), so it never counts
                !isLauncherEntry(e.packageName, e.className) && hasHiddenIcon(e.packageName) -> Intrusion.Kind.HiddenAppOverOtherApp
                else -> null
            }
            if (kind == null) { intruder = null; continue }
            intruder = e.packageName
            covered = p.packageName
            out += Intrusion(e.packageName, e.timeMs, e.className, p.packageName, kind)
        }
        return out
    }

    fun summarize(intrusions: List<Intrusion>): List<IntrusionSummary> =
        intrusions.groupBy { it.packageName }.map { (pkg, list) ->
            IntrusionSummary(
                packageName = pkg,
                count = list.size,
                firstMs = list.minOf { it.timeMs },
                lastMs = list.maxOf { it.timeMs },
                coveredPackages = list.map { it.coveredPackage }.distinct(),
                examples = list.sortedBy { it.timeMs }.take(5),
                level = if (list.size >= CONFIRM_COUNT) IntrusionSummary.Level.CONFIRMED else IntrusionSummary.Level.SUSPECTED,
            )
        }.sortedByDescending { it.count }

    companion object {
        const val CONFIRM_COUNT = 2
        /** Window after the user closed the guard in which the page underneath counts as a return. */
        const val RETURN_MS = 2_000L
        /** An app's own screen replacing its ad this soon after a launcher tap means the user opened the app. */
        const val LAUNCH_MS = 1_500L
        val PERMISSION_DIALOGS = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")

        fun isReturnAfterExit(exitTimes: Collection<Long>, timeMs: Long) = exitTimes.any { timeMs - it in 0..RETURN_MS }
    }
}
