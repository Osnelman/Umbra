package com.freebuff.barrage.lock

import java.security.MessageDigest

/**
 * Verrou binaire : le code court (ex. 4839) est converti en sa représentation
 * binaire (1001011100111). Seul le hachage SHA-256 de cette chaîne binaire est
 * stocké : l'app ne révèle jamais le code, il faut le retaper pour désactiver.
 */
object CodeLock {

    private val CODE_REGEX = Regex("^[0-9]{3,10}$")
    private val BINARY_REGEX = Regex("^[01]{4,32}$")

    /** Convertit un code court en code binaire, ou null si invalide. */
    fun toBinary(code: String): String? {
        val c = code.trim()
        if (!CODE_REGEX.matches(c)) return null
        val value = c.toLongOrNull() ?: return null
        if (value < 8) return null
        return value.toString(2)
    }

    /** Hachage SHA-256 (hexadécimal) du code binaire. */
    fun hash(binary: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(binary.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Vérifie un code binaire saisi contre le hachage stocké. */
    fun verify(binary: String, expectedHash: String): Boolean {
        val b = binary.trim()
        if (!BINARY_REGEX.matches(b)) return false
        return hash(b) == expectedHash
    }
}
