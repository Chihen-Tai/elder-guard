package org.elderguard.filter

/**
 * The one decision the VPN asks for every DNS question: block this name or not.
 *
 * Precedence (predictable, and written down in docs/VPN.md):
 *  1. The user's own rules win over every list. Among them the MOST SPECIFIC name wins, and allow beats block
 *     on the same name.  BLOCK example.com + ALLOW api.example.com -> api.example.com allowed, x.example.com blocked.
 *  2. Then the lists, the same way: most specific first; at one name, allow (incl. the built-in safety allowlist)
 *     beats block. Exact (hosts-file) rules only match the whole name.
 *  3. Nothing matched -> allowed.
 */
class FilterEngine(
    val userAllow: DomainSet = DomainSet.EMPTY,
    val userBlock: DomainSet = DomainSet.EMPTY,
    val listAllow: RuleSet = RuleSet.EMPTY,
    val listBlock: RuleSet = RuleSet.EMPTY,
) {
    enum class Match(val blocked: Boolean) { USER_ALLOW(false), USER_BLOCK(true), LIST_ALLOW(false), LIST_BLOCK(true), NONE(false) }

    val blockRuleCount get() = listBlock.size + userBlock.size

    fun match(name: String): Match {
        val d = FilterParser.normalize(name) ?: return Match.NONE
        val hs = DomainHash.suffixes(d)
        if (userAllow.size + userBlock.size > 0) for (h in hs) {
            if (h in userAllow) return Match.USER_ALLOW
            if (h in userBlock) return Match.USER_BLOCK
        }
        for ((i, h) in hs.withIndex()) {
            val whole = i == 0
            if (h in listAllow.suffix || (whole && h in listAllow.exact)) return Match.LIST_ALLOW
            if (h in listBlock.suffix || (whole && h in listBlock.exact)) return Match.LIST_BLOCK
        }
        return Match.NONE
    }

    fun isBlocked(name: String) = match(name).blocked

    companion object {
        val EMPTY = FilterEngine()

        /**
         * Never blocked by any list (a user rule can still block them): connectivity checks (a blocked check makes
         * Android say "no internet"), push delivery (LINE / WeChat notifications arrive through FCM), time sync and
         * Play downloads. Blocking any of these breaks the phone in ways an older user cannot diagnose.
         */
        val SAFETY_ALLOW = listOf(
            "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com", "clients4.google.com",
            "www.google.com", "mtalk.google.com", "fcm.googleapis.com", "android.clients.google.com", "play.googleapis.com",
            "time.android.com", "android.googleapis.com", "firebaseinstallations.googleapis.com",
        )
        val SAFETY_ALLOW_SET: RuleSet by lazy { RuleSet(DomainSet.ofDomains(SAFETY_ALLOW), DomainSet.EMPTY) }
    }
}
