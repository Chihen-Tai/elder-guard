package org.elderguard.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Counters and the optional DNS history. Hot path (every DNS question) touches only atomics and, when history is on,
 * one synchronized ring-buffer insert. Disk writes are batched: counters every [FLUSH_MS] and on stop, history lines
 * in one append per flush. Nothing here is ever uploaded.
 *
 * History is OFF by default (it is a list of every site the phone looked up). When off, no names are kept anywhere —
 * not even in memory. Turning it off deletes the file.
 */
class DnsStats(private val c: Context) {
    private val prefs = c.getSharedPreferences("vpn_stats", Context.MODE_PRIVATE)
    private val file = File(c.filesDir, "dns_history.jsonl")

    val queries = AtomicLong()
    val blocked = AtomicLong()
    private val sessionBlockedBase = AtomicLong()

    data class Entry(val t: Long, val name: String, val blocked: Boolean, val match: String, val app: String?)

    private val ring = ArrayDeque<Entry>()
    private val pending = ArrayList<Entry>()
    @Volatile var historyOn: Boolean = prefs.getBoolean("history", false)
        private set

    fun setHistory(on: Boolean) {
        historyOn = on
        prefs.edit().putBoolean("history", on).apply()
        if (!on) synchronized(ring) { ring.clear(); pending.clear(); file.delete() }
    }

    fun record(name: String, isBlocked: Boolean, match: String, app: String?) {
        queries.incrementAndGet()
        if (isBlocked) blocked.incrementAndGet()
        if (!historyOn) return
        val e = Entry(System.currentTimeMillis(), name, isBlocked, match, app)
        synchronized(ring) {
            ring.addLast(e); if (ring.size > RING) ring.removeFirst()
            pending += e
        }
    }

    fun recent(): List<Entry> = synchronized(ring) {
        if (ring.isEmpty() && historyOn) loadFile()
        ring.toList().asReversed()
    }

    private fun loadFile() {
        if (!file.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        file.readLines().takeLast(RING).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optLong("t") >= cutoff }
            .forEach { ring.addLast(Entry(it.optLong("t"), it.optString("d"), it.optBoolean("b"), it.optString("m"), it.optString("a").ifEmpty { null })) }
    }

    /** Blocked today (local date), including earlier sessions today. */
    fun blockedToday(): Long {
        rollDay()
        return prefs.getLong("dayBlocked", 0) + blocked.get() - sessionBlockedBase.get()
    }

    fun totalBlocked(): Long = prefs.getLong("totalBlocked", 0) + blocked.get() - sessionBlockedBase.get()

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun rollDay() {
        if (prefs.getString("day", "") != today()) {
            // unflushed blocks from before midnight still count toward the total, not toward the new day
            val b = blocked.get()
            val delta = b - sessionBlockedBase.getAndSet(b)
            prefs.edit().putString("day", today()).putLong("dayBlocked", 0)
                .putLong("totalBlocked", prefs.getLong("totalBlocked", 0) + delta).apply()
        }
    }

    /** Batched persistence; called from the service's slow timer and on stop. */
    fun flush() {
        rollDay()
        val b = blocked.get()
        val delta = b - sessionBlockedBase.get()
        if (delta > 0) {
            prefs.edit().putLong("dayBlocked", prefs.getLong("dayBlocked", 0) + delta)
                .putLong("totalBlocked", prefs.getLong("totalBlocked", 0) + delta).apply()
            sessionBlockedBase.set(b)
        }
        synchronized(ring) {
            // under the same lock as setHistory(false): the file can never come back after the user turned history off
            if (!historyOn) { pending.clear(); return }
            val batch = pending.toList().also { pending.clear() }
            if (batch.isNotEmpty()) file.appendText(batch.joinToString("") {
                JSONObject().put("t", it.t).put("d", it.name).put("b", it.blocked).put("m", it.match).put("a", it.app ?: "").toString() + "\n"
            })
            // keep at most 24 h (and 500 lines): the promise shown in the UI
            if (file.exists()) {
                val cutoff = System.currentTimeMillis() - KEEP_MS
                val lines = file.readLines()
                val keep = lines.filter { l -> runCatching { JSONObject(l).optLong("t") >= cutoff }.getOrDefault(false) }.takeLast(RING)
                if (keep.size != lines.size) file.writeText(keep.joinToString("") { it + "\n" })
            }
            while (ring.isNotEmpty() && ring.first().t < System.currentTimeMillis() - KEEP_MS) ring.removeFirst()
        }
    }

    companion object {
        const val FLUSH_MS = 60_000L
        const val RING = 500
        const val KEEP_MS = 24 * 3600 * 1000L
        const val MAX_BYTES = 1L * 1024 * 1024

        @Volatile private var instance: DnsStats? = null
        fun get(c: Context): DnsStats = instance ?: synchronized(this) { instance ?: DnsStats(c.applicationContext).also { instance = it } }
    }
}
