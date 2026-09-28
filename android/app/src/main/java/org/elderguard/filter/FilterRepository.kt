package org.elderguard.filter

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Owns the filter lists and the engine built from them. One per process ([get]); the VPN reads [engine] for every
 * DNS question, so swapping it is a single volatile write — a query in flight keeps using the old engine safely
 * (no memory is unmapped under it, unlike BlockAds' munmap-while-in-use crash).
 */
class FilterRepository private constructor(private val c: Context) {
    val store = FilterStore(File(c.filesDir, "filters"))

    @Volatile var engine: FilterEngine = FilterEngine.EMPTY
        private set
    /** Bumped on every rebuild; the VPN notification and UI use it to refresh counts. */
    @Volatile var generation = 0
        private set
    @Volatile var loadError: String? = null
        private set

    val loaded get() = generation > 0

    @Synchronized fun catalog(): List<FilterList> {
        val saved = store.readCatalog()
        if (saved != null) {
            // new built-in entries from an app update are added, the user's switches are kept
            val missing = DEFAULTS.filter { d -> saved.none { it.id == d.id } }
            return if (missing.isEmpty()) saved else (saved + missing).also { store.writeCatalog(it) }
        }
        store.writeCatalog(DEFAULTS)
        return DEFAULTS
    }

    @Synchronized private fun update(id: String, f: (FilterList) -> FilterList) =
        store.writeCatalog(catalog().map { if (it.id == id) f(it) else it })

    /** Builds the engine from what is on disk. Safe to call again at any time; the old engine stays in use until done. */
    @Synchronized fun reload(): FilterEngine {
        val lists = catalog()
        val blocks = ArrayList<RuleSet>(); val allows = arrayListOf(FilterEngine.SAFETY_ALLOW_SET)
        var err: String? = null
        var total = 0
        for (l in lists.filter { it.enabled }) {
            var compiled = store.load(l.id)
            if (compiled == null && l.builtin) compiled = runCatching { compileBundled(l.id) }.getOrNull()
            if (compiled == null) { if (l.url == null || l.lastUpdatedMs > 0) err = "「${l.name}」讀不到，已略過"; continue }
            if (l.ruleCount != compiled.ruleCount) update(l.id) { it.copy(ruleCount = compiled.ruleCount) }
            if (total + compiled.ruleCount > MAX_TOTAL_RULES) { err = "規則太多，「${l.name}」沒有載入"; continue }
            total += compiled.ruleCount
            blocks += compiled.block
            // A list the user added cannot UN-block what other lists block (an "@@" line in a custom or hijacked list
            // would otherwise switch off the malware list). Only the curated lists' exceptions apply (review M5).
            if (l.builtin) allows += compiled.allow
        }
        val user = store.readUserRules()
        val e = FilterEngine(
            userAllow = DomainSet.ofDomains(user.allow.mapNotNull { FilterParser.normalize(it) }),
            userBlock = DomainSet.ofDomains(user.block.mapNotNull { FilterParser.normalize(it) }),
            listAllow = RuleSet.union(allows),
            listBlock = RuleSet.union(blocks),
        )
        engine = e
        loadError = err
        generation++
        return e
    }

    /** First run (or a corrupt .bin): compile the copy shipped inside the app and keep it as the list in use. */
    private fun compileBundled(id: String): CompiledList? {
        val asset = BUNDLED[id] ?: return null
        val compiled = openAsset(asset).use { FilterParser.parse(it) }
        store.install(id, compiled)
        update(id) { it.copy(ruleCount = compiled.ruleCount, lastUpdatedMs = BUNDLED_DATE_MS, lastError = null) }
        return compiled
    }

    /** AGP un-gzips ".gz" assets at build time (same trap as words.txt.gz in 0.4.0), so accept both names. */
    private fun openAsset(name: String): InputStream =
        runCatching { c.assets.open(name.removeSuffix(".gz")) }.getOrNull() ?: GZIPInputStream(c.assets.open(name))

    /**
     * After the user changes rules: rebuild the engine AND the tunnel. Android caches DNS answers per network; a
     * rebuilt tunnel starts with an empty cache, so a new block/allow applies at once instead of after the old
     * answer's TTL (emulator test: a cached example.com kept resolving after "block" until the rebuild).
     */
    private fun changed() { reload(); org.elderguard.vpn.VpnController.restart(c) }

    fun setEnabled(id: String, on: Boolean) { update(id) { it.copy(enabled = on) }; changed() }

    /** Adds a list the user typed in; only https:// addresses are accepted. Returns an error message or null. */
    @Synchronized fun addCustom(name: String, url: String): String? {
        if (!url.startsWith("https://") || url.length > 500) return "網址要以 https:// 開頭"
        val id = "custom-" + Integer.toHexString(url.hashCode()).take(8)
        if (catalog().any { it.id == id }) return "這個清單已經加過了"
        store.writeCatalog(catalog() + FilterList(id, name.take(40).ifBlank { "自訂清單" }, url, enabled = true, builtin = false, license = "（使用者自訂）"))
        return null
    }

    @Synchronized fun remove(id: String) {
        val l = catalog().firstOrNull { it.id == id } ?: return
        if (l.builtin) return
        store.writeCatalog(catalog().filter { it.id != id })
        store.delete(id)
        changed()
    }

    fun rollback(id: String): Boolean = store.rollback(id).also { ok ->
        if (ok) { store.load(id)?.let { cl -> update(id) { it.copy(ruleCount = cl.ruleCount, lastError = null) } }; changed() }
    }

    /** New list versions were installed: apply them (engine + tunnel, see [changed]). */
    fun applyUpdates() = changed()

    /** Records the result of an update attempt (the updater calls this; it never deletes working rules). */
    fun recordUpdate(id: String, compiled: CompiledList?, sha256: String?, error: String?) {
        val now = System.currentTimeMillis()
        if (compiled != null) {
            store.install(id, compiled)
            update(id) { it.copy(lastUpdatedMs = now, lastCheckMs = now, ruleCount = compiled.ruleCount, sha256 = sha256, lastError = null) }
        } else update(id) { it.copy(lastCheckMs = now, lastError = error) }
    }

    // ------------------------------------------------------------ user rules

    fun userRules() = store.readUserRules()

    /** Returns the normalized name, or null when it is not a valid domain. */
    @Synchronized fun addUserRule(domain: String, block: Boolean): String? {
        val d = FilterParser.normalize(domain) ?: return null
        val r = store.readUserRules()
        store.writeUserRules(if (block) r.copy(block = (r.block - d + d), allow = r.allow - d) else r.copy(allow = (r.allow - d + d), block = r.block - d))
        changed()
        return d
    }

    @Synchronized fun removeUserRule(domain: String) {
        val r = store.readUserRules()
        store.writeUserRules(r.copy(block = r.block - domain, allow = r.allow - domain))
        changed()
    }

    companion object {
        @Volatile private var instance: FilterRepository? = null
        fun get(c: Context): FilterRepository = instance ?: synchronized(this) {
            instance ?: FilterRepository(c.applicationContext).also { instance = it }
        }

        /** 3M rules = 24 MB of hashes: the most the engine will hold, whatever lists are enabled. */
        const val MAX_TOTAL_RULES = 3_000_000
        private val BUNDLED = mapOf("stevenblack" to "filters/stevenblack-hosts.txt.gz")
        /** Date of the bundled snapshot (StevenBlack header "27 September 2026"). */
        const val BUNDLED_DATE_MS = 1790475495000L

        /**
         * The lists offered. Only StevenBlack (MIT; ads + malware, includes URLhaus) is on and bundled; the others need
         * a download and are off until the user turns them on. All are data only — never code.
         */
        val DEFAULTS = listOf(
            FilterList("stevenblack", "基本廣告與惡意網站（StevenBlack）", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                enabled = true, builtin = true, license = "MIT"),
            FilterList("adguard-dns", "AdGuard DNS 廣告與追蹤", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                enabled = false, builtin = true, license = "GPL-3.0"),
            FilterList("hagezi-light", "HaGeZi Light（較少誤擋）", "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt",
                enabled = false, builtin = true, license = "GPL-3.0"),
        )
    }
}
