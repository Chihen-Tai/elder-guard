package org.elderguard.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.elderguard.filter.FilterEngine

/**
 * Reads DNS questions from the TUN, answers blocked names immediately, sends the rest upstream on a small worker
 * pool, and writes the replies back. No busy loop: one thread sleeps in poll() until a packet arrives or [stop]
 * writes to the wake-up pipe (closing the fd alone does not reliably wake a blocked read on every Android version).
 *
 * Health signals for [VpnHealthMonitor]: [alive], [lastUpstreamOkMs], [upstreamFailuresInRow], [lastError].
 */
class TunnelEngine(
    private val tun: ParcelFileDescriptor,
    private val filter: () -> FilterEngine,
    @Volatile var upstream: DnsUpstream,
    private val stats: DnsStats,
    /** Which app asked (only called while DNS history is on; costs one system call). */
    private val appOf: (UdpPacket) -> String?,
) {
    private val fd: FileDescriptor = tun.fileDescriptor
    private val wake = Os.pipe()
    private val writeLock = Any()
    @Volatile var alive = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastQueryMs = 0L
        private set
    @Volatile var lastUpstreamOkMs = 0L
        private set
    val upstreamFailuresInRow = AtomicInteger()
    /** Paused while Android's "block connections without VPN" is on: keep resolving, stop filtering (review M2). */
    @Volatile var passThrough = false
    private val inflight = AtomicInteger()

    /** Up to 8 upstream questions at once; beyond that the app gets SERVFAIL at once instead of a 5 s timeout. */
    private val pool = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(), { r -> Thread(r, "dns-up").apply { isDaemon = true } },
        RejectedExecutionHandler { r, _ -> (r as? Job)?.overloaded() })

    private val thread = Thread({ loop() }, "dns-tun")

    fun start() { alive = true; thread.start() }

    fun stop() {
        alive = false
        runCatching { Os.write(wake[1], byteArrayOf(1), 0, 1) }
        runCatching { thread.join(1_500) }
        pool.shutdownNow()
        runCatching { Os.close(wake[0]) }; runCatching { Os.close(wake[1]) }
    }

    val threadAlive get() = thread.isAlive

    private fun loop() {
        val buf = ByteArray(32767)
        val fds = arrayOf(StructPollfd().apply { fd = this@TunnelEngine.fd; events = OsConstants.POLLIN.toShort() },
            StructPollfd().apply { fd = wake[0]; events = OsConstants.POLLIN.toShort() })
        try {
            while (alive) {
                fds[0].revents = 0; fds[1].revents = 0
                try { Os.poll(fds, -1) } catch (e: ErrnoException) { if (e.errno == OsConstants.EINTR) continue else throw e }
                if (fds[1].revents.toInt() != 0 || !alive) break
                val ev = fds[0].revents.toInt()
                if (ev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) { lastError = "tun closed"; break }
                if (ev and OsConstants.POLLIN == 0) continue
                val n = try { Os.read(fd, buf, 0, buf.size) } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e
                }
                if (n <= 0) continue
                try { handle(buf, n) } catch (e: Exception) { lastError = "packet: " + e.javaClass.simpleName }
            }
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + e.message
        } finally {
            alive = false
        }
    }

    private fun handle(buf: ByteArray, n: Int) {
        val p = IpUdp.parse(buf, n) ?: return
        if (p.dstPort != 53) return
        lastQueryMs = System.currentTimeMillis()
        // Anything that is not one plain question is dropped, never forwarded: a malformed packet from any app must
        // not crash the guard (security review H2: a 1-byte payload killed the process) or tie up the upstream
        // workers (M1). Phone resolvers only send plain single-question queries.
        if (p.payload.size < 12) return
        val q = Dns.parseQuery(p.payload) ?: return
        val m = if (passThrough) org.elderguard.filter.FilterEngine.Match.NONE else filter().match(q.name)
        val app = if (stats.historyOn) runCatching { appOf(p) }.getOrNull() else null
        stats.record(q.name, m.blocked, m.name, app)
        if (m.blocked) write(IpUdp.reply(p, Dns.blockedResponse(p.payload, q)))
        else pool.execute(Job(p, q))
    }

    private inner class Job(val p: UdpPacket, val q: DnsQuestion) : Runnable {
        override fun run() {
            inflight.incrementAndGet()
            try {
                val r = upstream.resolve(p.payload, q)
                if (r != null) {
                    lastUpstreamOkMs = System.currentTimeMillis()
                    upstreamFailuresInRow.set(0)
                    write(IpUdp.reply(p, r))
                } else {
                    upstreamFailuresInRow.incrementAndGet()
                    write(IpUdp.reply(p, Dns.errorResponse(p.payload, q, Dns.RCODE_SERVFAIL)))
                }
            } catch (e: Throwable) {
                // never let one query take the process (VPN, accessibility and monitor) down
                lastError = "job: " + e.javaClass.simpleName
            } finally { inflight.decrementAndGet() }
        }

        fun overloaded() { runCatching { write(IpUdp.reply(p, Dns.errorResponse(p.payload, q, Dns.RCODE_SERVFAIL))) } }
    }

    private fun write(packet: ByteArray) {
        if (!alive) return
        synchronized(writeLock) {
            try { Os.write(fd, packet, 0, packet.size) } catch (e: ErrnoException) {
                // EAGAIN = the kernel queue is full: drop this reply, the app's resolver retries
                if (e.errno != OsConstants.EAGAIN) lastError = "write: " + e.message
            } catch (e: java.io.InterruptedIOException) { }
        }
    }
}
