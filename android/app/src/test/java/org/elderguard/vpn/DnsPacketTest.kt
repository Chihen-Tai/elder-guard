package org.elderguard.vpn

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {
    private val app4 = InetAddress.getByName("192.0.2.2").address
    private val dns4 = InetAddress.getByName("192.0.2.53").address
    private val app6 = InetAddress.getByName("fd00:e1de:9a4d::2").address
    private val dns6 = InetAddress.getByName("fd00:e1de:9a4d::53").address

    @Test fun ipv4QueryRoundTripAndChecksums() {
        val q = Dns.buildQuery(0x1234, "Ads.Example.com", Dns.TYPE_A)
        val pkt = IpUdp.build4(app4, dns4, 40000, 53, q)
        // a correct IPv4 header sums to zero; the UDP checksum over the pseudo header too
        assertEquals(0, IpUdp.checksum(pkt, 0, 20, 0))
        val p = IpUdp.parse(pkt, pkt.size)!!
        assertEquals(4, p.ipVersion); assertEquals(53, p.dstPort); assertEquals(40000, p.srcPort)
        assertArrayEquals(q, p.payload)
        val dq = Dns.parseQuery(p.payload)!!
        assertEquals("ads.example.com", dq.name); assertEquals(Dns.TYPE_A, dq.type); assertEquals(0x1234, dq.id)
        val reply = IpUdp.reply(p, Dns.blockedResponse(p.payload, dq))
        val rp = IpUdp.parse(reply, reply.size)!!
        assertArrayEquals(dns4, rp.src); assertArrayEquals(app4, rp.dst); assertEquals(53, rp.srcPort); assertEquals(40000, rp.dstPort)
        assertEquals(0, IpUdp.checksum(reply, 0, 20, 0))
        assertUdpChecksumOk4(reply)
    }

    private fun assertUdpChecksumOk4(p: ByteArray) {
        val udpLen = p.size - 20
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += IpUdp.u16(p, i)
        pseudo += 17 + udpLen
        assertEquals(0, IpUdp.checksum(p, 20, udpLen, pseudo))
    }

    @Test fun ipv6RoundTripHasValidUdpChecksum() {
        val q = Dns.buildQuery(7, "tracker.example.net", Dns.TYPE_AAAA)
        val pkt = IpUdp.build6(app6, dns6, 5353, 53, q)
        val p = IpUdp.parse(pkt, pkt.size)!!
        assertEquals(6, p.ipVersion)
        val reply = IpUdp.reply(p, Dns.blockedResponse(p.payload, Dns.parseQuery(p.payload)!!))
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += IpUdp.u16(reply, i)
        pseudo += 17 + (reply.size - 40)
        assertEquals(0, IpUdp.checksum(reply, 40, reply.size - 40, pseudo))
    }

    @Test fun blockedAnswersAreZeroAddressesOrNodata() {
        val a = Dns.buildQuery(1, "x.example.com", Dns.TYPE_A)
        val ra = Dns.blockedResponse(a, Dns.parseQuery(a)!!)
        assertEquals(0x8000, IpUdp.u16(ra, 2) and 0x8000) // response
        assertEquals(0, IpUdp.u16(ra, 2) and 0xf)          // NOERROR
        assertEquals(1, IpUdp.u16(ra, 6))                  // one answer
        assertEquals(4, IpUdp.u16(ra, ra.size - 6))        // rdlength
        assertTrue(ra.takeLast(4).all { it == 0.toByte() })
        val aaaa = Dns.buildQuery(2, "x.example.com", Dns.TYPE_AAAA)
        val r6 = Dns.blockedResponse(aaaa, Dns.parseQuery(aaaa)!!)
        assertEquals(16, IpUdp.u16(r6, r6.size - 18))
        val https = Dns.buildQuery(3, "x.example.com", 65)
        val rh = Dns.blockedResponse(https, Dns.parseQuery(https)!!)
        assertEquals(0, IpUdp.u16(rh, 6))                  // NODATA
        val sf = Dns.errorResponse(a, Dns.parseQuery(a)!!, Dns.RCODE_SERVFAIL)
        assertEquals(2, IpUdp.u16(sf, 2) and 0xf)
        assertEquals(1, IpUdp.u16(sf, 0))
    }

    @Test fun rejectsNonQueriesFragmentsAndGarbage() {
        val a = Dns.buildQuery(1, "x.example.com", Dns.TYPE_A)
        val resp = a.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(Dns.parseQuery(resp))
        assertNull(Dns.parseQuery(ByteArray(5)))
        val pointer = a.copyOf().also { it[12] = 0xc0.toByte() }
        assertNull(Dns.parseQuery(pointer))
        val pkt = IpUdp.build4(app4, dns4, 1, 53, a)
        pkt[6] = 0x20 // MF flag
        assertNull(IpUdp.parse(pkt, pkt.size))
        assertNull(IpUdp.parse(ByteArray(3), 3))
        val tcp = IpUdp.build4(app4, dns4, 1, 53, a).also { it[9] = 6 }
        assertNull(IpUdp.parse(tcp, tcp.size))
    }

    @Test fun failoverUsesFallbackAfterRepeatedFailuresAndRetriesLater() {
        var now = 0L
        var primaryUp = false
        val primary = object : DnsUpstream { override val label = "doh"; override fun resolve(query: ByteArray, q: DnsQuestion) = if (primaryUp) byteArrayOf(1) else null }
        var fallbackCalls = 0
        val fallback = object : DnsUpstream { override val label = "net"; override fun resolve(query: ByteArray, q: DnsQuestion): ByteArray { fallbackCalls++; return byteArrayOf(2) } }
        val f = FailoverUpstream(primary, fallback, failLimit = 3, retryAfterMs = 1000, clock = { now })
        val q = DnsQuestion(1, "a.com", 1, 1, 0)
        repeat(3) { assertNotNull(f.resolve(ByteArray(0), q)) }
        assertTrue(f.fallingBack)
        primaryUp = true
        assertEquals(2.toByte(), f.resolve(ByteArray(0), q)!![0])
        now = 2000
        assertFalse(f.fallingBack)
        assertEquals(1.toByte(), f.resolve(ByteArray(0), q)!![0])
    }

    @Test fun healthMonitorRestartsThenFailsOpen() {
        fun snap(alive: Boolean = true, fails: Int = 0, lastOk: Long = 0, validated: Boolean = true, restarts: List<Long> = emptyList(), loaded: Boolean = true) =
            VpnHealthMonitor.Snapshot(100_000, alive, true, loaded, fails, lastOk, 0, validated, restarts)
        assertEquals(VpnHealthMonitor.Action.NONE, VpnHealthMonitor.judge(snap()).action)
        assertEquals(VpnHealthMonitor.Action.RESTART, VpnHealthMonitor.judge(snap(alive = false)).action)
        assertEquals(VpnHealthMonitor.Action.FAIL_OPEN, VpnHealthMonitor.judge(snap(alive = false, restarts = listOf(95_000))).action)
        // old restarts outside the window do not count
        assertEquals(VpnHealthMonitor.Action.RESTART, VpnHealthMonitor.judge(snap(alive = false, restarts = listOf(-600_000, -500_000))).action)
        assertEquals(VpnHealthMonitor.Action.RESTART, VpnHealthMonitor.judge(snap(fails = 10)).action)
        // no validated network (airplane mode, captive portal): failures are expected, do nothing
        assertEquals(VpnHealthMonitor.Action.NONE, VpnHealthMonitor.judge(snap(fails = 50, validated = false)).action)
        // a recent success means DNS works
        assertEquals(VpnHealthMonitor.Action.NONE, VpnHealthMonitor.judge(snap(fails = 10, lastOk = 90_000)).action)
        assertEquals(VpnHealthMonitor.Action.RELOAD_FILTERS, VpnHealthMonitor.judge(snap(loaded = false)).action)
    }

    @Test fun perAppPlanNeverRoutesTheGuardThroughItselfAndRefusesEmptySelection() {
        val installed = { p: String -> p != "gone.app" }
        val all = PerAppVpnPolicy.plan(AppMode.ALL, setOf("bank.app", "gone.app"), emptySet(), "org.elderguard", installed)
        assertEquals(listOf("bank.app", "org.elderguard"), all.disallowed); assertTrue(all.allowed.isEmpty())
        val sel = PerAppVpnPolicy.plan(AppMode.SELECTED, emptySet(), setOf("game.app", "org.elderguard"), "org.elderguard", installed)
        assertEquals(listOf("game.app"), sel.allowed); assertTrue(sel.disallowed.isEmpty())
        assertNotNull(PerAppVpnPolicy.plan(AppMode.SELECTED, emptySet(), setOf("gone.app"), "org.elderguard", installed).error)
    }
}
