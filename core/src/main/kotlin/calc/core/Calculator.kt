package calc.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Classic pocket-calculator state machine (immediate execution, chaining, repeat '=', %, ±). */
class Calculator {
    enum class Op(val sym: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷") }

    var display: String = "0"; private set
    /** Small expression line shown above the display. */
    var history: String = ""; private set
    /** Digits typed since the last clear with no operator involved (used to detect the secret code). */
    var rawEntry: String = ""; private set
    var pendingOp: Op? = null; private set

    private var acc: BigDecimal? = null
    private var entering = false
    private var lastOp: Op? = null
    private var lastOperand: BigDecimal? = null
    private var error = false
    private var pureEntry = true

    /** Digits typed as a single plain number (no operator) - candidate for the secret code on '='. */
    fun secretCandidate(): String? = if (pureEntry && pendingOp == null && entering && rawEntry.isNotEmpty()) rawEntry else null

    val isAllClear get() = display == "0" && !entering && acc == null

    fun digit(d: Char) {
        require(d in '0'..'9')
        if (error) clearAll()
        if (!entering) { display = "0"; entering = true }
        if (display.replace("-", "").replace(".", "").length >= 15) return
        display = if (display == "0") "$d" else if (display == "-0") "-$d" else display + d
        if (pureEntry) rawEntry += d
    }
    fun dot() {
        if (error) clearAll()
        if (!entering) { display = "0"; entering = true }
        if (!display.contains('.')) display += "."
        pureEntry = false
    }
    fun op(o: Op) {
        if (error) return
        pureEntry = false
        if (entering || acc == null) {
            val cur = value()
            acc = if (acc != null && pendingOp != null && entering) apply(acc!!, pendingOp!!, cur) else cur
            if (error) return
            display = fmt(acc!!)
        }
        pendingOp = o; entering = false; lastOp = null
        history = fmt(acc!!) + " " + o.sym
    }
    fun equals() {
        if (error) return
        val cur = value()
        when {
            pendingOp != null -> {
                val a = acc ?: cur
                history = fmt(a) + " " + pendingOp!!.sym + " " + fmt(cur) + " ="
                val r = apply(a, pendingOp!!, cur); if (error) return
                lastOp = pendingOp; lastOperand = cur; pendingOp = null; acc = r; display = fmt(r)
            }
            lastOp != null -> {
                history = fmt(cur) + " " + lastOp!!.sym + " " + fmt(lastOperand!!) + " ="
                val r = apply(cur, lastOp!!, lastOperand!!); if (error) return
                acc = r; display = fmt(r)
            }
            else -> { acc = cur; display = fmt(cur) }
        }
        entering = false; pureEntry = false
    }
    fun percent() {
        if (error) return
        pureEntry = false
        val cur = value()
        val r = if (acc != null && (pendingOp == Op.ADD || pendingOp == Op.SUB)) acc!!.multiply(cur).divide(BigDecimal(100), MC)
                else cur.divide(BigDecimal(100), MC)
        display = fmt(r); entering = true
    }
    fun negate() {
        if (error) return
        pureEntry = false
        if (!entering) { val v = value().negate(); display = fmt(v); entering = true; acc = if (pendingOp == null) null else acc; return }
        display = if (display.startsWith("-")) display.substring(1) else "-$display"
    }
    fun backspace() {
        if (!entering || error) return
        display = display.dropLast(1).let { if (it.isEmpty() || it == "-") "0" else it }
        if (pureEntry) rawEntry = rawEntry.dropLast(1)
    }
    /** C clears entry; AC clears everything. */
    fun clear() {
        if (entering && display != "0") { display = "0"; if (pureEntry) rawEntry = "" }
        else clearAll()
    }
    fun clearAll() {
        display = "0"; history = ""; rawEntry = ""; pendingOp = null; acc = null; entering = false
        lastOp = null; lastOperand = null; error = false; pureEntry = true
    }

    private fun value(): BigDecimal = try { BigDecimal(display.trimEnd('.')) } catch (e: Exception) { BigDecimal.ZERO }
    private fun apply(a: BigDecimal, o: Op, b: BigDecimal): BigDecimal = when (o) {
        Op.ADD -> a.add(b, MC); Op.SUB -> a.subtract(b, MC); Op.MUL -> a.multiply(b, MC)
        Op.DIV -> if (b.signum() == 0) { error = true; display = "Error"; history = ""; BigDecimal.ZERO } else a.divide(b, MC)
    }

    companion object {
        private val MC = MathContext(20, RoundingMode.HALF_EVEN)
        fun fmt(v: BigDecimal): String {
            val r = v.round(MathContext(12, RoundingMode.HALF_EVEN)).stripTrailingZeros()
            if (r.signum() == 0) return "0"
            val abs = r.abs()
            return if (abs >= BigDecimal("1e15") || abs < BigDecimal("1e-9")) {
                val s = String.format("%.8e", r.toDouble())
                val (m, e) = s.split('e'); m.trimEnd('0').trimEnd('.') + "e" + e.toInt()
            } else r.toPlainString()
        }
    }
}
