package calc.core

import org.junit.Assert.*
import org.junit.Test

class CalcLockTest {
    private fun run(keys: String): Calculator { val c = Calculator(); keys.forEach { k -> when (k) {
        in '0'..'9' -> c.digit(k); '.' -> c.dot(); '+' -> c.op(Calculator.Op.ADD); '-' -> c.op(Calculator.Op.SUB)
        '*' -> c.op(Calculator.Op.MUL); '/' -> c.op(Calculator.Op.DIV); '=' -> c.equals(); '%' -> c.percent(); 'n' -> c.negate(); 'C' -> c.clear() } }; return c }

    @Test fun basics() {
        assertEquals("5", run("2+3=").display)
        assertEquals("5", run("2+3*").display) // chaining shows running total
        assertEquals("20", run("2+3*4=").display) // immediate execution like a pocket calculator
        assertEquals("0.3", run(".1+.2=").display)
        assertEquals("Error", run("5/0=").display)
        assertEquals("-7", run("7n").display)
        assertEquals("15", run("10+50%=").display) // 10 + 10*50%
        assertEquals("0.5", run("50%").display)
        assertEquals("8", run("2*2==").display) // repeat =
        assertEquals("8", run("2+2=+4=").display)
        assertEquals("1.5", run("3/2=").display)
        assertEquals("0.333333333333", run("1/3=").display)
        assertEquals("9", run("5+4C4=").display)
        assertEquals("21000000", run("21000000=").display) // wrong/right codes still do maths
    }
    @Test fun secretCandidate() {
        val c = Calculator(); "21000000".forEach { c.digit(it) }
        assertEquals("21000000", c.secretCandidate())
        val d = run("2+21000000"); assertNull(d.secretCandidate())
        val e = run("21000000."); assertNull(e.secretCandidate())
    }

    private class Mem : CodeLock.Store { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value } }

    @Test fun codeLock() {
        val s = Mem(); val l = CodeLock(s, 1000)
        assertTrue(l.verify("21000000")); assertFalse(l.verify("21000001")); assertFalse(l.verify("2+2"))
        l.setCode("1337420")
        assertFalse(l.verify("21000000")); assertTrue(l.verify("1337420"))
        // never stores plaintext
        assertFalse(s.m.values.any { it.contains("1337420") || it.contains("21000000") })
        assertEquals(64, s.m["lock.hash"]!!.length); assertEquals(32, s.m["lock.salt"]!!.length)
        assertThrows(IllegalArgumentException::class.java) { l.setCode("12") }
        // salted: same code -> different hash
        val h1 = s.m["lock.hash"]; l.setCode("1337420"); assertNotEquals(h1, s.m["lock.hash"])
    }
}
