package com.freebuff.barrage.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    /** Construit une requête DNS réelle (ID, RD, QDCOUNT=1, question IN). */
    private fun buildQuery(name: String, type: Int = 1): ByteArray {
        val labels = name.split(".")
        var size = 12
        for (l in labels) size += 1 + l.length
        size += 1 + 4
        val q = ByteArray(size)
        q[0] = 0x12
        q[1] = 0x34                    // ID
        q[2] = 0x01
        q[3] = 0x00                    // RD
        q[4] = 0x00
        q[5] = 0x01                    // QDCOUNT = 1
        var pos = 12
        for (l in labels) {
            q[pos] = l.length.toByte()
            for ((i, c) in l.withIndex()) q[pos + 1 + i] = c.code.toByte()
            pos += 1 + l.length
        }
        q[pos] = 0
        q[pos + 1] = ((type shr 8) and 0xFF).toByte()
        q[pos + 2] = (type and 0xFF).toByte()
        q[pos + 3] = 0
        q[pos + 4] = 1                 // class IN
        return q
    }

    private fun u16(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    @Test
    fun litLaQuestion() {
        val q = DnsPacket.parseQuestion(buildQuery("PornHub.COM"))
        assertNotNull(q)
        assertEquals("pornhub.com", q!!.name)
        assertEquals(1, q.type)
        assertEquals(1, q.cls)
        assertEquals(29, q.endOffset)   // 12 + 1+7 + 1+3 + 1 + 4
    }

    @Test
    fun reponseDeBlocagePourUnA() {
        val raw = buildQuery("xvideos.com")
        val question = DnsPacket.parseQuestion(raw)!!
        val resp = DnsPacket.buildBlockedResponse(raw, question)

        assertTrue("QR doit être à 1", (resp[2].toInt() and 0x80) != 0)
        assertEquals(1, u16(resp, 4))    // QDCOUNT
        assertEquals(1, u16(resp, 6))    // ANCOUNT
        assertEquals(0, u16(resp, 8))    // NSCOUNT
        assertEquals(0, u16(resp, 10))   // ARCOUNT
        assertEquals(raw.size + 16, resp.size)
        // Dernière donnée : 0.0.0.0
        for (i in resp.size - 4 until resp.size) {
            assertEquals(0, resp[i].toInt())
        }
        // Le nom de la question est conservé tel quel
        for (i in raw.indices) {
            if (i == 2 || i == 3) continue
            if (i == 6 || i == 7) continue
            if (i == 10 || i == 11) continue
            assertEquals(raw[i], resp[i])
        }
    }

    @Test
    fun reponseAAAASansAdresse() {
        val raw = buildQuery("onlyfans.com", type = 28)
        val question = DnsPacket.parseQuestion(raw)!!
        val resp = DnsPacket.buildBlockedResponse(raw, question)
        assertEquals(1, u16(resp, 4))    // QDCOUNT
        assertEquals(0, u16(resp, 6))    // ANCOUNT → NODATA
        assertEquals(raw.size, resp.size)
    }

    @Test
    fun rejetteLesPaquetsInvalides() {
        assertNull(DnsPacket.parseQuestion(ByteArray(5)))
        assertNull(DnsPacket.parseQuestion(buildQuery("a.com").copyOf(10)))
        // Une réponse (QR=1) n'est pas une question
        val response = buildQuery("a.com").also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsPacket.parseQuestion(response))
    }
}
