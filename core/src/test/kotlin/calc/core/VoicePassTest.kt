package calc.core

import org.junit.Assert.*
import org.junit.Test

class VoicePassTest {
    private class Mem : CodeLock.Store { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value } }

    @Test fun normalisation() {
        assertEquals("satoshi nakamoto", VoicePass.normalise("  Satoshi,   NAKAMOTO!! "))
        assertEquals("cafe creme", VoicePass.normalise("Café-Crème"))
        assertEquals("satoshinakamoto", VoicePass.canonical("satoshi naka moto"))
        assertEquals(VoicePass.canonical("Satoshi Nakamoto."), VoicePass.canonical("satoshi\tnakamoto"))
        assertFalse(VoicePass.isValidPhrase(" a. b ")); assertTrue(VoicePass.isValidPhrase("open sesame"))
    }

    @Test fun candidatesTrimFillers() {
        val c = VoicePass.candidates(listOf("um satoshi nakamoto please"))
        assertTrue("satoshinakamoto" in c); assertTrue("umsatoshinakamotoplease" in c)
        assertFalse(c.any { it.length < VoicePass.MIN_CHARS })
    }

    @Test fun defaultMatchesVariantsAndNBest() {
        val v = VoicePass(Mem(), iterations = 1000)
        for (s in listOf("Satoshi Nakamoto", "satoshi nakamoto", "satoshi naka moto", "SATOSHI NAKAMOTO.", "uh, Satoshi Nakamoto"))
            assertTrue(s, v.verify(listOf(s)))
        assertTrue(v.verify(listOf("so tushy naka motor", "satoshi nakamoto")))  // second n-best alternative
        assertFalse(v.verify(listOf("hal finney"))); assertFalse(v.verify(emptyList())); assertFalse(v.verify(listOf("")))
        assertFalse(v.verify(listOf("satoshi")))
    }

    @Test fun hashOnlyAndChange() {
        val st = Mem(); val v = VoicePass(st, iterations = 1000)
        v.setPhrase("Open Sesame")
        assertFalse(st.m.values.any { it.contains("sesame", true) })
        assertEquals(64, st.m["voice.hash"]!!.length)
        assertTrue(v.verify(listOf("open sesame"))); assertFalse(v.verify(listOf("satoshi nakamoto")))
        val h1 = st.m["voice.hash"]; v.setPhrase("Open Sesame"); assertNotEquals(h1, st.m["voice.hash"]) // fresh salt
        assertThrows(IllegalArgumentException::class.java) { v.setPhrase("ab") }
    }
}
