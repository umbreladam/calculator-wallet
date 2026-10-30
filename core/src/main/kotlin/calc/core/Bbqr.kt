package calc.core

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** BBQr (Better Bitcoin QR) by Coinkite: https://github.com/coinkite/BBQr */
object Bbqr {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun base32(data: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (b in data) { buf = (buf shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(B32[(buf shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
    fun unbase32(s: String): ByteArray {
        val out = ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s.trimEnd('=')) { val v = B32.indexOf(c); require(v >= 0) { "bad base32" }
            buf = ((buf shl 5) or v) and 0xfffff; bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 } }
        return out.toByteArray()
    }
    fun b36(n: Int) = "" + B36[n / 36] + B36[n % 36]
    fun unb36(s: String): Int { val a = B36.indexOf(s[0]); val b = B36.indexOf(s[1]); require(a >= 0 && b >= 0); return a * 36 + b }

    /**
     * Split [data] into BBQr parts. Uses '2' (Base32) encoding: compression ('Z') needs a 1 KiB deflate window
     * which java.util.zip cannot enforce, so we never emit Z (decoders all accept '2').
     * @param fileType 'P' PSBT, 'T' transaction, 'J' JSON, 'U' text
     */
    fun split(data: ByteArray, fileType: Char, maxCharsPerPart: Int = 400): List<String> {
        val enc = base32(data)
        val per = maxOf(8, maxCharsPerPart - (maxCharsPerPart % 8)) // multiple of 8 => byte aligned
        val count = (enc.length + per - 1) / per
        require(count in 1..1295) { "too large for BBQr" }
        return (0 until count).map { i -> "B$" + "2" + fileType + b36(count) + b36(i) + enc.substring(i * per, minOf(enc.length, (i + 1) * per)) }
    }

    fun isBbqr(s: String) = s.length >= 8 && s.startsWith("B$")

    class Joiner {
        private val parts = HashMap<Int, String>()
        var total = 0; private set
        var encoding = ' '; private set
        var fileType = ' '; private set
        val received get() = parts.size
        val isComplete get() = total > 0 && parts.size == total
        /** @return true if accepted */
        fun add(s: String): Boolean {
            val t = s.trim()
            if (!isBbqr(t)) return false
            val enc = t[2]; val ft = t[3]; val tot = unb36(t.substring(4, 6)); val idx = unb36(t.substring(6, 8))
            if (enc !in "H2Z" || tot < 1 || idx >= tot) return false
            if (total == 0) { total = tot; encoding = enc; fileType = ft }
            else if (tot != total || enc != encoding || ft != fileType) return false
            parts[idx] = t.substring(8)
            return true
        }
        fun result(): ByteArray {
            check(isComplete)
            val body = (0 until total).joinToString("") { parts[it]!! }
            return when (encoding) {
                'H' -> UrUtil.unhex(body)
                '2' -> unbase32(body)
                'Z' -> inflateRaw(unbase32(body))
                else -> error("enc")
            }
        }
    }

    fun inflateRaw(b: ByteArray): ByteArray {
        val inf = Inflater(true); inf.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end(); return out.toByteArray()
    }
}
