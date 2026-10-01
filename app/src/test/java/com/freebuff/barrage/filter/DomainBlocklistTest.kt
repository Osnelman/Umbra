package com.freebuff.barrage.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlocklistTest {

    private fun list(vararg lines: String): DomainBlocklist {
        val l = DomainBlocklist()
        l.load(lines.asList().asSequence())
        return l
    }

    @Test
    fun bloqueLeDomaineEtSesSousDomaines() {
        val l = list("pornhub.com")
        assertTrue(l.isBlocked("pornhub.com"))
        assertTrue(l.isBlocked("www.pornhub.com"))
        assertTrue(l.isBlocked("cdn-images.www.pornhub.com"))
        assertFalse(l.isBlocked("mywebsite.com"))
        assertFalse(l.isBlocked("example.com"))
    }

    @Test
    fun accepteTousLesFormatsDeLignes() {
        val l = list(
            "! commentaire adguard",
            "# commentaire hosts",
            "",
            "/regex-ignore-me/",
            "0.0.0.0 xvideos.com",
            "||onlyfans.com^\$third-party",
            "@@||safe.example.com^",
            "127.0.0.1 xnxx.com # local"
        )
        assertTrue(l.isBlocked("xvideos.com"))
        assertTrue(l.isBlocked("onlyfans.com"))
        assertTrue(l.isBlocked("xnxx.com"))
        assertFalse(l.isBlocked("safe.example.com"))
        assertFalse(l.isBlocked("sub.safe.example.com"))
    }

    @Test
    fun lesExceptionsPassentEnPriorite() {
        val l = list("example.com", "@@example.com")
        assertFalse(l.isBlocked("example.com"))
        assertFalse(l.isBlocked("www.example.com"))
        assertTrue(l.isBlocked("pornhub.com"))
    }

    @Test
    fun bloqueLesMotsClesMemeHorsListe() {
        val l = list()
        assertTrue(l.isBlocked("freepornhub.tv"))
        assertTrue(l.isBlocked("xxx-cinema.net"))
        assertTrue(l.isBlocked("myonlyfans-proxy.io"))
        assertTrue(l.isBlocked("xnxx-mirror.xyz"))
        assertFalse(l.isBlocked("wikipedia.org"))
        assertFalse(l.isBlocked("lemonde.fr"))
    }

    @Test
    fun motCleStrictSansFauxPositifs() {
        val l = list()
        assertTrue(l.isBlocked("sex.com"))
        assertTrue(l.isBlocked("sexvideos.example"))
        assertFalse(l.isBlocked("sussex.gov.uk"))
        assertFalse(l.isBlocked("essex.gov.uk"))
    }

    @Test
    fun bloqueDesTldsEntiers() {
        val l = list("xxx")
        assertTrue(l.isBlocked("tube.xxx"))
        assertTrue(l.isBlocked("anything.sub.xxx"))
        assertFalse(l.isBlocked("www.example.com")) // le TLD est .com, pas .xxx
    }

    @Test
    fun ignoreLesEntréesInvalides() {
        val l = list("http://bad.example/path", "1.2.3.4", "UPPER.COM")
        assertEquals(1, l.size)
        assertTrue(l.isBlocked("upper.com"))
        assertTrue(l.isBlocked("sub.upper.com"))
    }
}
