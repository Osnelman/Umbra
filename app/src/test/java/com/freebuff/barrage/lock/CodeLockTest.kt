package com.freebuff.barrage.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLockTest {

    @Test
    fun convertit4839EnBinaire() {
        assertEquals("1001011100111", CodeLock.toBinary("4839"))
    }

    @Test
    fun convertitAutresCodes() {
        assertEquals("1000", CodeLock.toBinary("008"))    // 8 en binaire, format à 3 chiffres
        assertEquals("1000000001000", CodeLock.toBinary("4104"))
        assertEquals("100000000000000000000", CodeLock.toBinary("1048576"))
    }

    @Test
    fun rejetteLesCodesInvalides() {
        assertNull(CodeLock.toBinary("7"))            // trop petit
        assertNull(CodeLock.toBinary("12"))           // moins de 3 chiffres
        assertNull(CodeLock.toBinary("48a9"))         // pas que des chiffres
        assertNull(CodeLock.toBinary(""))             // vide
        assertNull(CodeLock.toBinary("12345678901"))  // plus de 10 chiffres
    }

    @Test
    fun verifieLeCodeBinaire() {
        val binary = CodeLock.toBinary("4839")!!
        val hash = CodeLock.hash(binary)
        assertTrue(CodeLock.verify(binary, hash))
        assertFalse(CodeLock.verify("1111111111111", hash))
        assertFalse(CodeLock.verify("100101110011", hash))   // tronqué
        assertFalse(CodeLock.verify("not-binary", hash))
    }

    @Test
    fun hashStableEtNonReversible() {
        val a = CodeLock.hash("1001011100111")
        val b = CodeLock.hash("1001011100111")
        assertEquals(a, b)
        assertFalse(a.contains("1001011100111"))
        assertEquals(64, a.length)
    }
}
