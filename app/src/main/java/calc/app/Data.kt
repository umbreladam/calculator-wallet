package calc.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import calc.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoindevkit.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key; used for the wallet list (descriptors are privacy-sensitive). */
object Vault {
    private const val ALIAS = "calc_state_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray()))
    }
    fun decrypt(s: String): String {
        val (iv, ct) = s.split(":"); val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        return String(c.doFinal(Base64.getDecoder().decode(ct)))
    }
}

data class WalletMeta(val id: String, val name: String, val external: String, val internal: String, val network: String, val kind: String, val warnings: List<String>) {
    fun json() = JSONObject().put("id", id).put("name", name).put("ext", external).put("int", internal).put("net", network).put("kind", kind).put("warn", JSONArray(warnings))
    companion object {
        fun from(o: JSONObject) = WalletMeta(o.getString("id"), o.getString("name"), o.getString("ext"), o.getString("int"), o.getString("net"), o.optString("kind"),
            o.optJSONArray("warn")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
    }
}

class Settings(ctx: Context) : CodeLock.Store {
    val p: SharedPreferences = ctx.getSharedPreferences("calc", Context.MODE_PRIVATE)
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }

    var host get() = p.getString("srv.host", "")!!; set(v) = p.edit().putString("srv.host", v).apply()
    var port get() = p.getInt("srv.port", 50001); set(v) = p.edit().putInt("srv.port", v).apply()
    var ssl get() = p.getBoolean("srv.ssl", false); set(v) = p.edit().putBoolean("srv.ssl", v).apply()
    var validateTls get() = p.getBoolean("srv.tlsv", false); set(v) = p.edit().putBoolean("srv.tlsv", v).apply()
    var proxy get() = p.getString("srv.proxy", "")!!; set(v) = p.edit().putString("srv.proxy", v).apply()
    var fiat get() = p.getBoolean("ui.fiat", false); set(v) = p.edit().putBoolean("ui.fiat", v).apply()
    var hideAmounts get() = p.getBoolean("ui.hide", false); set(v) = p.edit().putBoolean("ui.hide", v).apply()
    var sats get() = p.getBoolean("ui.sats", false); set(v) = p.edit().putBoolean("ui.sats", v).apply()
    var usePublic get() = p.getBoolean("srv.public", false); set(v) = p.edit().putBoolean("srv.public", v).apply()
    var pubHost get() = p.getString("pub.host", "electrum.blockstream.info")!!; set(v) = p.edit().putString("pub.host", v).apply()
    var pubPort get() = p.getInt("pub.port", 50002); set(v) = p.edit().putInt("pub.port", v).apply()
    fun server() = if (usePublic) ServerConfig(pubHost.trim(), pubPort, true, proxy.trim().ifEmpty { null }, true)
                   else ServerConfig(host.trim(), port, ssl, proxy.trim().ifEmpty { null }, validateTls)
    /** True when the active server is (or looks like) a public one. */
    fun isPublicActive() = usePublic || (host.isNotBlank() && !ServerCheck.isPrivate(host))
    fun configured() = usePublic || host.isNotBlank()

    fun wallets(): List<WalletMeta> {
        val enc = p.getString("wallets.enc", null) ?: return emptyList()
        return try { val a = JSONArray(Vault.decrypt(enc)); (0 until a.length()).map { WalletMeta.from(a.getJSONObject(it)) } } catch (e: Exception) { emptyList() }
    }
    fun saveWallets(list: List<WalletMeta>) { p.edit().putString("wallets.enc", Vault.encrypt(JSONArray(list.map { it.json() }).toString())).apply() }
}

class Repo(private val ctx: Context) {
    val settings = Settings(ctx)
    val lock = CodeLock(settings)
    private val engines = HashMap<String, WalletEngine>()
    private val dir = File(ctx.noBackupFilesDir, "w").apply { mkdirs() }

    fun network(m: WalletMeta) = Network.valueOf(m.network)

    @Synchronized fun engine(m: WalletMeta): WalletEngine = engines.getOrPut(m.id) {
        WalletEngine(m.external, m.internal, network(m), File(dir, "${m.id}.sqlite").absolutePath)
    }

    fun add(c: WalletCandidate, name: String, network: Network): WalletMeta {
        val m = WalletMeta(UUID.randomUUID().toString(), name.ifBlank { c.label }, c.external, c.internal, network.name, c.kind, c.warnings)
        // validate with BDK before saving (throws on bad descriptors)
        WalletEngine(m.external, m.internal, network, null).use { it.peek(false, 0) }
        settings.saveWallets(settings.wallets() + m)
        return m
    }
    fun rename(id: String, name: String) = settings.saveWallets(settings.wallets().map { if (it.id == id) it.copy(name = name) else it })
    @Synchronized fun delete(id: String) {
        engines.remove(id)?.close()
        File(dir, "$id.sqlite").delete(); File(dir, "$id.sqlite-journal").delete(); File(dir, "$id.sqlite-wal").delete()
        settings.saveWallets(settings.wallets().filter { it.id != id })
    }
    @Synchronized fun closeAll() { engines.values.forEach { runCatching { it.close() } }; engines.clear() }

    /** Optional fiat price (off by default). Goes through the SOCKS proxy when configured. */
    suspend fun gbpPrice(): Double? = withContext(Dispatchers.IO) {
        if (!settings.fiat) return@withContext null
        try {
            val px = settings.proxy.trim()
            val proxy = if (px.contains(":")) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(px.substringBeforeLast(":"), px.substringAfterLast(":").toInt())) else Proxy.NO_PROXY
            val c = URL("https://mempool.space/api/v1/prices").openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.inputStream.bufferedReader().use { JSONObject(it.readText()).getDouble("GBP") }
        } catch (e: Exception) { null }
    }
}
