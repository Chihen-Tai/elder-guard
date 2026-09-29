package org.elderguard.redirect

/**
 * Long-term jump statistics kept ON THE PHONE (the history itself is kept 7 days only): how long "tap -> the other app
 * appears" takes (the 4 s click window), how often the guard blocked or asked, and how often the user opened the app
 * anyway afterwards (a likely wrong decision). Counts only — no package names, no times of individual jumps — so it
 * can be kept for the whole trial and shown on the diagnostics page / in the export without describing the user.
 */
class RedirectStats(var sinceMs: Long = 0L) {
    /** Allowed taps by destination (shop / store vs other) and "tap -> jump" time: <1 s, 1–2 s, 2–3 s, 3–4 s. */
    val tapShop = IntArray(4)
    val tapOther = IntArray(4)
    var blocks = 0
    var asks = 0
    /** Blocked / asked with no tap seen: where a component that does not report taps would show up. */
    var tapless = 0
    /** What the user did afterwards (user_open_anyway, user_allow, user_allow_always, user_block, dismissed, ...). */
    val outcomes = sortedMapOf<String, Int>()

    fun record(timeMs: Long, decision: Decision, reasons: List<ReasonCode>, interactionAgeMs: Long?, outcome: String?) {
        if (sinceMs == 0L) sinceMs = timeMs
        if (outcome != null && reasons.isEmpty()) { outcomes[outcome] = (outcomes[outcome] ?: 0) + 1; return }
        when (decision) {
            Decision.BLOCK -> blocks++
            Decision.ASK -> asks++
            Decision.ALLOW -> if (ReasonCode.USER_CLICK in reasons && interactionAgeMs != null && interactionAgeMs >= 0) {
                val shop = ReasonCode.SHOPPING_REDIRECT in reasons || ReasonCode.APP_STORE_REDIRECT in reasons
                (if (shop) tapShop else tapOther)[(interactionAgeMs / 1000).toInt().coerceIn(0, 3)]++
            }
        }
        if (decision != Decision.ALLOW && ReasonCode.NO_USER_INTERACTION in reasons) tapless++
    }

    /** Opened after all: tapped "仍然開啟" on a block, or "要／以後都允許" on a question. */
    val openedAnyway get() = (outcomes["user_open_anyway"] ?: 0) + (outcomes["user_allow"] ?: 0) + (outcomes["user_allow_always"] ?: 0)

    fun toText(): String = buildString {
        appendLine("since=$sinceMs")
        appendLine("tapShop=${tapShop.joinToString(",")}")
        appendLine("tapOther=${tapOther.joinToString(",")}")
        appendLine("blocks=$blocks"); appendLine("asks=$asks"); appendLine("tapless=$tapless")
        outcomes.forEach { (k, v) -> appendLine("o.$k=$v") }
    }

    companion object {
        fun parse(text: String): RedirectStats {
            val s = RedirectStats()
            text.lines().forEach { line ->
                val k = line.substringBefore('=', ""); val v = line.substringAfter('=', "")
                when {
                    k == "since" -> s.sinceMs = v.toLongOrNull() ?: 0L
                    k == "tapShop" -> v.split(',').mapNotNull { it.toIntOrNull() }.take(4).forEachIndexed { i, n -> s.tapShop[i] = n }
                    k == "tapOther" -> v.split(',').mapNotNull { it.toIntOrNull() }.take(4).forEachIndexed { i, n -> s.tapOther[i] = n }
                    k == "blocks" -> s.blocks = v.toIntOrNull() ?: 0
                    k == "asks" -> s.asks = v.toIntOrNull() ?: 0
                    k == "tapless" -> s.tapless = v.toIntOrNull() ?: 0
                    k.startsWith("o.") -> v.toIntOrNull()?.let { s.outcomes[k.removePrefix("o.")] = it }
                }
            }
            return s
        }
    }
}
