package org.elderguard.filter

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** One filter list the user can turn on/off. [url] null = only the copy bundled in the app. */
data class FilterList(
    val id: String,
    val name: String,
    val url: String?,
    val enabled: Boolean,
    /** Shipped with the app (cannot be removed, only turned off). */
    val builtin: Boolean,
    val license: String,
    val lastUpdatedMs: Long = 0,
    val ruleCount: Int = 0,
    val sha256: String? = null,
    /** Last update problem in plain words, or null. The previous rules stay in use. */
    val lastError: String? = null,
    val lastCheckMs: Long = 0,
    val hasBackup: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url ?: JSONObject.NULL)
        .put("enabled", enabled).put("builtin", builtin).put("license", license).put("lastUpdatedMs", lastUpdatedMs)
        .put("ruleCount", ruleCount).put("sha256", sha256 ?: JSONObject.NULL).put("lastError", lastError ?: JSONObject.NULL)
        .put("lastCheckMs", lastCheckMs)

    companion object {
        fun fromJson(o: JSONObject) = FilterList(
            o.getString("id"), o.getString("name"), o.optString("url").takeIf { !o.isNull("url") && it.isNotEmpty() },
            o.optBoolean("enabled"), o.optBoolean("builtin"), o.optString("license"), o.optLong("lastUpdatedMs"),
            o.optInt("ruleCount"), o.optString("sha256").takeIf { !o.isNull("sha256") }, o.optString("lastError").takeIf { !o.isNull("lastError") },
            o.optLong("lastCheckMs"),
        )
    }
}

/**
 * Files under one directory (filesDir/filters in the app, a temp dir in tests):
 *   catalog.json       list settings and status
 *   <id>.bin           compiled rules in use          <id>.bak   the version before the last update (rollback)
 *   user_rules.json    the user's own block / allow names
 *
 * Every write is "write temp -> fsync -> rename", so a crash or a full disk never leaves a half-written file in use,
 * and an update never deletes the old rules before the new ones are complete (the core rule of the update design).
 * Every .bin carries a CRC32; a corrupt file is reported as unreadable and the caller falls back to .bak / bundled.
 */
class FilterStore(val dir: File) {
    init { dir.mkdirs() }

    private val catalogFile get() = File(dir, "catalog.json")
    private val userFile get() = File(dir, "user_rules.json")
    fun binFile(id: String) = File(dir, "${safe(id)}.bin")
    fun bakFile(id: String) = File(dir, "${safe(id)}.bak")

    private fun safe(id: String): String {
        require(id.matches(Regex("[a-z0-9_-]{1,40}"))) { "bad list id" }
        return id
    }

    // ------------------------------------------------------------ catalog

    @Synchronized fun readCatalog(): List<FilterList>? = runCatching {
        if (!catalogFile.exists()) return null
        val a = JSONArray(catalogFile.readText())
        (0 until a.length()).map { FilterList.fromJson(a.getJSONObject(it)).let { l -> l.copy(hasBackup = bakFile(l.id).exists()) } }
    }.getOrNull()

    @Synchronized fun writeCatalog(lists: List<FilterList>) =
        atomicWrite(catalogFile) { it.write(JSONArray(lists.map { l -> l.toJson() }).toString(1).toByteArray()) }

    // ------------------------------------------------------------ user rules

    data class UserRules(val block: List<String>, val allow: List<String>)

    @Synchronized fun readUserRules(): UserRules = runCatching {
        val o = JSONObject(userFile.readText())
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        UserRules(arr("block"), arr("allow"))
    }.getOrDefault(UserRules(emptyList(), emptyList()))

    @Synchronized fun writeUserRules(r: UserRules) = atomicWrite(userFile) {
        it.write(JSONObject().put("block", JSONArray(r.block)).put("allow", JSONArray(r.allow)).toString(1).toByteArray())
    }

    // ------------------------------------------------------------ compiled lists

    /**
     * Installs [c] as the list in use: the previous version becomes the backup. Nothing is replaced if writing fails.
     */
    @Synchronized fun install(id: String, c: CompiledList) {
        val tmp = File(dir, "${safe(id)}.bin.tmp")
        writeBin(tmp, c)
        val cur = binFile(id)
        if (cur.exists()) {
            val bak = bakFile(id)
            if (!cur.renameTo(bak)) { bak.delete(); cur.renameTo(bak) }
        }
        if (!tmp.renameTo(cur)) { tmp.delete(); throw java.io.IOException("could not install $id") }
    }

    /** Swaps the backup back in (the current version becomes the new backup). Returns false if there is none. */
    @Synchronized fun rollback(id: String): Boolean {
        val bak = bakFile(id); val cur = binFile(id)
        if (!bak.exists() || read(bak) == null) return false
        val tmp = File(dir, "${safe(id)}.swap")
        if (cur.exists() && !cur.renameTo(tmp)) return false
        if (!bak.renameTo(cur)) { tmp.renameTo(cur); return false }
        if (tmp.exists()) tmp.renameTo(bak)
        return true
    }

    /** The list in use; falls back to the backup when the current file is corrupt. null = neither is readable. */
    @Synchronized fun load(id: String): CompiledList? = read(binFile(id)) ?: read(bakFile(id))

    @Synchronized fun delete(id: String) { binFile(id).delete(); bakFile(id).delete() }

    private fun writeBin(f: File, c: CompiledList) = atomicWrite(f) { raw ->
        val crc = CRC32()
        val out = DataOutputStream(BufferedOutputStream(CheckedOutputStream(raw, crc), 64 * 1024))
        out.writeInt(MAGIC); out.writeInt(VERSION)
        out.writeInt(c.validLines); out.writeInt(c.skippedLines)
        for (s in listOf(c.block.exact, c.block.suffix, c.allow.suffix)) {
            out.writeInt(s.size)
            for (h in s.hashes) out.writeLong(h)
        }
        out.flush()
        DataOutputStream(raw).writeLong(crc.value)
    }

    private fun read(f: File): CompiledList? {
        if (!f.exists() || f.length() < 28) return null
        return runCatching {
            val crc = CRC32()
            DataInputStream(BufferedInputStream(f.inputStream(), 64 * 1024)).use { raw ->
                val len = f.length() - 8
                val body = DataInputStream(CheckedInputStream(BoundedStream(raw, len), crc))
                if (body.readInt() != MAGIC || body.readInt() != VERSION) return null
                val valid = body.readInt(); val skipped = body.readInt()
                fun set(): DomainSet {
                    val n = body.readInt()
                    if (n < 0 || n * 8L > len) throw java.io.IOException("bad count")
                    val a = LongArray(n) { body.readLong() }
                    for (i in 1 until n) if (a[i] <= a[i - 1]) throw java.io.IOException("not sorted")
                    return DomainSet.of(a)
                }
                val be = set(); val bs = set(); val al = set()
                if (raw.readLong() != crc.value) return null
                CompiledList(RuleSet(be, bs), RuleSet(DomainSet.EMPTY, al), valid, skipped)
            }
        }.getOrNull()
    }

    private class BoundedStream(private val s: java.io.InputStream, private var left: Long) : java.io.InputStream() {
        override fun read(): Int = if (left <= 0) -1 else s.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            return s.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
    }

    companion object {
        private const val MAGIC = 0x45474631 // "EGF1"
        private const val VERSION = 1

        /** Write to "<name>.tmp", fsync, then rename over the target (atomic on the same file system). */
        fun atomicWrite(target: File, body: (FileOutputStream) -> Unit) {
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                FileOutputStream(tmp).use { body(it); it.flush(); it.fd.sync() }
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: ${target.name}")
            } catch (e: Exception) {
                tmp.delete(); throw e
            }
        }
    }
}
