package org.elderguard.redirect

enum class Decision { ALLOW, ASK, BLOCK }

/** Shown to older users only as plain words (see RedirectText); never as a number. */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** Why a switch was allowed / asked / blocked. Stored in the redirect history (codes only, no URLs or screen text). */
enum class ReasonCode {
    // allow
    NOT_PROTECTED, USER_CLICK, KNOWN_ALLOW_RULE, TEMPORARY_UNLOCK, NOTIFICATION_CLICK, SHARE_FLOW, AUTH_FLOW,
    SYSTEM_FLOW, LAUNCHER_FLOW, RECENTS_FLOW, RETURN_FLOW, USER_ALLOW_ONCE, USER_ALLOW_ALWAYS,
    // risk
    NO_USER_INTERACTION, INTERACTION_UNOBSERVABLE, AD_PAGE_REDIRECT, SHAKE_REDIRECT, APP_STORE_REDIRECT,
    SHOPPING_REDIRECT, BROWSER_REDIRECT, REPEATED_REDIRECT, SOURCE_HISTORY, INSTANT_LAUNCH, BACKGROUND_LAUNCH,
    LOOP_DETECTED, KNOWN_BLOCK_RULE, FINANCE_CAUTION, USER_BLOCKED,
}

/** What kind of app the target is; drives risk (an ad's usual destinations) and caution (never hard-block money). */
enum class TargetKind { APP_STORE, SHOPPING, BROWSER, FINANCE, OTHER }

/** One switch from the app the user was in to another app. */
data class Transition(
    val from: String,
    val fromClass: String?,
    val to: String,
    val toClass: String?,
    val timeMs: Long,
    /** How long [from] had been in front. */
    val fromDwellMs: Long,
)

/** The engine's answer for one transition. [score] is internal (0–100+) and only shown on the advanced history page. */
data class Verdict(
    val decision: Decision,
    val reasons: List<ReasonCode>,
    val risk: RiskLevel,
    val score: Int,
    /** ms since the last click in the source app, or null if none was seen. */
    val interactionAgeMs: Long?,
    val matchedRule: String? = null,
    /** Loop protection: go to the home screen instead of back into the source (which would redirect again). */
    val goHome: Boolean = false,
) {
    val primary get() = reasons.firstOrNull()
}

/** A redirect the user decided about ("以後都允許" / "以後都不要"), or a short-lived grant ("這次允許"). */
data class RedirectRule(
    val source: String,
    val target: String,
    val decision: Decision,
    val createdBy: CreatedBy,
    val createdAt: Long,
    /** null = permanent. One-time grants also end when used once. */
    val expiresAt: Long? = null,
) {
    enum class CreatedBy { USER, SYSTEM, TEMPORARY }
    fun active(now: Long) = expiresAt == null || now < expiresAt
    val key get() = "$source>$target"
}

/** One line of the redirect history (local only). */
data class RedirectEvent(
    val timeMs: Long,
    val source: String,
    val target: String,
    val decision: Decision,
    val reasons: List<ReasonCode>,
    val risk: RiskLevel,
    val score: Int,
    val interactionAgeMs: Long?,
    val matchedRule: String?,
    /** What happened after: "back", "home", "relaunch", "prompt", "user_allow", "user_block", or null. */
    val outcome: String? = null,
)
