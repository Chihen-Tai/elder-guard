package org.elderguard.vpn

/**
 * Minimal, allocation-light IPv4/IPv6 + UDP + DNS handling for the DNS-only tunnel. Clean implementation from the
 * RFCs (791, 8200, 768, 1035); no code taken from other blockers.
 *
 * Only what the tunnel needs: recognise a UDP datagram to port 53, read the first question, build a reply datagram
 * with the addresses/ports swapped. Fragments, IPv6 extension headers and TCP are ignored (dropped) — the only
 * traffic routed into the tunnel is DNS to our own fake server address.
 */
class UdpPacket(
    val ipVersion: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object IpUdp {
    private const val PROTO_UDP = 17

    fun parse(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 1) return null
        return when (buf[0].toInt() ushr 4) {
            4 -> parse4(buf, len)
            6 -> parse6(buf, len)
            else -> null
        }
    }

    private fun parse4(b: ByteArray, len: Int): UdpPacket? {
        if (len < 28) return null
        val ihl = (b[0].toInt() and 0x0f) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (b[9].toInt() and 0xff != PROTO_UDP) return null
        val flagsFrag = u16(b, 6)
        if (flagsFrag and 0x3fff != 0) return null // a fragment (MF set or offset > 0)
        val total = minOf(u16(b, 2), len)
        return udp(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), b, ihl, total)
    }

    private fun parse6(b: ByteArray, len: Int): UdpPacket? {
        if (len < 48) return null
        if (b[6].toInt() and 0xff != PROTO_UDP) return null // extension headers: not used by DNS stubs
        val total = minOf(40 + u16(b, 4), len)
        return udp(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), b, 40, total)
    }

    private fun udp(v: Int, src: ByteArray, dst: ByteArray, b: ByteArray, off: Int, end: Int): UdpPacket? {
        val udpLen = u16(b, off + 4)
        if (udpLen < 8 || off + udpLen > end) return null
        return UdpPacket(v, src, dst, u16(b, off), u16(b, off + 2), b.copyOfRange(off + 8, off + udpLen))
    }

    /** A reply to [req] carrying [payload]: source and destination swapped, checksums filled in. */
    fun reply(req: UdpPacket, payload: ByteArray): ByteArray =
        if (req.ipVersion == 4) build4(req.dst, req.src, req.dstPort, req.srcPort, payload)
        else build6(req.dst, req.src, req.dstPort, req.srcPort, payload)

    fun build4(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val p = ByteArray(20 + udpLen)
        p[0] = 0x45; put16(p, 2, p.size); put16(p, 6, 0x4000) // DF
        p[8] = 64; p[9] = PROTO_UDP.toByte()
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20, 0))
        writeUdp(p, 20, sport, dport, payload)
        val pseudo = sum(src) + sum(dst) + PROTO_UDP + udpLen
        put16(p, 26, udpChecksum(p, 20, udpLen, pseudo))
        return p
    }

    fun build6(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val p = ByteArray(40 + udpLen)
        p[0] = 0x60; put16(p, 4, udpLen); p[6] = PROTO_UDP.toByte(); p[7] = 64
        System.arraycopy(src, 0, p, 8, 16); System.arraycopy(dst, 0, p, 24, 16)
        writeUdp(p, 40, sport, dport, payload)
        val pseudo = sum(src) + sum(dst) + udpLen + PROTO_UDP
        put16(p, 46, udpChecksum(p, 40, udpLen, pseudo))
        return p
    }

    private fun writeUdp(p: ByteArray, off: Int, sport: Int, dport: Int, payload: ByteArray) {
        put16(p, off, sport); put16(p, off + 2, dport); put16(p, off + 4, 8 + payload.size)
        System.arraycopy(payload, 0, p, off + 8, payload.size)
    }

    /** UDP checksum 0 means "none" on IPv4 and is illegal on IPv6, so a computed 0 is sent as 0xffff. */
    private fun udpChecksum(p: ByteArray, off: Int, len: Int, pseudo: Long): Int =
        checksum(p, off, len, pseudo).let { if (it == 0) 0xffff else it }

    private fun sum(a: ByteArray): Long { var s = 0L; var i = 0; while (i < a.size) { s += u16(a, i); i += 2 }; return s }

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var s = initial
        var i = off
        while (i + 1 < off + len) { s += u16(b, i); i += 2 }
        if (i < off + len) s += (b[i].toInt() and 0xff) shl 8
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return (s.inv() and 0xffff).toInt()
    }

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
}

/** The part of a DNS message the filter needs. [questionEnd] = offset just after the question section. */
class DnsQuestion(val id: Int, val name: String, val type: Int, val qclass: Int, val questionEnd: Int)

object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    /** Seconds a blocked answer may be cached; short so a new allow rule takes effect quickly. */
    const val BLOCK_TTL = 60

    /** Reads a standard query with exactly one question; anything else (responses, updates, multi-question) -> null. */
    fun parseQuery(m: ByteArray): DnsQuestion? {
        if (m.size < 17) return null
        val flags = IpUdp.u16(m, 2)
        if (flags and 0x8000 != 0) return null        // QR = response
        if ((flags ushr 11) and 0xf != 0) return null   // opcode != QUERY
        if (IpUdp.u16(m, 4) != 1) return null           // QDCOUNT
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= m.size) return null
            val l = m[i].toInt() and 0xff
            if (l == 0) { i++; break }
            if (l and 0xc0 != 0 || l > 63) return null  // no compression in a question we accept
            if (i + 1 + l > m.size || sb.length + l > 253) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in i + 1..i + l) {
                val c = (m[k].toInt() and 0xff).toChar()
                sb.append(if (c in 'A'..'Z') c + 32 else c)
            }
            i += 1 + l
        }
        if (i + 4 > m.size) return null
        return DnsQuestion(IpUdp.u16(m, 0), sb.toString(), IpUdp.u16(m, i), IpUdp.u16(m, i + 2), i + 4)
    }

    /**
     * Answer for a blocked name: A -> 0.0.0.0, AAAA -> ::, anything else -> NOERROR with no answer (NODATA).
     * An address answer makes apps fail fast instead of retrying (NXDOMAIN makes some SDKs retry other hosts).
     */
    fun blockedResponse(query: ByteArray, q: DnsQuestion): ByteArray {
        val addrLen = when (q.type) { TYPE_A -> 4; TYPE_AAAA -> 16; else -> 0 }
        val ans = if (addrLen > 0) 12 + addrLen else 0
        val r = ByteArray(q.questionEnd + ans)
        System.arraycopy(query, 0, r, 0, q.questionEnd)
        header(r, query, RCODE_NOERROR, answers = if (addrLen > 0) 1 else 0)
        if (addrLen > 0) {
            var o = q.questionEnd
            IpUdp.put16(r, o, 0xc00c); o += 2                // name: pointer to the question
            IpUdp.put16(r, o, q.type); o += 2
            IpUdp.put16(r, o, 1); o += 2                     // class IN
            r[o] = 0; r[o + 1] = 0; IpUdp.put16(r, o + 2, BLOCK_TTL); o += 4
            IpUdp.put16(r, o, addrLen)                       // rdata stays all zero
        }
        return r
    }

    /** An error reply (SERVFAIL when upstream is unreachable), so the app's resolver fails now instead of timing out. */
    fun errorResponse(query: ByteArray, q: DnsQuestion, rcode: Int): ByteArray {
        val r = query.copyOf(q.questionEnd)
        header(r, query, rcode, answers = 0)
        return r
    }

    private fun header(r: ByteArray, query: ByteArray, rcode: Int, answers: Int) {
        val qflags = IpUdp.u16(query, 2)
        val rd = qflags and 0x0100
        IpUdp.put16(r, 2, 0x8000 or rd or 0x0080 or (rcode and 0xf)) // QR, RD copied, RA
        IpUdp.put16(r, 4, 1); IpUdp.put16(r, 6, answers); IpUdp.put16(r, 8, 0); IpUdp.put16(r, 10, 0)
    }

    /** True when [resp] answers [q] (same ID; a spoofed or stale reply is dropped). */
    fun matches(resp: ByteArray, len: Int, q: DnsQuestion) = len >= 12 && IpUdp.u16(resp, 0) == q.id && (resp[2].toInt() and 0x80) != 0

    /** A query message for [name] (used by the health check and the debug harness). */
    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val h = ByteArray(12); IpUdp.put16(h, 0, id); IpUdp.put16(h, 2, 0x0100); IpUdp.put16(h, 4, 1)
        out.write(h)
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII)) }
        out.write(0)
        val t = ByteArray(4); IpUdp.put16(t, 0, type); IpUdp.put16(t, 2, 1); out.write(t)
        return out.toByteArray()
    }
}
