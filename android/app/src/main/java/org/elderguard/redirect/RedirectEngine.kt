package org.elderguard.redirect

/**
 * Follows "which app is in front" from window events, and turns every app-to-app switch into a [Transition].
 * Keyboards, System UI overlays and the guard's own prompt are transparent; launcher / recents / share sheet are
 * remembered as hops (the user was choosing).
 */
class ForegroundTracker(private val sys: SystemFlowClassifier) {
    /**
     * How an app was entered, so "going back to where you came from" is recognised — and cannot be abused:
     * only undoing a FORWARD trip the user asked for counts as a return (never a return of a return, and never the
     * way back after the guard pulled the user out: that is how "game -> shop -> back -> game -> shop" ping-pong
     * would otherwise pass as "returning").
     */
    enum class Via { FORWARD_OK, RETURN, UNJUDGED, TAINTED }
    data class Arrival(val from: String, val via: Via, val timeMs: Long)

    var current: String? = null
        private set
    var currentClass: String? = null
        private set
    private var sinceMs = 0L
    private val arrivals = HashMap<String, Arrival>()
    /** Last ad-SDK page time per package (the "X" of a full-screen ad is inside such a page). */
    private val lastAdPage = HashMap<String, Long>()

    /** Returns a transition when the front app changed; null for same-app windows and transparent windows. */
    fun onWindow(pkg: String, cls: String?, t: Long, isAdPage: Boolean): Transition? {
        if (sys.isTransparent(pkg, cls)) return null
        if (isAdPage) lastAdPage[pkg] = t
        val prev = current
        if (pkg == prev) { currentClass = cls; return null }
        val tr = prev?.let { Transition(it, currentClass, pkg, cls, t, t - sinceMs) }
        current = pkg; currentClass = cls; sinceMs = t
        return tr
    }

    /** The guard's recovery / the user's answer tells the tracker how [pkg] was entered. */
    fun recordArrival(pkg: String, from: String, via: Via, t: Long) { arrivals[pkg] = Arrival(from, via, t) }
    fun arrival(pkg: String): Arrival? = arrivals[pkg]

    fun adPageWithin(pkg: String, t: Long, windowMs: Long) = lastAdPage[pkg]?.let { t - it in 0..windowMs } == true

    /** After BACK/HOME the system does not always send an event for the app underneath; the guard sets it. */
    fun setForeground(pkg: String?, cls: String? = null, t: Long) { if (pkg != null && pkg != current) { current = pkg; currentClass = cls; sinceMs = t } }
}

/** A browser the source app opened with the user's consent (sign-in pages): the return trip is expected. */
data class AuthSession(val app: String, val browser: String, val expiresAt: Long)

/**
 * Everything the engine asks the phone about. Implemented by RedirectGuard on the device and by fakes in tests.
 */
interface RedirectEnv {
    fun now(): Long
    fun isProtected(pkg: String): Boolean
    fun isAdPage(cls: String?): Boolean
    /** A saved "source -> target" rule, if any (active only). */
    fun rule(source: String, target: String): RedirectRule?
    /** Consumes a one-time grant for this pair ("這次允許"); true if there was one. */
    fun consumeGrant(source: String, target: String): Boolean
    /** The source was blocked for redirects in the last 24 h (outside the current loop). */
    fun hadBlocksRecently(source: String): Boolean
    /** [pkg] currently has a notification in the shade (from the notification listener, if enabled). */
    fun hasNotification(pkg: String): Boolean
}

/**
 * Cross-app intent protection. Principle: NOT "apps may not open other apps", but "an app may not take the user to
 * another app WITHOUT the user asking for it".
 *
 * Order of decisions for a switch from a protected app:
 *  1. clear system / user flows -> ALLOW (launcher, recents, share sheet, system screens, notification tap, going
 *     back, sign-in round trip, temporary unlock, one-time grant, saved allow rule);
 *  2. a saved block rule or an active loop -> BLOCK;
 *  3. otherwise a risk score from multiple signals (a click alone is not trusted: ads turn the "X" into a click-through).
 *     Money-related apps are never hard-blocked: at most ASK.
 */
class RedirectEngine(
    val sys: SystemFlowClassifier,
    private val env: RedirectEnv,
    val intent: UserIntentTracker = UserIntentTracker(),
    val loops: RedirectLoopDetector = RedirectLoopDetector(env::now),
    val unlock: TemporaryUnlockManager = TemporaryUnlockManager(env::now),
) {
    val tracker = ForegroundTracker(sys)
    private val authSessions = ArrayList<AuthSession>()

    fun onClick(pkg: String, cls: String?, t: Long) = intent.onClick(pkg, cls, t)
    fun onScroll(pkg: String, t: Long) = intent.onScroll(pkg, t)
    fun onNotificationTap(pkg: String, t: Long) = intent.onNotificationTap(pkg, t)
    fun onShake(t: Long) = intent.onShake(t)

    /** Feed every window-state event. Returns a verdict only for switches FROM a protected app that need one. */
    fun onWindow(pkg: String, cls: String?, t: Long): Pair<Transition, Verdict>? {
        if (pkg == SystemFlowClassifier.SYSTEM_UI) intent.onSystemUi(t)
        val tr = tracker.onWindow(pkg, cls, t, env.isAdPage(cls)) ?: return null
        val v = judge(tr)
        tracker.recordArrival(tr.to, tr.from, when {
            v.decision != Decision.ALLOW -> ForegroundTracker.Via.TAINTED
            v.primary == ReasonCode.RETURN_FLOW -> ForegroundTracker.Via.RETURN
            v.primary == ReasonCode.NOT_PROTECTED || v.primary == ReasonCode.SYSTEM_FLOW -> ForegroundTracker.Via.UNJUDGED
            else -> ForegroundTracker.Via.FORWARD_OK
        }, t)
        if (v.decision == Decision.ALLOW && sys.kind(tr.to) == TargetKind.BROWSER && env.isProtected(tr.from) && v.primary == ReasonCode.USER_CLICK)
            authSessions += AuthSession(tr.from, tr.to, t + AUTH_SESSION_MS)
        return if (v.primary == ReasonCode.NOT_PROTECTED) null else tr to v
    }

    fun judge(tr: Transition): Verdict {
        val t = tr.timeMs
        authSessions.removeAll { it.expiresAt < t }
        val click = intent.lastClick(tr.from, t, CLICK_WINDOW_MS)
        val age = click?.let { t - it.timeMs }
        fun allow(r: ReasonCode, rule: String? = null) = Verdict(Decision.ALLOW, listOf(r), RiskLevel.LOW, 0, age, rule)

        // --- where the user was choosing, or the system is working
        val hop = sys.hopOf(tr.from, tr.fromClass)
        when (hop) {
            SystemFlowClassifier.Hop.LAUNCHER -> return allow(ReasonCode.LAUNCHER_FLOW)
            SystemFlowClassifier.Hop.RECENTS -> return allow(ReasonCode.RECENTS_FLOW)
            SystemFlowClassifier.Hop.CHOOSER -> return allow(ReasonCode.SHARE_FLOW)
            else -> {}
        }
        if (sys.isSystemTarget(tr.to) || sys.hopOf(tr.to, tr.toClass) != null) return allow(ReasonCode.SYSTEM_FLOW)
        if (!env.isProtected(tr.from)) return allow(ReasonCode.NOT_PROTECTED)
        if (sys.isSystemTarget(tr.from)) return allow(ReasonCode.SYSTEM_FLOW)
        if (intent.notificationTapped(tr.to, t)) return allow(ReasonCode.NOTIFICATION_CLICK)
        // Tapping a notification in the shade: System UI was just open and the target has a notification showing.
        // (Only auto-cancel notifications report REASON_CLICK, so this covers the rest.)
        if (click == null && intent.systemUiWithin(t, SHADE_WINDOW_MS) && env.hasNotification(tr.to)) return allow(ReasonCode.NOTIFICATION_CLICK)
        if (unlock.active()) return allow(ReasonCode.TEMPORARY_UNLOCK)
        if (env.consumeGrant(tr.from, tr.to)) return allow(ReasonCode.USER_ALLOW_ONCE, "${tr.from}>${tr.to}")
        env.rule(tr.from, tr.to)?.let { r ->
            if (r.decision == Decision.ALLOW) return allow(ReasonCode.KNOWN_ALLOW_RULE, r.key)
            if (r.decision == Decision.BLOCK) return Verdict(Decision.BLOCK, listOf(ReasonCode.KNOWN_BLOCK_RULE), RiskLevel.HIGH, 100, age, r.key)
        }
        // Going back where you came from (WeChat -> Bilibili -> back -> WeChat) — but not into an app the guard just
        // pulled the user back to: that is how "game -> shop -> back -> game -> shop" loops would slip through.
        tracker.arrival(tr.from)?.let { a -> if (a.from == tr.to && a.via == ForegroundTracker.Via.FORWARD_OK) return allow(ReasonCode.RETURN_FLOW) }
        if (authSessions.any { (it.app == tr.from && it.browser == tr.to) || (it.browser == tr.from && it.app == tr.to) })
            return allow(ReasonCode.AUTH_FLOW)

        if (loops.inCooldown(tr.from, tr.to))
            return Verdict(Decision.BLOCK, listOf(ReasonCode.LOOP_DETECTED), RiskLevel.HIGH, 100, age, goHome = true)

        // --- multi-signal risk
        val reasons = ArrayList<ReasonCode>()
        var score = BASE
        val adPage = env.isAdPage(tr.fromClass) || tracker.adPageWithin(tr.from, t, AD_PAGE_WINDOW_MS)
        val observable = intent.observable(tr.from, t)
        if (click != null) {
            // a click on an ad page is weak evidence: the "close" button is often wired to the ad's link
            score -= if (adPage) 10 else if (age!! <= STRONG_CLICK_MS) 40 else 30
            reasons += ReasonCode.USER_CLICK
        } else if (observable) {
            score += 45; reasons += ReasonCode.NO_USER_INTERACTION
        } else {
            // this app never reports taps (games drawn on a SurfaceView): "no click" proves nothing
            score += 15; reasons += ReasonCode.INTERACTION_UNOBSERVABLE
        }
        if (adPage) { score += 30; reasons += ReasonCode.AD_PAGE_REDIRECT }
        if (intent.shookWithin(t, SHAKE_WINDOW_MS)) {
            score += if (click == null) 35 else 10; reasons += ReasonCode.SHAKE_REDIRECT
        }
        val kind = sys.kind(tr.to)
        when (kind) {
            TargetKind.APP_STORE -> { score += 15; reasons += ReasonCode.APP_STORE_REDIRECT }
            TargetKind.SHOPPING -> { score += 15; reasons += ReasonCode.SHOPPING_REDIRECT }
            TargetKind.BROWSER -> { score += 5; reasons += ReasonCode.BROWSER_REDIRECT }
            else -> {}
        }
        if (click == null && tr.fromDwellMs in 0 until INSTANT_MS) { score += 10; reasons += ReasonCode.INSTANT_LAUNCH }
        if (loops.recentCount(tr.from, tr.to) >= 1) { score += 15; reasons += ReasonCode.REPEATED_REDIRECT }
        if (env.hadBlocksRecently(tr.from)) { score += 10; reasons += ReasonCode.SOURCE_HISTORY }

        var decision = when { score >= BLOCK_AT -> Decision.BLOCK; score >= ASK_AT -> Decision.ASK; else -> Decision.ALLOW }
        // Bank / payment / OTP apps: a wrong block could break a payment or a sign-in check — ask instead.
        if (decision == Decision.BLOCK && (kind == TargetKind.FINANCE || sys.isFinance(tr.from))) {
            decision = Decision.ASK; reasons.add(0, ReasonCode.FINANCE_CAUTION)
        }
        val risk = when { score >= BLOCK_AT -> RiskLevel.HIGH; score >= ASK_AT -> RiskLevel.MEDIUM; else -> RiskLevel.LOW }
        // lead with the decisive reason: the strongest risk signal for ASK/BLOCK, the click for ALLOW
        val ordered = if (decision == Decision.ALLOW) reasons.sortedBy { if (it == ReasonCode.USER_CLICK) 0 else 1 }
            else reasons.sortedBy { PRIORITY.indexOf(it).let { i -> if (i < 0) 99 else i } }
        return Verdict(decision, ordered, risk, score.coerceAtLeast(0), age)
    }

    /**
     * The guard blocked [v] for [tr]: count it for loop detection. Returns the verdict to act on (goHome when this
     * block completed a loop).
     */
    fun recordBlocked(tr: Transition, v: Verdict): Verdict {
        val loop = loops.recordBlock(tr.from, tr.to)
        return if (loop) v.copy(reasons = listOf(ReasonCode.LOOP_DETECTED) + v.reasons, goHome = true) else v
    }

    /** The user said "yes, open it" (ASK) or "open anyway" (BLOCK): the app they are in now was entered legitimately. */
    fun userAllowed(tr: Transition, t: Long) = tracker.recordArrival(tr.to, tr.from, ForegroundTracker.Via.FORWARD_OK, t)

    /** After the guard sent the user back to [pkg] (the source), that arrival must not count as "going back". */
    fun recoveredTo(pkg: String?, from: String, t: Long) {
        if (pkg == null) return
        tracker.setForeground(pkg, null, t)
        tracker.recordArrival(pkg, from, ForegroundTracker.Via.TAINTED, t)
    }

    companion object {
        const val BASE = 20
        const val ASK_AT = 30
        const val BLOCK_AT = 60
        /** A click this recent in the source counts as "the user asked" (cold-starting the target can take ~3 s). */
        const val CLICK_WINDOW_MS = 4_000L
        const val STRONG_CLICK_MS = 1_500L
        const val SHAKE_WINDOW_MS = 3_000L
        const val AD_PAGE_WINDOW_MS = 10_000L
        /** The source had been in front for less than this without any tap: it jumped right away. */
        const val INSTANT_MS = 1_500L
        const val AUTH_SESSION_MS = 10 * 60_000L
        const val SHADE_WINDOW_MS = 5_000L

        private val PRIORITY = listOf(
            ReasonCode.FINANCE_CAUTION, ReasonCode.LOOP_DETECTED, ReasonCode.KNOWN_BLOCK_RULE, ReasonCode.SHAKE_REDIRECT,
            ReasonCode.AD_PAGE_REDIRECT, ReasonCode.NO_USER_INTERACTION, ReasonCode.REPEATED_REDIRECT, ReasonCode.APP_STORE_REDIRECT,
            ReasonCode.SHOPPING_REDIRECT, ReasonCode.INSTANT_LAUNCH, ReasonCode.INTERACTION_UNOBSERVABLE, ReasonCode.SOURCE_HISTORY,
            ReasonCode.BROWSER_REDIRECT, ReasonCode.USER_CLICK,
        )
    }
}
