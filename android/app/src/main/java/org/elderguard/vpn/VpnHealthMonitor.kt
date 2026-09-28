package org.elderguard.vpn

/**
 * Decides what to do about the tunnel's health. Pure logic (tested); [AdBlockVpnService] gathers the snapshot every
 * [CHECK_MS] while the screen is on (every [CHECK_IDLE_MS] while off) and carries out the action.
 *
 * Principle: a broken blocker must never cut an older user off the internet. One rebuild is tried; if the tunnel
 * still does not work, protection is turned off ("fail open") and the user is told plainly that the internet still
 * works but ad blocking has stopped. BlockAds probed its own tunnel from its excluded UID (the probe can never reach
 * the tunnel), so we judge from the engine's real counters instead.
 */
object VpnHealthMonitor {
    const val CHECK_MS = 20_000L
    const val CHECK_IDLE_MS = 5 * 60_000L
    /** One rebuild, then give up: a rebuild rarely fixes a dead upstream, and every minute without DNS matters. */
    const val MAX_RESTARTS = 1
    const val RESTART_WINDOW_MS = 10 * 60_000L
    /** Upstream failures in a row that count as "DNS is dead" while the network itself is validated. */
    const val UPSTREAM_DEAD = 8
    const val UPSTREAM_DEAD_MS = 30_000L

    data class Snapshot(
        val now: Long,
        val engineAlive: Boolean,
        val tunValid: Boolean,
        val filterLoaded: Boolean,
        val upstreamFailuresInRow: Int,
        val lastUpstreamOkMs: Long,
        val startedMs: Long,
        /** The underlying network passed Android's own internet check. */
        val networkValidated: Boolean,
        val recentRestarts: List<Long>,
    )

    enum class Action { NONE, RELOAD_FILTERS, RESTART, FAIL_OPEN }

    data class Verdict(val action: Action, val problem: String?)

    fun judge(s: Snapshot): Verdict {
        val restarts = s.recentRestarts.count { s.now - it < RESTART_WINDOW_MS }
        fun restartOrGiveUp(why: String) = Verdict(if (restarts < MAX_RESTARTS) Action.RESTART else Action.FAIL_OPEN, why)
        if (!s.engineAlive || !s.tunValid) return restartOrGiveUp("engine_down")
        val sinceOk = s.now - maxOf(s.lastUpstreamOkMs, s.startedMs)
        if (s.networkValidated && s.upstreamFailuresInRow >= UPSTREAM_DEAD && sinceOk > UPSTREAM_DEAD_MS) return restartOrGiveUp("upstream_dead")
        if (!s.filterLoaded) return Verdict(Action.RELOAD_FILTERS, "filters_missing")
        return Verdict(Action.NONE, null)
    }
}
