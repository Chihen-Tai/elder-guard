package org.elderguard.data

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Local-only storage (the app has no INTERNET permission). Two logs, both kept on the phone:
 *  - events.jsonl — evidence the guard itself needs to work (pop-ups, closes, warning notifications). Always on.
 *  - diag.jsonl   — diagnostics for improving the guard (scan results, errors, crashes, service health).
 *                   Written ONLY after the user agreed ([diagConsent]); turning consent off deletes it.
 * Neither log ever holds notification text, full web addresses, photos, contacts, messages or account data.
 * Nothing leaves the phone unless the user exports the file and shares it themselves.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val logFile = File(context.filesDir, "events.jsonl")
    private val diagFile = File(context.filesDir, "diag.jsonl")

    var lastScanMs: Long
        get() = prefs.getLong("lastScanMs", 0)
        set(v) = prefs.edit().putLong("lastScanMs", v).apply()

    var lastScanFlagged: Int
        get() = prefs.getInt("lastScanFlagged", -1)
        set(v) = prefs.edit().putInt("lastScanFlagged", v).apply()

    /** Heartbeat written by the monitor every poll; the UI shows "stopped" when it is stale. */
    var heartbeatMs: Long
        get() = prefs.getLong("heartbeatMs", 0)
        set(v) = prefs.edit().putLong("heartbeatMs", v).apply()

    var lastUsagePollMs: Long
        get() = prefs.getLong("lastUsagePollMs", 0)
        set(v) = prefs.edit().putLong("lastUsagePollMs", v).apply()

    fun lastAlertMs(pkg: String): Long = prefs.getLong("alert:$pkg", 0)
    fun setLastAlert(pkg: String, t: Long) = prefs.edit().putLong("alert:$pkg", t).apply()

    fun isKept(pkg: String): Boolean = prefs.getBoolean("kept:$pkg", false)
    fun setKept(pkg: String, kept: Boolean) = prefs.edit().apply { if (kept) putBoolean("kept:$pkg", true) else remove("kept:$pkg") }.apply()
    fun keptPackages(): List<String> = prefs.all.keys.filter { it.startsWith("kept:") && prefs.getBoolean(it, false) }.map { it.removePrefix("kept:") }

    /** null = not asked yet (the home screen asks once), true/false = the user's answer. */
    var diagConsent: Boolean?
        get() = when (prefs.getInt("diagConsent", -1)) { 1 -> true; 0 -> false; else -> null }
        set(v) {
            prefs.edit().putInt("diagConsent", when (v) { true -> 1; false -> 0; null -> -1 }).apply()
            if (v != true) clearDiag()
        }

    /** Last capability snapshot the monitor logged, so only changes are written. */
    var lastCapsSnapshot: String
        get() = prefs.getString("capsSnapshot", "") ?: ""
        set(v) = prefs.edit().putString("capsSnapshot", v).apply()

    var lastVersionSeen: Long
        get() = prefs.getLong("lastVersionSeen", 0)
        set(v) = prefs.edit().putLong("lastVersionSeen", v).apply()

    @Synchronized
    fun log(type: String, pkg: String, fields: Map<String, Any?> = emptyMap(), timeMs: Long = System.currentTimeMillis()) {
        logFile.appendText(line(type, pkg, fields, timeMs))
    }

    @Synchronized
    fun readLog(sinceMs: Long = 0): List<JSONObject> = read(logFile, EVENTS_KEEP_MS).filter { it.optLong("t") >= sinceMs }

    /** Diagnostic record; silently dropped unless the user agreed. */
    @Synchronized
    fun diag(type: String, pkg: String = "", fields: Map<String, Any?> = emptyMap(), timeMs: Long = System.currentTimeMillis()) {
        if (diagConsent != true) return
        if (diagFile.length() > DIAG_MAX_BYTES) {
            val lines = diagFile.readLines()
            diagFile.writeText(lines.drop(lines.size / 2).joinToString("") { it + "\n" })
        }
        diagFile.appendText(line(type, pkg, fields, timeMs))
    }

    @Synchronized
    fun readDiag(): List<JSONObject> = read(diagFile, DIAG_KEEP_MS)

    @Synchronized
    fun clearDiag() { diagFile.delete() }

    private fun line(type: String, pkg: String, fields: Map<String, Any?>, timeMs: Long): String {
        val o = JSONObject().put("t", timeMs).put("type", type)
        if (pkg.isNotEmpty()) o.put("pkg", pkg)
        fields.forEach { (k, v) -> if (v != null) o.put(k, v) }
        return o.toString() + "\n"
    }

    private fun read(f: File, keepMs: Long): List<JSONObject> {
        if (!f.exists()) return emptyList()
        val cutoff = System.currentTimeMillis() - keepMs
        val all = f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val kept = all.filter { it.optLong("t") >= cutoff }
        if (kept.size < all.size) f.writeText(kept.joinToString("") { it.toString() + "\n" })
        return kept
    }

    companion object {
        const val EVENTS_KEEP_MS = 7L * 24 * 3600 * 1000
        const val DIAG_KEEP_MS = 14L * 24 * 3600 * 1000
        const val DIAG_MAX_BYTES = 2L * 1024 * 1024
    }
}
