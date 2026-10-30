package calc.core

import java.math.BigInteger

object Base58 {
    private const val A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    fun encode(input: ByteArray): String {
        var n = BigInteger(1, input); val sb = StringBuilder(); val b58 = BigInteger.valueOf(58)
        while (n.signum() > 0) { val qr = n.divideAndRemainder(b58); sb.append(A[qr[1].toInt()]); n = qr[0] }
        for (b in input) { if (b.toInt() == 0) sb.append('1') else break }
        return sb.reverse().toString()
    }
    fun decode(s: String): ByteArray {
        var n = BigInteger.ZERO; val b58 = BigInteger.valueOf(58)
        for (c in s) { val v = A.indexOf(c); require(v >= 0) { "bad base58" }; n = n.multiply(b58).add(BigInteger.valueOf(v.toLong())) }
        var bytes = n.toByteArray(); if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
        val zeros = s.takeWhile { it == '1' }.length
        return ByteArray(zeros) + (if (n.signum() == 0) ByteArray(0) else bytes)
    }
    fun checkEncode(payload: ByteArray): String = encode(payload + dsha(payload).copyOf(4))
    fun checkDecode(s: String): ByteArray {
        val raw = decode(s); require(raw.size > 4) { "short" }
        val p = raw.copyOf(raw.size - 4)
        require(dsha(p).copyOf(4).contentEquals(raw.copyOfRange(raw.size - 4, raw.size))) { "bad checksum" }
        return p
    }
    fun dsha(b: ByteArray) = UrUtil.sha256(UrUtil.sha256(b))
}

/** SLIP-132 extended public key helpers. */
object ExtKeys {
    enum class Hint { P2PKH, P2SH_P2WPKH, P2WPKH, P2SH_P2WSH, P2WSH, NONE }
    data class Info(val mainnet: Boolean, val hint: Hint)
    private val XPUB = UrUtil.unhex("0488b21e"); private val TPUB = UrUtil.unhex("043587cf")
    val versions: Map<String, Info> = mapOf(
        "0488b21e" to Info(true, Hint.P2PKH), "049d7cb2" to Info(true, Hint.P2SH_P2WPKH), "04b24746" to Info(true, Hint.P2WPKH),
        "0295b43f" to Info(true, Hint.P2SH_P2WSH), "02aa7ed3" to Info(true, Hint.P2WSH),
        "043587cf" to Info(false, Hint.P2PKH), "044a5262" to Info(false, Hint.P2SH_P2WPKH), "045f1cf6" to Info(false, Hint.P2WPKH),
        "024289ef" to Info(false, Hint.P2SH_P2WSH), "02575483" to Info(false, Hint.P2WSH),
    )
    val PUB_REGEX = Regex("\\b[xyzXYZtuvUV]pub[1-9A-HJ-NP-Za-km-z]{100,112}\\b")

    fun info(key: String): Info? = try { val p = Base58.checkDecode(key); if (p.size != 78) null else versions[UrUtil.hex(p.copyOf(4))] } catch (e: Exception) { null }

    /** Convert any SLIP-132 pub to plain xpub/tpub as required by descriptors. */
    fun normalize(key: String): String {
        val p = Base58.checkDecode(key); require(p.size == 78) { "Not an extended key" }
        val inf = versions[UrUtil.hex(p.copyOf(4))] ?: throw IllegalArgumentException("Unknown extended key version")
        return Base58.checkEncode((if (inf.mainnet) XPUB else TPUB) + p.copyOfRange(4, 78))
    }
    fun normalizeAll(text: String): String = PUB_REGEX.replace(text) { m -> try { normalize(m.value) } catch (e: Exception) { m.value } }

    /** Build an xpub from raw parts (used for UR crypto-hdkey). */
    fun build(mainnet: Boolean, depth: Int, parentFp: ByteArray, child: Long, chainCode: ByteArray, pubKey: ByteArray): String {
        require(chainCode.size == 32 && pubKey.size == 33)
        val b = (if (mainnet) XPUB else TPUB) + byteArrayOf(depth.toByte()) + parentFp.copyOf(4) + UrUtil.u32be(child) + chainCode + pubKey
        return Base58.checkEncode(b)
    }
}

/** Refuses anything that looks like private key material. This app is strictly watch-only. */
object SecretGuard {
    private val PRV = Regex("[xyzXYZtuvUV]prv[1-9A-HJ-NP-Za-km-z]{20,}")
    private val WIF = Regex("(?<![1-9A-HJ-NP-Za-km-z])[5KLc9][1-9A-HJ-NP-Za-km-z]{50,51}(?![1-9A-HJ-NP-Za-km-z])")
    private val HEX_SECRET = Regex("(?i)\"(xprv|private_?key|privkey|seed|mnemonic|entropy)\"\\s*:\\s*(\"[^\"]+\"|\\{|\\[)")
    private val words: Set<String> by lazy {
        SecretGuard::class.java.getResourceAsStream("/bip39-english.txt")!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    private val SECRET_UR = setOf("crypto-seed", "seed", "crypto-bip39", "bip39", "crypto-eckey", "eckey", "crypto-sskr", "sskr", "envelope")

    class SecretMaterialException(msg: String) : IllegalArgumentException(msg)

    fun check(text: String) {
        if (PRV.containsMatchIn(text)) throw SecretMaterialException("Private extended key (xprv/tprv) detected. This app is watch-only — import only public keys.")
        WIF.findAll(text).forEach { m ->
            val ok = try { val p = Base58.checkDecode(m.value); (p.size == 33 || p.size == 34) && (p[0].toInt() and 0xff) in setOf(0x80, 0xef) } catch (e: Exception) { false }
            if (ok) throw SecretMaterialException("WIF private key detected. This app is watch-only.")
        }
        if (HEX_SECRET.containsMatchIn(text)) throw SecretMaterialException("Input contains a seed/private key field. This app is watch-only.")
        // consecutive BIP39 words (>= 12) => seed phrase
        var run = 0
        for (tok in text.lowercase().split(Regex("[^a-z]+"))) {
            if (tok.isEmpty()) continue
            if (tok in words) { run++; if (run >= 12) throw SecretMaterialException("Seed words detected. Never enter your seed here — this app is watch-only.") } else run = 0
        }
        // digit-encoded SeedQR (48 or 96 digits of 4-digit word indexes)
        val t = text.trim()
        if ((t.length == 48 || t.length == 96) && t.all { it.isDigit() } && t.chunked(4).all { it.toInt() < 2048 })
            throw SecretMaterialException("This looks like a SeedQR. Never scan your seed into this app.")
    }
    fun checkUrType(type: String) { if (type.lowercase() in SECRET_UR) throw SecretMaterialException("UR type '$type' contains secrets. This app is watch-only.") }
    /** CompactSeedQR: raw 16/32 bytes in a binary QR. */
    fun checkBinary(b: ByteArray) { if (b.size == 16 || b.size == 32) throw SecretMaterialException("Binary 16/32-byte QR looks like a CompactSeedQR. Refusing.") }
}
