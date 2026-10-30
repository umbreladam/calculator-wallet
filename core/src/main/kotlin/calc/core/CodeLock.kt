package calc.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Secret-code lock: only a salted PBKDF2-HMAC-SHA256 hash is stored, never the code. */
class CodeLock(private val store: Store, private val iterations: Int = 120_000) {
    interface Store { fun get(key: String): String?; fun put(key: String, value: String) }

    companion object {
        const val DEFAULT_CODE = "21000000"
        const val MIN_LEN = 4
        const val MAX_LEN = 15
        fun isValidCode(c: String) = c.length in MIN_LEN..MAX_LEN && c.all { it in '0'..'9' }
        fun hash(code: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
    }

    private fun ensureInit() { if (store.get("lock.hash") == null) setCode(DEFAULT_CODE) }

    fun setCode(code: String) {
        require(isValidCode(code)) { "Code must be $MIN_LEN-$MAX_LEN digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.put("lock.salt", UrUtil.hex(salt))
        store.put("lock.iter", iterations.toString())
        store.put("lock.hash", UrUtil.hex(hash(code, salt, iterations)))
    }

    /** Cheap pre-filter so ordinary maths never pays the PBKDF2 cost. */
    fun couldBeCode(entry: String) = isValidCode(entry)

    fun verify(code: String): Boolean {
        if (!isValidCode(code)) return false
        ensureInit()
        val salt = UrUtil.unhex(store.get("lock.salt")!!)
        val iter = store.get("lock.iter")!!.toInt()
        val expected = UrUtil.unhex(store.get("lock.hash")!!)
        return MessageDigest.isEqual(hash(code, salt, iter), expected)
    }
}
