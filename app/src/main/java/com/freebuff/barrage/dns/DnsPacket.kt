package com.freebuff.barrage.dns

/** Question DNS extraite d'une requête (section question). */
data class DnsQuestion(
    /** Nom de domaine en minuscules, sans point final. */
    val name: String,
    /** Type demandé (1 = A, 28 = AAAA...). */
    val type: Int,
    /** Classe (1 = IN). */
    val cls: Int,
    /** Offset absolu juste après la section question. */
    val endOffset: Int
)

/**
 * Parseur DNS minimal (RFC 1035) : lit la question d'une requête et construit
 * la réponse « domaine bloqué » (0.0.0.0 en A, NOERROR/NODATA pour les autres
 * types). Aucune dépendance externe.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val CLASS_IN = 1

    /** Analyse la question d'une requête DNS ; null si le paquet est invalide. */
    fun parseQuestion(dns: ByteArray): DnsQuestion? {
        if (dns.size < 12) return null
        val flags = u16(dns, 2)
        if ((flags and 0x8000) != 0) return null          // ce n'est pas une requête
        if (u16(dns, 4) < 1) return null                  // aucune question

        var pos = 12
        val name = StringBuilder()
        while (true) {
            if (pos >= dns.size) return null
            val len = dns[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    break
                }
                (len and 0xC0) == 0xC0 -> {
                    // Pointeur de compression : le nom se poursuit ailleurs,
                    // les champs de question suivent l'encodage du pointeur.
                    if (pos + 1 >= dns.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (dns[pos + 1].toInt() and 0xFF)
                    pos += 2
                    val target = readName(dns, ptr, 0) ?: return null
                    if (name.isNotEmpty()) name.append('.')
                    name.append(target)
                    break
                }
                (len and 0xC0) != 0 -> return null         // type de label réservé
                else -> {
                    if (pos + 1 + len > dns.size) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (i in 0 until len) {
                        name.append((dns[pos + 1 + i].toInt() and 0xFF).toChar())
                    }
                    pos += 1 + len
                }
            }
        }

        if (pos + 4 > dns.size) return null
        val type = u16(dns, pos)
        val cls = u16(dns, pos + 2)
        pos += 4

        val host = name.toString().trimEnd('.').lowercase()
        if (host.isEmpty() || host.length > 253) return null
        return DnsQuestion(host, type, cls, pos)
    }

    /**
     * Construit la réponse de blocage : en-tête + question d'origine inchangés,
     * réponse A = 0.0.0.0 (TTL 30 s), ou NOERROR sans answer pour AAAA/autres.
     */
    fun buildBlockedResponse(dns: ByteArray, question: DnsQuestion): ByteArray {
        val header = dns.copyOfRange(0, 12)
        val body = dns.copyOfRange(12, question.endOffset)

        var flags = u16(header, 2)
        flags = flags or 0x8000                 // QR = c'est une réponse
        flags = flags or 0x0100                 // RA = résolveur récursif
        flags = flags and 0x0200.inv()          // pas de TC
        flags = flags and 0x000F.inv()          // RCODE = NOERROR
        put16(header, 2, flags)

        val sinkhole = question.type == TYPE_A && question.cls == CLASS_IN
        put16(header, 4, 1)                     // QDCOUNT conservé
        put16(header, 6, if (sinkhole) 1 else 0) // ANCOUNT
        put16(header, 8, 0)                     // NSCOUNT
        put16(header, 10, 0)                    // ARCOUNT (EDNS retiré)

        if (!sinkhole) return header + body

        // Réponse A : 0.0.0.0 → la connexion échoue immédiatement côté appli
        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C                        // pointeur vers le nom de la question
        put16(answer, 2, TYPE_A)
        put16(answer, 4, CLASS_IN)
        put32(answer, 6, 30)                    // TTL 30 s
        put16(answer, 10, 4)                    // longueur de données
        // octets 12..15 : 0.0.0.0
        return header + body + answer
    }

    /** Lit un nom avec suivi des pointeurs de compression (borné en profondeur). */
    private fun readName(dns: ByteArray, rel: Int, depth: Int): String? {
        if (depth > 8) return null
        var pos = rel
        val sb = StringBuilder()
        while (true) {
            if (pos < 0 || pos >= dns.size) return null
            val len = dns[pos].toInt() and 0xFF
            when {
                len == 0 -> break
                (len and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= dns.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (dns[pos + 1].toInt() and 0xFF)
                    val sub = readName(dns, ptr, depth + 1) ?: return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(sub)
                    break
                }
                (len and 0xC0) != 0 -> return null
                else -> {
                    if (pos + 1 + len > dns.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 0 until len) {
                        sb.append((dns[pos + 1 + i].toInt() and 0xFF).toChar())
                    }
                    pos += 1 + len
                }
            }
        }
        return sb.toString()
    }

    private fun u16(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private fun put16(d: ByteArray, at: Int, v: Int) {
        d[at] = ((v shr 8) and 0xFF).toByte()
        d[at + 1] = (v and 0xFF).toByte()
    }

    private fun put32(d: ByteArray, at: Int, v: Int) {
        d[at] = ((v shr 24) and 0xFF).toByte()
        d[at + 1] = ((v shr 16) and 0xFF).toByte()
        d[at + 2] = ((v shr 8) and 0xFF).toByte()
        d[at + 3] = (v and 0xFF).toByte()
    }
}
