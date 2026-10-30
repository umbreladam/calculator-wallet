package calc.core

import java.math.BigDecimal
import kotlin.math.*

/**
 * Pixel-style expression calculator: the user builds an expression (with smart parentheses and scientific functions),
 * a live preview is shown, and '=' replaces the expression with its result. Display symbols: + − × ÷ ^ % ! π e √ ( ).
 */
class ExprCalc {
    data class Entry(val expr: String, val result: String)

    private val toks = mutableListOf<String>()
    var degrees = false
    var inverse = false
    var error = false; private set
    var justEvaluated = false; private set
    /** In-memory history (newest last). Plain numbers are never recorded, so the unlock code never lands here. */
    val history = mutableListOf<Entry>()

    val expression: String get() = if (error) "Error" else toks.joinToString("")
    val isEmpty get() = toks.isEmpty() && !error

    /** The expression if it is exactly a run of digits typed by the user (the unlock code candidate). */
    fun secretCandidate(): String? = if (!justEvaluated && !error && toks.isNotEmpty() && toks.all { it.length == 1 && it[0] in '0'..'9' }) toks.joinToString("") else null

    private fun last() = toks.lastOrNull()
    private fun isOp(t: String?) = t in listOf("+", "−", "×", "÷", "^")
    private fun isNumTok(t: String?) = t != null && (t[0].isDigit() || t == ".")
    /** Ends an operand: digit, ')', '%', '!', a constant. */
    private fun endsOperand(t: String?) = t != null && (isNumTok(t) || t == ")" || t == "%" || t == "!" || t == "π" || t == "e")
    private fun startFresh() { if (error) { error = false; toks.clear() }; justEvaluated = false }

    fun digit(d: Char) {
        require(d in '0'..'9')
        if (justEvaluated || error) { toks.clear(); error = false }
        justEvaluated = false
        toks.add(d.toString())
    }
    fun dot() {
        if (justEvaluated || error) { toks.clear(); error = false }
        justEvaluated = false
        // no second dot in the current number
        for (i in toks.indices.reversed()) { val t = toks[i]; if (t == ".") return; if (!t[0].isDigit()) break }
        if (!isNumTok(last())) toks.add("0")
        toks.add(".")
    }
    fun op(o: String) {
        require(isOp(o))
        startFresh()
        val l = last()
        when {
            l == null -> if (o == "−") toks.add(o)
            isOp(l) -> { if (o == "−" && (l == "×" || l == "÷" || l == "^")) toks.add(o) else if (toks.size > 1 || o == "−") toks[toks.size - 1] = o }
            l == "(" || l.endsWith("(") -> if (o == "−") toks.add(o)
            else -> toks.add(o)
        }
    }
    fun percent() { startFresh(); if (endsOperand(last())) toks.add("%") }
    fun factorial() { startFresh(); if (endsOperand(last())) toks.add("!") }
    fun constant(c: String) { require(c == "π" || c == "e"); if (justEvaluated) toks.clear(); startFresh(); toks.add(c) }
    /** sin cos tan ln log √ (and with [inverse]: sin⁻¹ cos⁻¹ tan⁻¹ exp 10^ x²). */
    fun function(f: String) {
        if (f == "x²") { startFresh(); if (endsOperand(last())) { toks.add("^"); toks.add("2") }; return }
        if (justEvaluated) { val r = toks.toList(); toks.clear(); justEvaluated = false; toks.add("$f("); toks.addAll(r); toks.add(")"); return }
        startFresh(); toks.add("$f(")
    }
    /** Smart ( ): closes if there is an open bracket and the expression ends with an operand, otherwise opens. */
    fun paren() {
        startFresh()
        val open = toks.count { it.endsWith("(") } - toks.count { it == ")" }
        if (open > 0 && endsOperand(last())) toks.add(")") else toks.add("(")
    }
    fun backspace() { if (error) { error = false; toks.clear(); return }; justEvaluated = false; if (toks.isNotEmpty()) toks.removeAt(toks.size - 1) }
    fun clear() { toks.clear(); error = false; justEvaluated = false }
    fun clearHistory() = history.clear()
    fun load(result: String) { toks.clear(); error = false; justEvaluated = true; tokenize(result) }

    private fun tokenize(s: String) { var i = 0; while (i < s.length) { val c = s[i]
        if (c == 'E') { val m = Regex("E(-?)(\\d+)").find(s, i)!!; toks.addAll(listOf("×", "1", "0", "^")); if (m.groupValues[1] == "-") toks.add("−"); m.groupValues[2].forEach { toks.add(it.toString()) }; i = m.range.last + 1; continue }
        toks.add(if (c == '-') "−" else c.toString()); i++ } }

    /** Live result preview, or "" when the expression is a bare number or incomplete. */
    fun preview(): String {
        if (error || justEvaluated || toks.isEmpty()) return ""
        if (toks.all { isNumTok(it) }) return ""
        return try { format(eval(closed())) } catch (e: Exception) { "" }
    }

    /** Evaluate; on success the expression becomes the result. Returns false (and shows Error) on failure. */
    fun equals(): Boolean {
        if (error || toks.isEmpty()) return false
        if (justEvaluated) return true
        val exprText = closed()
        return try {
            val r = format(eval(exprText))
            if (!toks.all { isNumTok(it) }) history.add(Entry(exprText, r))
            toks.clear(); tokenize(r); justEvaluated = true; true
        } catch (e: Exception) { error = true; toks.clear(); false }
    }

    private fun closed(): String { val open = toks.count { it.endsWith("(") } - toks.count { it == ")" }; return toks.joinToString("") + ")".repeat(max(0, open)) }

    fun eval(s: String): Double = Parser(s, degrees).parse()

    companion object {
        fun format(v: Double): String {
            if (v.isNaN() || v.isInfinite()) throw ArithmeticException("Can't compute")
            val r = if (abs(v) < 1e-12) 0.0 else v
            val bd = BigDecimal(r).round(java.math.MathContext(12)).stripTrailingZeros()
            val a = bd.abs()
            if (bd.signum() == 0) return "0"
            return if (a >= BigDecimal("1e15") || a < BigDecimal("1e-9")) {
                val s = String.format(java.util.Locale.US, "%.10e", bd.toDouble()); val (m, e) = s.split('e')
                m.trimEnd('0').trimEnd('.') + "E" + e.toInt()
            } else bd.toPlainString()
        }
    }

    /** Recursive-descent parser. expr := term ((+|−) term)* ; term := unary ((×|÷|implicit) unary)* ; unary := −unary | power ;
     *  power := postfix (^ unary)? ; postfix := primary (%|!)* ; primary := number | π | e | ( expr ) | fn( expr ). */
    private class Parser(val s: String, val deg: Boolean) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException("bad"); return v }
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun expr(): Double {
            var v = term().first
            while (peek() == '+' || peek() == '−') {
                val op = s[i++]; val (b, pct) = term()
                val rhs = if (pct != null) v * pct / 100 else b   // a ± b%  ->  a ± a·b/100
                v = if (op == '+') v + rhs else v - rhs
            }
            return v
        }
        /** Returns (value, raw percent value if the term was a single "x%"). */
        fun term(): Pair<Double, Double?> {
            val (first, pct) = unaryP(); var v = first; var single = true
            while (true) {
                val c = peek()
                v = when {
                    c == '×' -> { i++; single = false; v * unaryP().first }
                    c == '÷' -> { i++; single = false; val d = unaryP().first; if (d == 0.0) throw ArithmeticException("div0"); v / d }
                    c == '(' || c == 'π' || c == 'e' || c == '√' || c.isLetter() || c.isDigit() || c == '.' -> { single = false; v * unaryP().first }
                    else -> return v to (if (single) pct else null)
                }
            }
        }
        fun unaryP(): Pair<Double, Double?> { if (peek() == '−') { i++; val (v, p) = unaryP(); return -v to p?.let { -it } }; return power() }
        fun power(): Pair<Double, Double?> {
            val (b, p) = postfix()
            if (peek() == '^') { i++; return b.pow(unaryP().first) to null }
            return b to p
        }
        fun postfix(): Pair<Double, Double?> {
            var v = primary(); var pct: Double? = null
            while (peek() == '%' || peek() == '!') {
                if (s[i++] == '%') { pct = v; v /= 100 } else { pct = null; v = fact(v) }
            }
            return v to pct
        }
        fun fact(x: Double): Double { if (x < 0 || x != floor(x) || x > 170) throw ArithmeticException("fact"); var r = 1.0; for (k in 2..x.toInt()) r *= k; return r }
        fun primary(): Double {
            val c = peek()
            if (c.isDigit() || c == '.') { val st = i; while (peek().isDigit() || peek() == '.') i++; return s.substring(st, i).toDouble() }
            if (c == 'π') { i++; return PI }
            if (c == '(') { i++; val v = expr(); expect(')'); return v }
            for (f in FNS) if (s.startsWith("$f(", i)) { i += f.length + 1; val a = expr(); expect(')'); return apply(f, a) }
            if (c == 'e') { i++; return E }
            throw IllegalArgumentException("bad")
        }
        fun expect(c: Char) { if (peek() != c) throw IllegalArgumentException("bad"); i++ }
        fun toRad(x: Double) = if (deg) Math.toRadians(x) else x
        fun fromRad(x: Double) = if (deg) Math.toDegrees(x) else x
        fun apply(f: String, a: Double): Double = when (f) {
            "sin" -> sin(toRad(a)); "cos" -> cos(toRad(a)); "tan" -> { val r = toRad(a); if (abs(cos(r)) < 1e-12) throw ArithmeticException("tan"); tan(r) }
            "sin⁻¹" -> fromRad(asin(a)); "cos⁻¹" -> fromRad(acos(a)); "tan⁻¹" -> fromRad(atan(a))
            "ln" -> { if (a <= 0) throw ArithmeticException("ln"); ln(a) }; "log" -> { if (a <= 0) throw ArithmeticException("log"); log10(a) }
            "√" -> { if (a < 0) throw ArithmeticException("sqrt"); sqrt(a) }
            "exp" -> exp(a); "10^" -> 10.0.pow(a)
            else -> throw IllegalArgumentException(f)
        }
        companion object { val FNS = listOf("sin⁻¹", "cos⁻¹", "tan⁻¹", "sin", "cos", "tan", "ln", "log", "√", "exp", "10^") }
    }
}
