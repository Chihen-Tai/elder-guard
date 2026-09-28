package org.elderguard.filter

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Downloads one list SAFELY. Order: download to a temp file (size-capped) -> parse -> validate -> hand the result to
 * the repository, which installs it with an atomic rename. Any failure returns an error and the old rules stay in use.
 *
 * Checks: https only; HTTP 200; <= [maxBytes]; not an HTML page (captive portal / error page); at least [minRules]
 * usable rules; not a sudden collapse to < 30% of the previous size (truncated or broken upstream). There is no
 * upstream signature to verify (none of these lists publish one); SHA-256 is recorded to tell "unchanged" apart and
 * to show in Diagnostics.
 *
 * [open] is injectable so tests can serve local files; the app passes URL::openConnection (the guard is excluded
 * from its own VPN, so this goes straight to the real network).
 */
class FilterUpdater(
    private val tmpDir: File,
    private val open: (URL) -> URLConnection = { it.openConnection() },
    private val maxBytes: Long = 40L * 1024 * 1024,
    private val minRules: Int = 10,
) {
    data class Result(val compiled: CompiledList?, val sha256: String?, val error: String?, val unchanged: Boolean = false)

    fun fetch(url: String, previousCount: Int, previousSha: String?, requireHttps: Boolean = true): Result {
        if (requireHttps && !url.startsWith("https://")) return Result(null, null, "只接受 https 網址")
        val tmp = File(tmpDir, "download-${System.nanoTime()}.tmp")
        try {
            val conn = open(URL(url))
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "fkAd-filter-updater")
            if (conn is HttpURLConnection) {
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code != 200) return Result(null, null, "伺服器回應 $code")
                // a redirect to http:// would downgrade the transport
                if (requireHttps && conn.url.protocol != "https") return Result(null, null, "被轉到不安全的網址")
            }
            val len = conn.contentLengthLong
            if (len > maxBytes) return Result(null, null, "清單太大（${len / 1024 / 1024} MB）")
            val md = MessageDigest.getInstance("SHA-256")
            conn.getInputStream().use { input -> copyCapped(DigestInputStream(input, md), tmp) }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha == previousSha) return Result(null, sha, null, unchanged = true)
            if (looksLikeHtml(tmp)) return Result(null, null, "下載到的是網頁，不是清單（可能是 Wi-Fi 登入頁）")
            val compiled = tmp.inputStream().use { FilterParser.parse(it, maxBytes) }
            if (compiled.ruleCount < minRules) return Result(null, null, "清單裡沒有可用的規則")
            if (previousCount >= 1000 && compiled.ruleCount < previousCount * 3 / 10)
                return Result(null, null, "新清單的規則突然少很多（${compiled.ruleCount}），可能不完整，先不用")
            return Result(compiled, sha, null)
        } catch (e: FilterFormatException) {
            return Result(null, null, "清單太大")
        } catch (e: java.net.SocketTimeoutException) {
            return Result(null, null, "連線逾時")
        } catch (e: java.io.IOException) {
            return Result(null, null, "下載失敗（${e.javaClass.simpleName}）")
        } finally {
            tmp.delete()
        }
    }

    private fun copyCapped(input: InputStream, out: File) {
        out.outputStream().buffered().use { o ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw FilterFormatException("too large")
                o.write(buf, 0, n)
            }
        }
    }

    private fun looksLikeHtml(f: File): Boolean {
        val head = f.inputStream().use { s -> ByteArray(512).let { b -> String(b, 0, maxOf(0, s.read(b))) } }.trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head")
    }
}
