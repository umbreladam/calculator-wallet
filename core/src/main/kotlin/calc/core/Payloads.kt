package calc.core

import java.util.Base64

/** Classifies data coming back from a signer (scan or file): PSBT (binary/base64/hex) or raw transaction (hex/binary). */
sealed class SignedPayload {
    class Psbt(val bytes: ByteArray) : SignedPayload() { val base64: String get() = Base64.getEncoder().encodeToString(bytes) }
    class RawTx(val bytes: ByteArray) : SignedPayload()

    companion object {
        val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        fun isPsbt(b: ByteArray) = b.size > 5 && b.copyOf(5).contentEquals(MAGIC)
        fun fromBytes(b: ByteArray): SignedPayload {
            if (isPsbt(b)) return Psbt(b)
            val s = String(b, Charsets.US_ASCII).trim()
            if (s.isNotEmpty() && s.all { it.code in 32..126 || it.isWhitespace() }) return fromText(s)
            return RawTx(b)
        }
        fun fromText(t: String): SignedPayload {
            val s = t.trim().replace(Regex("\\s"), "")
            if (s.startsWith("cHNidP")) return Psbt(Base64.getDecoder().decode(s))
            if (s.matches(Regex("[0-9a-fA-F]+")) && s.length % 2 == 0) {
                val b = UrUtil.unhex(s); return if (isPsbt(b)) Psbt(b) else RawTx(b)
            }
            throw IllegalArgumentException("Not a PSBT or transaction")
        }
        fun fromUr(ur: UR): SignedPayload = when (ur.type) {
            "crypto-psbt", "psbt" -> Psbt(Cbor.decode(ur.cbor) as ByteArray)
            "bytes" -> fromBytes(Cbor.decode(ur.cbor) as ByteArray)
            else -> throw IllegalArgumentException("Unexpected UR type ${ur.type}")
        }
        fun fromBbqr(j: Bbqr.Joiner): SignedPayload { val b = j.result(); return when (j.fileType) { 'P' -> Psbt(b); 'T' -> RawTx(b); else -> fromBytes(b) } }
    }
}

/** Export encoders for air-gapped signers. */
object PsbtExport {
    fun urParts(psbt: ByteArray, maxFragment: Int = 200): UrEncoder = UrEncoder(UR("crypto-psbt", Cbor.encodeBytes(psbt)), maxFragment)
    fun bbqrParts(psbt: ByteArray, maxChars: Int = 400) = Bbqr.split(psbt, 'P', maxChars)
}

object ServerCheck {
    /** True if host is a private / tailnet / local / onion address (i.e. the user's own node). */
    fun isPrivate(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.endsWith(".onion") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".ts.net") || h.endsWith(".home.arpa") || h == "localhost" || h.endsWith(".internal")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = p[0] to p[1]
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        if (h.startsWith("fd") || h.startsWith("fc") || h == "::1" || h.startsWith("fe80")) return true
        return false
    }
}
