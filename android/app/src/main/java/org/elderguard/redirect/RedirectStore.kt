package org.elderguard.redirect

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import org.elderguard.filter.FilterStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved "source -> target" rules and one-time grants. Local JSON file, atomic writes. Rules are always PAIRS: allowing
 * WeChat -> Bilibili says nothing about a game opening Bilibili. Only the user creates permanent rules; nothing is
 * ever learned automatically.
 */
class RedirectRuleRepository private constructor(c: Context) {
    private val file = File(c.filesDir, "redirect_rules.json")
    private var rules: List<RedirectRule> = load()
    /** "這次允許": in memory only, single use, 30 s. */
    private val grants = HashMap<String, Long>()

    private fun load(): List<RedirectRule> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            RedirectRule(it.getString("s"), it.getString("t"), Decision.valueOf(it.getString("d")),
                RedirectRule.CreatedBy.valueOf(it.getString("by")), it.getLong("at"), if (it.isNull("exp")) null else it.getLong("exp"))
        }
    }.getOrDefault(emptyList())

    private fun save() = FilterStore.atomicWrite(file) { out ->
        out.write(JSONArray(rules.map {
            JSONObject().put("s", it.source).put("t", it.target).put("d", it.decision.name).put("by", it.createdBy.name)
                .put("at", it.createdAt).put("exp", it.expiresAt ?: JSONObject.NULL)
        }).toString(1).toByteArray())
    }

    @Synchronized fun all(now: Long = System.currentTimeMillis()): List<RedirectRule> = rules.filter { it.active(now) }

    @Synchronized fun find(source: String, target: String, now: Long): RedirectRule? =
        rules.lastOrNull { it.source == source && it.target == target && it.active(now) }

    @Synchronized fun put(source: String, target: String, d: Decision, now: Long = System.currentTimeMillis()) {
        rules = rules.filter { !(it.source == source && it.target == target) && it.active(now) } +
            RedirectRule(source, target, d, RedirectRule.CreatedBy.USER, now)
        save()
    }

    @Synchronized fun remove(source: String, target: String) {
        rules = rules.filter { !(it.source == source && it.target == target) }
        save()
    }

    @Synchronized fun grantOnce(source: String, target: String, now: Long) { grants["$source>$target"] = now + GRANT_MS }

    @Synchronized fun consumeGrant(source: String, target: String, now: Long): Boolean {
        val until = grants.remove("$source>$target") ?: return false
        return now < until
    }

    companion object {
        const val GRANT_MS = 30_000L
        @Volatile private var instance: RedirectRuleRepository? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: RedirectRuleRepository(c.applicationContext).also { instance = it } }
    }
}

/** Redirect history: codes, package names and times only (no URLs, no screen text). 7 days, local only. */
class RedirectHistoryRepository private constructor(c: Context) {
    private val file = File(c.filesDir, "redirect_history.jsonl")
    private var lastTrimMs = 0L
    /** Counts for the whole trial (the history above is trimmed to 7 days). */
    private val statsFile = File(c.filesDir, "redirect_stats.txt")
    private var stats: RedirectStats = if (statsFile.exists()) runCatching { RedirectStats.parse(statsFile.readText()) }.getOrDefault(RedirectStats())
        // first run after the update: start from the 7 days of history the phone still has
        else RedirectStats().also { st ->
            runCatching { read().filter { counts(it) }.forEach { e -> st.record(e.timeMs, e.decision, e.reasons, e.interactionAgeMs, e.outcome) } }
            saveStats(st)
        }

    /** The guard's own test apps (testapps/, e.g. the jump tester on a real phone) are not real use. */
    private fun counts(e: RedirectEvent) = !e.source.startsWith(TEST_APPS) && !e.target.startsWith(TEST_APPS)

    private fun saveStats(st: RedirectStats) = runCatching { // temp -> rename, so a crash never leaves half a file
        val tmp = File(statsFile.path + ".tmp"); tmp.writeText(st.toText()); tmp.renameTo(statsFile)
    }

    @Synchronized fun stats(): RedirectStats = RedirectStats.parse(stats.toText()) // a copy

    @Synchronized fun clearStats() { stats = RedirectStats(); statsFile.delete() }

    @Synchronized fun add(e: RedirectEvent) {
        file.appendText(JSONObject().put("t", e.timeMs).put("s", e.source).put("g", e.target).put("d", e.decision.name)
            .put("r", e.reasons.joinToString(",") { it.name }).put("k", e.risk.name).put("sc", e.score)
            .put("ia", e.interactionAgeMs ?: -1).put("m", e.matchedRule ?: "").put("o", e.outcome ?: "").toString() + "\n")
        if (counts(e)) { stats.record(e.timeMs, e.decision, e.reasons, e.interactionAgeMs, e.outcome); saveStats(stats) }
        // the promise is "7 days": trim by age at most once an hour, and by size at any time
        val now = System.currentTimeMillis()
        if (file.length() > MAX_BYTES || now - lastTrimMs > 3600_000L) { trim(); lastTrimMs = now }
    }

    /** Records what happened after a decision (the prompt answer, the recovery result). */
    @Synchronized fun outcome(timeMs: Long, source: String, target: String, outcome: String) {
        add(RedirectEvent(timeMs, source, target, Decision.ALLOW, emptyList(), RiskLevel.LOW, 0, null, null, outcome).let {
            it.copy(decision = if (outcome.startsWith("user_block")) Decision.BLOCK else Decision.ALLOW)
        })
    }

    @Synchronized fun read(sinceMs: Long = System.currentTimeMillis() - KEEP_MS): List<RedirectEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.filter { it.optLong("t") >= sinceMs }.map {
            RedirectEvent(it.optLong("t"), it.optString("s"), it.optString("g"), runCatching { Decision.valueOf(it.optString("d")) }.getOrDefault(Decision.ALLOW),
                it.optString("r").split(',').filter { r -> r.isNotEmpty() }.mapNotNull { r -> runCatching { ReasonCode.valueOf(r) }.getOrNull() },
                runCatching { RiskLevel.valueOf(it.optString("k")) }.getOrDefault(RiskLevel.LOW), it.optInt("sc"),
                it.optLong("ia").takeIf { v -> v >= 0 }, it.optString("m").ifEmpty { null }, it.optString("o").ifEmpty { null })
        }
    }

    fun blockedSince(sinceMs: Long) = read(sinceMs).count { it.decision == Decision.BLOCK && it.reasons.isNotEmpty() }

    fun hadBlocks(source: String, sinceMs: Long) = read(sinceMs).any { it.source == source && it.decision == Decision.BLOCK && it.reasons.isNotEmpty() }

    @Synchronized fun clear() { file.delete() }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val keep = file.readLines().filter { runCatching { JSONObject(it).optLong("t") >= cutoff }.getOrDefault(false) }.takeLast(MAX_LINES)
        file.writeText(keep.joinToString("") { it + "\n" })
    }

    companion object {
        const val KEEP_MS = 7L * 24 * 3600 * 1000
        private const val TEST_APPS = "org.elderguard.testing."
        const val MAX_BYTES = 512L * 1024
        const val MAX_LINES = 2000
        @Volatile private var instance: RedirectHistoryRepository? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: RedirectHistoryRepository(c.applicationContext).also { instance = it } }
    }
}

/**
 * Which apps are watched as redirect SOURCES. Default AUTO = user-installed apps that contain an ad SDK (from the
 * guard's own app scan), apps caught redirecting without a tap ([learn]), plus apps the user added. "Protect all apps" exists but is not the default.
 */
class ProtectedAppManager private constructor(private val c: Context) {
    enum class Mode { AUTO, ALL, MANUAL }

    private val p = c.getSharedPreferences("redirect", Context.MODE_PRIVATE)
    @Volatile private var cache: Snapshot = snapshot()

    private data class Snapshot(val mode: Mode, val include: Set<String>, val exclude: Set<String>, val adApps: Set<String>,
        val learned: Set<String>)

    private fun snapshot() = Snapshot(
        runCatching { Mode.valueOf(p.getString("mode", "AUTO")!!) }.getOrDefault(Mode.AUTO),
        p.getStringSet("include", emptySet())!!.toSet(), p.getStringSet("exclude", emptySet())!!.toSet(),
        p.getStringSet("adApps", emptySet())!!.toSet(), p.getStringSet("learned", emptySet())!!.toSet(),
    )

    var mode: Mode
        get() = cache.mode
        set(v) { p.edit().putString("mode", v.name).apply(); cache = snapshot().copy(mode = v) }

    val included get() = cache.include
    val excluded get() = cache.exclude
    val adApps get() = cache.adApps
    val adScanDone get() = p.getLong("adScanMs", 0) > 0

    /** Anti-redirect on/off (independent of the accessibility permission, which also powers pop-up closing). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    fun setProtected(pkg: String, on: Boolean) {
        val s = cache
        val inc = if (on) s.include + pkg else s.include - pkg
        val exc = if (on) s.exclude - pkg else s.exclude + pkg
        p.edit().putStringSet("include", inc).putStringSet("exclude", exc).apply()
        cache = s.copy(include = inc, exclude = exc)
    }

    fun setAdApps(pkgs: Set<String>) {
        p.edit().putStringSet("adApps", pkgs).putLong("adScanMs", System.currentTimeMillis()).apply()
        cache = cache.copy(adApps = pkgs)
    }

    fun addAdApp(pkg: String, hasAds: Boolean) {
        val s = cache.adApps
        val n = if (hasAds) s + pkg else s - pkg
        if (n != s) { p.edit().putStringSet("adApps", n).apply(); cache = cache.copy(adApps = n) }
    }

    /**
     * AUTO mode: a user app the engine caught jumping away from an ad page or right after it was opened (review B8:
     * "has an ad SDK" is decided by the app's own code; B9: never on "no tap seen" alone). Kept apart from [adApps],
     * which every full scan replaces; shown in the protected-app list with its reason, and the user can switch it off.
     */
    fun learnable(pkg: String): Boolean { val s = cache; return s.mode == Mode.AUTO && pkg !in s.exclude && isUserApp(pkg) }

    fun learn(pkg: String) {
        val s = cache
        if (pkg in s.learned) return
        val n = s.learned + pkg
        p.edit().putStringSet("learned", n).apply(); cache = s.copy(learned = n)
    }

    val learned get() = cache.learned

    fun isProtected(pkg: String): Boolean {
        val s = cache
        if (pkg in s.exclude) return false
        if (pkg in s.include) return true
        return when (s.mode) {
            Mode.MANUAL -> false
            Mode.AUTO -> pkg in s.adApps || pkg in s.learned
            Mode.ALL -> isUserApp(pkg)
        }
    }

    private val userAppCache = HashMap<String, Boolean>()
    private fun isUserApp(pkg: String): Boolean = synchronized(userAppCache) {
        userAppCache.getOrPut(pkg) {
            runCatching { (c.packageManager.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0 }
                .getOrDefault(false) && pkg != c.packageName
        }
    }

    /** Everything the engine may treat as a source right now (for the settings list). */
    fun protectedCount(installedUserApps: Collection<String>) = installedUserApps.count { isProtected(it) }

    companion object {
        @Volatile private var instance: ProtectedAppManager? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: ProtectedAppManager(c.applicationContext).also { instance = it } }
    }
}
