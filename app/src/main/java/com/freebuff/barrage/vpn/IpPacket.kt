package com.freebuff.barrage.vpn

/**
 * Lecture/écriture minimales des paquets IPv4 + UDP pour le proxy DNS.
 * Le service VPN ne traite que l'UDP port 53 ; le reste est ignoré.
 */
object IpPacket {

    const val DNS_PORT = 53

    /** Requête UDP/53 extraite du tunnel TUN. */
    data class UdpRequest(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray
    )

    /** Extrait une requête UDP/53 IPv4 non fragmentée ; null sinon. */
    fun parseDnsRequest(packet: ByteArray, length: Int): UdpRequest? {
        if (length < 28) return null
        if ((packet[0].toInt() and 0xF0) != 0x40) return null        // IPv4 uniquement
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        val totalLen = u16(packet, 2)
        if (totalLen < ihl + 8 || totalLen > length) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null           // protocole UDP
        if ((u16(packet, 6) and 0x1FFF) != 0) return null             // fragmenté

        val udp = ihl
        val dstPort = u16(packet, udp + 2)
        if (dstPort != DNS_PORT) return null
        val udpLen = u16(packet, udp + 4)
        if (udpLen < 8) return null

        val payloadStart = udp + 8
        val payloadEnd = minOf(udp + udpLen, totalLen)
        if (payloadEnd <= payloadStart) return null

        return UdpRequest(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = u16(packet, udp),
            dstPort = dstPort,
            dnsPayload = packet.copyOfRange(payloadStart, payloadEnd)
        )
    }

    /** Répond à la requête en inversant adresses et ports. */
    fun buildReply(request: UdpRequest, dnsResponse: ByteArray): ByteArray =
        buildUdp(
            srcIp = request.dstIp,
            dstIp = request.srcIp,
            srcPort = request.dstPort,
            dstPort = request.srcPort,
            payload = dnsResponse
        )

    /** Construit un paquet IPv4/UDP complet (checksum IP calculé). */
    fun buildUdp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)

        p[0] = 0x45                                    // IPv4, IHL = 20
        p[2] = ((total shr 8) and 0xFF).toByte()
        p[3] = (total and 0xFF).toByte()
        p[6] = 0x40                                    // DF
        p[8] = 64.toByte()                             // TTL
        p[9] = 17                                      // UDP
        srcIp.copyInto(p, 12)
        dstIp.copyInto(p, 16)

        val csum = checksum(p, 0, 20)
        p[10] = ((csum shr 8) and 0xFF).toByte()
        p[11] = (csum and 0xFF).toByte()

        // En-tête UDP — checksum à 0 (désactivé), valide en IPv4
        p[20] = ((srcPort shr 8) and 0xFF).toByte()
        p[21] = (srcPort and 0xFF).toByte()
        p[22] = ((dstPort shr 8) and 0xFF).toByte()
        p[23] = (dstPort and 0xFF).toByte()
        p[24] = ((udpLen shr 8) and 0xFF).toByte()
        p[25] = (udpLen and 0xFF).toByte()

        payload.copyInto(p, 28)
        return p
    }

    private fun u16(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    /** Checksum de complément à un sur les mots de 16 bits. */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
