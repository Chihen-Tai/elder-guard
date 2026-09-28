package org.elderguard.vpn

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection

/** Where allowed questions are sent. Implementations must be thread-safe (several workers call [resolve]). */
interface DnsUpstream {
    /** The raw reply, or null when no server answered in time. */
    fun resolve(query: ByteArray, q: DnsQuestion): ByteArray?
    val label: String
}

/**
 * Plain DNS over UDP to the given servers, in order. A fresh socket (random source port) per question; each socket is
 * passed to [protect] first so it can never loop back into our own tunnel.
 */
class UdpUpstream(
    private val servers: () -> List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val timeoutMs: Int = 2_500,
    override val label: String = "udp",
) : DnsUpstream {
    override fun resolve(query: ByteArray, q: DnsQuestion): ByteArray? {
        for (server in servers().take(3)) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = timeoutMs
                    s.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                    val buf = ByteArray(65535) // never cut a large (EDNS) answer
                    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        // only the server we asked, and only the answer to this question
                        if (p.address == server && Dns.matches(buf, p.length, q)) return buf.copyOf(p.length)
                    }
                }
            } catch (_: java.io.IOException) {
                // timeout / unreachable: try the next server
            }
        }
        return null
    }
}

/**
 * DNS-over-HTTPS (RFC 8484, POST application/dns-message). [open] opens the connection on the real network (the app
 * is excluded from its own VPN, so the default is fine). The message ID is sent as 0 (RFC 8484 §4.1) and restored.
 */
class DohUpstream(
    val url: String,
    private val open: (URL) -> URLConnection = { it.openConnection() },
    private val timeoutMs: Int = 4_000,
) : DnsUpstream {
    override val label = "doh"
    private val u = URL(url)

    init { require(u.protocol == "https") { "DoH needs https" } }

    override fun resolve(query: ByteArray, q: DnsQuestion): ByteArray? = try {
        val body = query.copyOf().also { it[0] = 0; it[1] = 0 }
        val c = open(u) as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/dns-message")
        c.setRequestProperty("Accept", "application/dns-message")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else {
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 65535) return null }
            }
            val r = out.toByteArray()
            if (r.size < 12) null else r.also { IpUdp.put16(it, 0, q.id) }
        }
    } catch (_: Exception) { null }
}

/**
 * Primary upstream with an explicit, visible fallback: after [failLimit] failures in a row the primary (e.g. DoH
 * blocked on this network) is skipped for [retryAfterMs] and the fallback (the network's own DNS) is used, so an
 * older user never loses the internet over a privacy setting. [fallingBack] is shown in Diagnostics — BlockAds fell
 * back to plain DNS silently.
 */
class FailoverUpstream(
    private val primary: DnsUpstream,
    private val fallback: DnsUpstream?,
    private val failLimit: Int = 3,
    private val retryAfterMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : DnsUpstream {
    @Volatile private var failures = 0
    @Volatile private var skipUntil = 0L
    val fallingBack get() = fallback != null && clock() < skipUntil
    override val label get() = if (fallingBack) "${primary.label}->${fallback!!.label}" else primary.label

    override fun resolve(query: ByteArray, q: DnsQuestion): ByteArray? {
        if (!fallingBack) {
            val r = primary.resolve(query, q)
            if (r != null) { failures = 0; return r }
            if (++failures >= failLimit && fallback != null) skipUntil = clock() + retryAfterMs
        }
        return fallback?.resolve(query, q)
    }
}
