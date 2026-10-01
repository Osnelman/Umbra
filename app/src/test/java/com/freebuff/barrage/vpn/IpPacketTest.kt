package com.freebuff.barrage.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun fakeDnsQuery(): ByteArray {
        // En-tête DNS (12 octets) + question minimale "a.com" A IN
        val q = ByteArray(12 + 6 + 5)
        q[0] = 0x12
        q[1] = 0x34
        q[4] = 0x00
        q[5] = 0x01
        var pos = 12
        q[pos] = 1
        q[pos + 1] = 'a'.code.toByte()
        pos += 2
        q[pos] = 3
        q[pos + 1] = 'c'.code.toByte()
        q[pos + 2] = 'o'.code.toByte()
        q[pos + 3] = 'm'.code.toByte()
        pos += 4
        q[pos] = 0
        q[pos + 2] = 1   // type A
        q[pos + 4] = 1   // class IN
        return q
    }

    @Test
    fun construitEtRelitUneRequeteUdp() {
        val dns = fakeDnsQuery()
        val src = ip(10, 111, 222, 1)
        val dst = ip(10, 111, 222, 53)
        val packet = IpPacket.buildUdp(src, dst, 54321, 53, dns)

        val parsed = IpPacket.parseDnsRequest(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(54321, parsed!!.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(src, parsed.srcIp)
        assertArrayEquals(dst, parsed.dstIp)
        assertArrayEquals(dns, parsed.dnsPayload)
    }

    @Test
    fun ignoreLeTraficNonDns() {
        val dns = fakeDnsQuery()
        val src = ip(10, 111, 222, 1)
        val dst = ip(10, 111, 222, 53)

        // Port destination ≠ 53
        val http = IpPacket.buildUdp(src, dst, 54321, 80, dns)
        assertNull(IpPacket.parseDnsRequest(http, http.size))

        // Très court
        assertNull(IpPacket.parseDnsRequest(ByteArray(10), 10))
    }

    @Test
    fun construitLaReponseEnInversantLesAdresses() {
        val dns = fakeDnsQuery()
        val src = ip(10, 111, 222, 1)
        val dst = ip(10, 111, 222, 53)
        val request = IpPacket.UdpRequest(src, dst, 54321, 53, dns)
        val answer = byteArrayOf(9, 9, 9)

        val reply = IpPacket.buildReply(request, answer)
        // IPv4 + UDP + payload
        assertEquals(20 + 8 + 3, reply.size)
        // IP source = destination de la requête
        assertEquals(10, reply[12].toInt() and 0xFF)
        assertEquals(111, reply[13].toInt() and 0xFF)
        assertEquals(222, reply[14].toInt() and 0xFF)
        assertEquals(53, reply[15].toInt() and 0xFF)
        // IP destination = source de la requête
        assertEquals(1, reply[19].toInt() and 0xFF)
        // Ports inversés : destination = 54321 = 0xD431
        assertEquals(0xD4, reply[22].toInt() and 0xFF)
        assertEquals(0x31, reply[23].toInt() and 0xFF)
        // Payload en fin de paquet
        assertArrayEquals(answer, reply.copyOfRange(reply.size - 3, reply.size))
    }
}
