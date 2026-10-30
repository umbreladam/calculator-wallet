package calc.core

import java.io.ByteArrayOutputStream

/** Minimal CBOR (RFC 8949) for UR payloads. Decoded values: Long, ByteArray, String, List, Map<Any,Any?>, Tagged, Boolean, null. */
object Cbor {
    data class Tagged(val tag: Long, val value: Any?)

    class Writer {
        private val out = ByteArrayOutputStream()
        private fun head(major: Int, v: Long) {
            val m = major shl 5
            when {
                v < 24 -> out.write(m or v.toInt())
                v < 0x100 -> { out.write(m or 24); out.write(v.toInt()) }
                v < 0x10000 -> { out.write(m or 25); out.write((v shr 8).toInt()); out.write(v.toInt() and 0xff) }
                v < 0x100000000L -> { out.write(m or 26); for (s in intArrayOf(24, 16, 8, 0)) out.write(((v shr s) and 0xff).toInt()) }
                else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write(((v shr s) and 0xff).toInt()) }
            }
        }
        fun uint(v: Long) = apply { head(0, v) }
        fun bytes(b: ByteArray) = apply { head(2, b.size.toLong()); out.write(b) }
        fun text(s: String) = apply { val b = s.toByteArray(); head(3, b.size.toLong()); out.write(b) }
        fun array(n: Int) = apply { head(4, n.toLong()) }
        fun map(n: Int) = apply { head(5, n.toLong()) }
        fun tag(t: Long) = apply { head(6, t) }
        fun bool(b: Boolean) = apply { out.write(if (b) 0xf5 else 0xf4) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    class Reader(private val b: ByteArray) {
        var pos = 0
        private fun u8() = b[pos++].toInt() and 0xff
        private fun arg(ai: Int): Long = when {
            ai < 24 -> ai.toLong()
            ai == 24 -> u8().toLong()
            ai == 25 -> ((u8() shl 8) or u8()).toLong()
            ai == 26 -> { var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; v }
            ai == 27 -> { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; v }
            else -> error("indefinite length unsupported")
        }
        fun read(): Any? {
            val ib = u8(); val major = ib shr 5; val ai = ib and 0x1f
            return when (major) {
                0 -> arg(ai)
                1 -> -1 - arg(ai)
                2 -> { val n = arg(ai).toInt(); require(n >= 0 && pos + n <= b.size); b.copyOfRange(pos, pos + n).also { pos += n } }
                3 -> { val n = arg(ai).toInt(); require(n >= 0 && pos + n <= b.size); String(b, pos, n).also { pos += n } }
                4 -> { val n = arg(ai).toInt(); List(n) { read() } }
                5 -> { val n = arg(ai).toInt(); val m = LinkedHashMap<Any?, Any?>(); repeat(n) { val k = read(); m[k] = read() }; m }
                6 -> Tagged(arg(ai), read())
                7 -> when (ai) { 20 -> false; 21 -> true; 22, 23 -> null; else -> error("unsupported simple") }
                else -> error("bad cbor")
            }
        }
    }
    fun decode(b: ByteArray): Any? = Reader(b).read()
    fun encodeBytes(b: ByteArray) = Writer().bytes(b).toByteArray()
}
