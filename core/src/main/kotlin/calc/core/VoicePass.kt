package calc.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

/**
 * Spoken passphrase, the second unlock factor. Only a salted PBKDF2 hash of the canonical text is stored.
 * Canonical form: lowercase, accents removed, punctuation dropped, and all whitespace removed, so ASR
 * variants such as "Satoshi Nakamoto.", "satoshi nakamoto" and "satoshi naka moto" match.
 */
class VoicePass(private val store: CodeLock.Store, private val iterations: Int = 20_000) {
    companion object {
        const val DEFAULT_PHRASE = "Satoshi Nakamoto"
        const val MIN_CHARS = 4
        /** Leading/trailing filler words that ASR or the speaker may add, tried trimmed off. */
        private const val MAX_TRIM = 2

        fun normalise(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return n.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        fun canonical(s: String) = normalise(s).replace(" ", "")
        fun isValidPhrase(s: String) = canonical(s).length >= MIN_CHARS

        /** Canonical candidates from the recogniser's n-best list: each phrase, plus versions with up to 2 words trimmed from either end. */
        fun candidates(alternatives: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (alt in alternatives) {
                val w = normalise(alt).split(" ").filter { it.isNotEmpty() }
                for (a in 0..minOf(MAX_TRIM, w.size)) for (b in 0..minOf(MAX_TRIM, w.size - a)) {
                    val c = w.subList(a, w.size - b).joinToString("")
                    if (c.length >= MIN_CHARS) out.add(c)
                }
            }
            return out.toList()
        }
    }

    private fun ensureInit() { if (store.get("voice.hash") == null) setPhrase(DEFAULT_PHRASE) }

    fun setPhrase(phrase: String) {
        require(isValidPhrase(phrase)) { "Passphrase too short" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.put("voice.salt", UrUtil.hex(salt)); store.put("voice.iter", iterations.toString())
        store.put("voice.hash", UrUtil.hex(CodeLock.hash(canonical(phrase), salt, iterations)))
    }

    /** True if any n-best alternative (or a lightly trimmed version) matches the stored hash. */
    fun verify(alternatives: List<String>): Boolean {
        ensureInit()
        val salt = UrUtil.unhex(store.get("voice.salt")!!); val iter = store.get("voice.iter")!!.toInt()
        val expected = UrUtil.unhex(store.get("voice.hash")!!)
        var ok = false
        for (c in candidates(alternatives).take(60)) if (MessageDigest.isEqual(CodeLock.hash(c, salt, iter), expected)) ok = true
        return ok
    }
}
