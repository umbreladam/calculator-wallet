package calc.core

import org.junit.Assert.*
import org.junit.Test

class ExprCalcTest {
    private fun ev(s: String, deg: Boolean = false) = ExprCalc.format(ExprCalc().apply { degrees = deg }.eval(s))
    /** Types keys: digits . + - * / ( smart-paren ) % ! = and B for backspace. */
    private fun keys(k: String): ExprCalc { val c = ExprCalc(); k.forEach { when (it) {
        in '0'..'9' -> c.digit(it); '.' -> c.dot(); '+' -> c.op("+"); '-' -> c.op("−"); '*' -> c.op("×"); '/' -> c.op("÷"); '^' -> c.op("^")
        'P' -> c.paren(); '%' -> c.percent(); '!' -> c.factorial(); '=' -> c.equals(); 'B' -> c.backspace() } }; return c }

    @Test fun precedence() {
        assertEquals("14", ev("2+3×4")); assertEquals("20", ev("(2+3)×4")); assertEquals("1", ev("8÷4÷2"))
        assertEquals("512", ev("2^3^2")); assertEquals("-4", ev("−2^2")); assertEquals("0.3", ev("0.1+0.2")); assertEquals("7", ev("10−3"))
    }
    @Test fun parenthesesAndImplicit() {
        assertEquals("10", ev("2(3+2)")); assertEquals("25", ev("(2+3)(2+3)")); assertEquals("6.28318530718", ev("2π"))
        val c = keys("P2+3P*4="); assertEquals("20", c.expression)          // smart ( ) opens then closes
        assertEquals("(2+3)", keys("P2+3P").expression)
        assertEquals("5", keys("P2+3=").expression)                        // auto-closed
        assertEquals("3×(", keys("3*P").expression)
    }
    @Test fun percent() {
        assertEquals("0.5", ev("50%")); assertEquals("110", ev("100+10%")); assertEquals("90", ev("100−10%"))
        assertEquals("20", ev("200×10%")); assertEquals("5", keys("50%*10=").expression)
    }
    @Test fun scientific() {
        assertEquals("1", ev("sin(90)", deg = true)); assertEquals("0", ev("sin(π)")); assertEquals("1", ev("cos(0)"))
        assertEquals("1", ev("tan(45)", deg = true)); assertEquals("1", ev("ln(e)")); assertEquals("3", ev("log(1000)"))
        assertEquals("4", ev("√(16)")); assertEquals("120", ev("5!")); assertEquals("1024", ev("2^10")); assertEquals("90", ev("sin⁻¹(1)", deg = true))
        assertEquals("100", ev("10^(2)")); assertEquals("2.71828182846", ev("e"))
        val c = ExprCalc(); c.function("√"); c.digit('9'); c.equals(); assertEquals("3", c.expression)
    }
    @Test fun errorsAndEditing() {
        val c = keys("5/0="); assertEquals("Error", c.expression)
        c.digit('7'); assertEquals("7", c.expression)
        assertEquals("12", keys("123B").expression); assertEquals("2×", keys("2+*").expression)
        assertEquals("1.5", keys("1..5").expression)
        assertEquals("", keys("1+2").also { assertEquals("3", it.preview()) }.let { "" })
        assertEquals("", keys("12").preview())
    }
    @Test fun secretCodeAndHistory() {
        val c = keys("21000000"); assertEquals("21000000", c.secretCandidate())
        assertNull(keys("2100+0000").secretCandidate()); assertNull(keys("21000000=").secretCandidate())
        c.equals(); assertTrue(c.history.isEmpty())                       // plain number (code) is never stored
        val d = keys("2+2="); assertEquals(listOf(ExprCalc.Entry("2+2", "4")), d.history)
        d.digit('5'); assertEquals("5", d.expression)                       // new entry after result
        val e = keys("2+2=+1="); assertEquals("5", e.expression)            // continue from result
    }
}
