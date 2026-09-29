package org.elderguard.redirect

/**
 * Follows "which app is in front" from window events, and turns every app-to-app switch into a [Transition].
 * Keyboards, System UI overlays and the guard's own prompt are transparent; launcher / recents / share sheet are
 * remembered as hops (the user was choosing).
 */
class ForegroundTracker(@Volatile var sys: SystemFlowClassifier) {
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

    /**
     * Forget which app is in front (keep the rest): after the service was disconnected, the front app may have
     * changed unseen; the guard re-reads it from the system's screen history instead of inventing a switch.
     */
    fun forgetFront() { current = null; currentClass = null }

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
    /**
     * An UNPROTECTED user app that may start being protected if it is caught redirecting (AUTO mode): protection by
     * "contains a known ad SDK" is decided by the app's own code, so an app without one (affiliate links, a renamed
     * SDK) would otherwise never be judged (review B8).
     */
    fun learnable(pkg: String): Boolean = false
    /**
     * [pkg] jumped with no tap, strongly enough to be blocked, AND with evidence a missed tap cannot explain (an ad
     * page, or jumping right after being opened from the home screen): protect it from now on.
     */
    fun learnSource(pkg: String) {}
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
    sys: SystemFlowClassifier,
    env: RedirectEnv,
    val intent: UserIntentTracker = UserIntentTracker(),
    val loops: RedirectLoopDetector = RedirectLoopDetector(env::now),
    val unlock: TemporaryUnlockManager = TemporaryUnlockManager(env::now),
) {
    /** Replaced when the accessibility service reconnects (same process, fresh roles / environment). */
    @Volatile var sys: SystemFlowClassifier = sys
        set(v) { field = v; tracker.sys = v }
    @Volatile var env: RedirectEnv = env
    val tracker = ForegroundTracker(sys)
    private val authSessions = ArrayList<AuthSession>()
    @Volatile private var recoveringFrom: String? = null
    @Volatile private var recoveringTo: String? = null
    @Volatile private var recoveringUntil = 0L

    /**
     * The guard is about to take the user from [pkg] back to [returnTo] (BACK / relaunch) or to the home screen:
     * that one switch is not judged. Only that way back — a jump from [pkg] anywhere else in the meantime is judged
     * as usual (security review, attack a2: Shopee jumping on to Taobao while the guard pressed BACK passed as "recovery").
     */
    fun expectRecovery(pkg: String, t: Long, returnTo: String? = null) {
        recoveringFrom = pkg; recoveringTo = returnTo; recoveringUntil = t + RECOVERY_MS
    }

    /** Tap-less "jumped away at once" switches that were allowed, per pair (see [judge], security review, attacks a3/a4). */
    private val quietJumps = HashMap<String, ArrayDeque<Long>>()

    fun onClick(pkg: String, cls: String?, t: Long) = intent.onClick(pkg, cls, t)
    fun onScroll(pkg: String, t: Long) = intent.onScroll(pkg, t)
    fun onNotificationTap(pkg: String, t: Long) = intent.onNotificationTap(pkg, t)
    fun onNotificationPosted(t: Long) = intent.onNotificationPosted(t)
    fun onShake(t: Long) = intent.onShake(t)

    /** Feed every window-state event. Returns a verdict only for switches FROM a protected app that need one. */
    fun onWindow(pkg: String, cls: String?, t: Long): Pair<Transition, Verdict>? {
        // The shade signal comes only from AOSP System UI itself: OEM System UI plugins post windows all the time
        // (vivo: com.vivo.systemuiplugin), which made a start-up bounce look like "tapped a notification".
        if (pkg == SystemFlowClassifier.SYSTEM_UI) intent.onSystemUi(t)
        val tr = tracker.onWindow(pkg, cls, t, env.isAdPage(cls)) ?: return null
        val v = judge(tr)
        recordQuietJump(tr, v)
        tracker.recordArrival(tr.to, tr.from, when {
            v.decision != Decision.ALLOW -> ForegroundTracker.Via.TAINTED
            v.primary == ReasonCode.RETURN_FLOW -> ForegroundTracker.Via.RETURN
            // only positive evidence that the user asked makes the way back a "return"; a switch that was merely
            // low-risk (no click seen) is not proof, or it would open a return-path loophole
            v.primary in POSITIVE -> ForegroundTracker.Via.FORWARD_OK
            else -> ForegroundTracker.Via.UNJUDGED
        }, t)
        if (v.decision == Decision.ALLOW && sys.kind(tr.to) == TargetKind.BROWSER && env.isProtected(tr.from) && v.primary == ReasonCode.USER_CLICK)
            authSessions += AuthSession(tr.from, tr.to, t + AUTH_SESSION_MS)
        return if (v.primary == ReasonCode.NOT_PROTECTED) null else tr to v
    }

    private fun quietJumpCount(from: String, to: String, t: Long): Int = synchronized(quietJumps) {
        quietJumps["$from>$to"]?.count { t - it in 0..QUIET_JUMP_WINDOW_MS } ?: 0
    }

    /** Counts allowed switches that had no click (whatever the timing) — the input of REPEATED_TAPLESS. */
    private fun recordQuietJump(tr: Transition, v: Verdict) {
        if (v.decision != Decision.ALLOW || v.interactionAgeMs != null) return
        if (v.primary != ReasonCode.NO_USER_INTERACTION && v.primary != ReasonCode.INTERACTION_UNOBSERVABLE &&
            v.primary != ReasonCode.BROWSER_REDIRECT && v.primary != ReasonCode.REPEATED_TAPLESS) return
        synchronized(quietJumps) {
            val q = quietJumps.getOrPut("${tr.from}>${tr.to}") { ArrayDeque() }
            // a start-up bounce of the same opening is not a second jump (review B6)
            if (q.isNotEmpty() && tr.timeMs - q.last() in 0..SETTLE_DEDUP_MS) return
            q.addLast(tr.timeMs)
            while (q.isNotEmpty() && tr.timeMs - q.first() > QUIET_JUMP_WINDOW_MS) q.removeFirst()
        }
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
        // The switch the guard itself caused (BACK / reopening the source) is not a redirect by the target app
        // (vivo test: Shopee, protected too, was then asked about "opening LINE").
        if (tr.from == recoveringFrom && t <= recoveringUntil && (tr.to == recoveringTo || tr.to in sys.launchers)) {
            recoveringFrom = null // one switch only (review B2); each recovery step re-arms it
            return allow(ReasonCode.GUARD_RECOVERY)
        }
        // An unprotected app is only scored in the background, to learn whether it should be protected (never acted on).
        val watched = env.isProtected(tr.from)
        if (!watched && !env.learnable(tr.from)) return allow(ReasonCode.NOT_PROTECTED)
        fun pass(r: ReasonCode, rule: String? = null) = allow(if (watched) r else ReasonCode.NOT_PROTECTED, rule)
        if (sys.isSystemSource(tr.from)) return pass(ReasonCode.SYSTEM_FLOW)
        if (intent.notificationTapped(tr.to, t)) return pass(ReasonCode.NOTIFICATION_CLICK)
        // Tapping a notification in the shade: System UI was just open and the target has a notification showing.
        // (Only auto-cancel notifications report REASON_CLICK, so this covers the rest.)
        if (click == null && intent.systemUiWithin(t, SHADE_WINDOW_MS) && env.hasNotification(tr.to)) return pass(ReasonCode.NOTIFICATION_CLICK)
        if (unlock.active()) return pass(ReasonCode.TEMPORARY_UNLOCK)
        if (watched && env.consumeGrant(tr.from, tr.to)) return allow(ReasonCode.USER_ALLOW_ONCE, "${tr.from}>${tr.to}")
        if (watched) env.rule(tr.from, tr.to)?.let { r ->
            if (r.decision == Decision.ALLOW) return allow(ReasonCode.KNOWN_ALLOW_RULE, r.key)
            if (r.decision == Decision.BLOCK) return Verdict(Decision.BLOCK, listOf(ReasonCode.KNOWN_BLOCK_RULE), RiskLevel.HIGH, 100, age, r.key)
        }
        // Going back where you came from (WeChat -> Bilibili -> back -> WeChat) — but not into an app the guard just
        // pulled the user back to: that is how "game -> shop -> back -> game -> shop" loops would slip through.
        tracker.arrival(tr.from)?.let { a -> if (a.from == tr.to && a.via == ForegroundTracker.Via.FORWARD_OK) return pass(ReasonCode.RETURN_FLOW) }
        if (authSessions.any { (it.app == tr.from && it.browser == tr.to) || (it.browser == tr.from && it.app == tr.to) })
            return pass(ReasonCode.AUTH_FLOW)

        if (watched && loops.inCooldown(tr.from, tr.to))
            return Verdict(Decision.BLOCK, listOf(ReasonCode.LOOP_DETECTED), RiskLevel.HIGH, 100, age, goHome = true)

        // --- multi-signal risk
        val reasons = ArrayList<ReasonCode>()
        var score = BASE
        val adPage = env.isAdPage(tr.fromClass) || tracker.adPageWithin(tr.from, t, AD_PAGE_WINDOW_MS)
        val observable = intent.observable(tr.from, t)
        val kind = sys.kind(tr.to)
        // An ad's usual destinations: a shop or an app store.
        val adDestination = kind == TargetKind.SHOPPING || kind == TargetKind.APP_STORE
        if (click != null) {
            // a click on an ad page is weak evidence: the "close" button is often wired to the ad's link
            score -= if (adPage) 10 else if (age!! <= STRONG_CLICK_MS) 40 else 30
            reasons += ReasonCode.USER_CLICK
        } else if (observable) {
            // "No click seen" is only evidence where ads go (shop / store) or inside an ad page. Elsewhere it proves
            // little: an app that reports some taps may not report the one that mattered (vivo field test: a tap in
            // a video app that opened Chrome sent no click event, and the user's own link was blocked).
            if (adDestination || adPage) score += 45
            reasons += ReasonCode.NO_USER_INTERACTION
        } else {
            // This app never reports taps (games on a SurfaceView; also YouTube on a real vivo, and possibly big
            // messaging apps): "no click" proves nothing, so it adds nothing. Only real evidence (ad page, shake,
            // shop/store target, instant jump, repeats) can raise the score — otherwise WeChat -> Bilibili would be
            // questioned whenever WeChat does not report the tap (case A must stay ALLOW).
            reasons += ReasonCode.INTERACTION_UNOBSERVABLE
        }
        if (adPage) { score += 30; reasons += ReasonCode.AD_PAGE_REDIRECT }
        if (intent.shookWithin(t, SHAKE_WINDOW_MS)) {
            score += if (click == null) 35 else 10; reasons += ReasonCode.SHAKE_REDIRECT
        }
        when (kind) {
            TargetKind.APP_STORE -> { score += 15; reasons += ReasonCode.APP_STORE_REDIRECT }
            TargetKind.SHOPPING -> { score += 15; reasons += ReasonCode.SHOPPING_REDIRECT }
            TargetKind.BROWSER -> { score += 5; reasons += ReasonCode.BROWSER_REDIRECT }
            else -> {}
        }
        // "Jumped away at once" is what splash / auto-launch ads do to reach a shop or store; for other targets the same
        // timing is routinely produced by the apps themselves (vivo: Chrome flashing back after closing a tab).
        val instant = click == null && tr.fromDwellMs in 0 until INSTANT_MS
        if (instant && adDestination) { score += 10; reasons += ReasonCode.INSTANT_LAUNCH }
        // Capped signals for browser / video / other destinations (review B3). A missing click there proves little on
        // its own (video-app field case), but two patterns are ad-like, and together they can reach ASK — never BLOCK:
        //  - the same pair jumped tap-lessly and was allowed twice already in 10 min (3rd time -> ask);
        //  - the source was JUST opened from the home screen and jumps away at once (a splash / open-screen ad).
        var capped = 0
        val openedFromHome = tracker.arrival(tr.from)?.let { it.from in sys.launchers && t - it.timeMs <= INSTANT_MS } == true
        if (click == null && !adDestination && !adPage) {
            if (quietJumpCount(tr.from, tr.to, t) >= QUIET_JUMPS_TO_ASK - 1) { capped += 10; reasons += ReasonCode.REPEATED_TAPLESS }
            if (instant && openedFromHome) { capped += 10; reasons += ReasonCode.INSTANT_LAUNCH }
        }
        if (loops.recentCount(tr.from, tr.to) >= 1) { score += 15; reasons += ReasonCode.REPEATED_REDIRECT }
        // "This app redirected before" only weighs on an ad's usual destinations: after the user refused one jump
        // to a shop, an ordinary link from the same app (browser, video) must still open normally (vivo test).
        if (adDestination && env.hadBlocksRecently(tr.from)) {
            score += 10; reasons += ReasonCode.SOURCE_HISTORY
        }

        // the capped signals may lift a switch to ASK, but never on their own to BLOCK
        if (capped > 0) score = maxOf(score, minOf(score + capped, BLOCK_AT - 1))
        if (!watched) {
            // Learning is stricter than blocking (review B9): "no tap seen" alone is not enough, because a component that
            // does not report taps (a chat's link card, like the video-app field case) does so EVERY time — repeats
            // prove nothing, and one learned app would be blocked on every genuine tap from then on. Only evidence a
            // missed tap cannot explain counts: an ad page, or jumping away right after being opened from the home
            // screen (a splash ad). A shake is not used: walking while tapping can look like one.
            val adLike = adPage || (instant && openedFromHome)
            if (score >= BLOCK_AT && click == null && adLike) env.learnSource(tr.from)
            return allow(ReasonCode.NOT_PROTECTED)
        }
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
        /** BACK, a second BACK and a relaunch take up to ~2.1 s; allow some slack for slow phones. */
        const val RECOVERY_MS = 4_000L
        const val QUIET_JUMPS_TO_ASK = 3
        /** Same as the guard's start-up-bounce folding window (RedirectGuard.MAX_SETTLE_MS). */
        const val SETTLE_DEDUP_MS = 2_500L
        const val QUIET_JUMP_WINDOW_MS = 10 * 60_000L

        private val POSITIVE = setOf(ReasonCode.USER_CLICK, ReasonCode.NOTIFICATION_CLICK, ReasonCode.SHARE_FLOW,
            ReasonCode.KNOWN_ALLOW_RULE, ReasonCode.USER_ALLOW_ONCE, ReasonCode.USER_ALLOW_ALWAYS, ReasonCode.AUTH_FLOW,
            ReasonCode.LAUNCHER_FLOW, ReasonCode.RECENTS_FLOW, ReasonCode.TEMPORARY_UNLOCK)

        private val PRIORITY = listOf(
            ReasonCode.FINANCE_CAUTION, ReasonCode.LOOP_DETECTED, ReasonCode.KNOWN_BLOCK_RULE, ReasonCode.SHAKE_REDIRECT,
            ReasonCode.AD_PAGE_REDIRECT, ReasonCode.NO_USER_INTERACTION, ReasonCode.REPEATED_REDIRECT, ReasonCode.APP_STORE_REDIRECT,
            ReasonCode.SHOPPING_REDIRECT, ReasonCode.INSTANT_LAUNCH, ReasonCode.INTERACTION_UNOBSERVABLE, ReasonCode.SOURCE_HISTORY,
            ReasonCode.REPEATED_TAPLESS, ReasonCode.BROWSER_REDIRECT, ReasonCode.USER_CLICK,
        )
    }
}
