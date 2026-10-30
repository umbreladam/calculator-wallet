package calc.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses watch-only wallet imports: bare xpub/ypub/zpub/tpub, output descriptors (incl. multipath, BSMS, Core JSON),
 * Sparrow exports, Coldcard generic JSON / multisig text, Electrum JSON, UR crypto-output / crypto-account / crypto-hdkey.
 * Throws [SecretGuard.SecretMaterialException] on anything private.
 */
object ImportParser {

    fun parse(input: String, nameHint: String = "Wallet"): List<WalletCandidate> {
        val text = input.trim().removePrefix("\uFEFF")
        require(text.isNotEmpty()) { "Nothing to import" }
        SecretGuard.check(text)
        if (text.lowercase().startsWith("ur:")) return parseUr(UrDecoder.decodeSingle(text), nameHint)
        if (text.startsWith("{") || text.startsWith("[")) parseJson(text, nameHint)?.let { if (it.isNotEmpty()) return it }
        coldcardMultisig(text, nameHint)?.let { return listOf(it) }
        val descs = DescriptorTools.extract(text)
        if (descs.isNotEmpty()) return pairDescriptors(descs, nameHint)
        // bare extended public key(s)
        val keys = ExtKeys.PUB_REGEX.findAll(text).map { it.value }.distinct().toList()
        require(keys.isNotEmpty()) { "No xpub or descriptor found" }
        return keys.map { k -> bareKey(k, null, null, nameHint) }
    }

    fun bareKey(key: String, fp: String?, deriv: String?, name: String): WalletCandidate {
        val info = ExtKeys.info(key) ?: throw IllegalArgumentException("Invalid extended public key")
        require(info.hint != ExtKeys.Hint.P2WSH && info.hint != ExtKeys.Hint.P2SH_P2WSH) { "This is a multisig cosigner key (Zpub/Ypub). Import the full multisig descriptor instead." }
        val d = DescriptorTools.singleSig(info.hint, DescriptorTools.purposeOf(deriv), DescriptorTools.keyExpr(fp, deriv, key))
        return DescriptorTools.candidate(name, d)
    }

    private fun pairDescriptors(descs: List<String>, name: String): List<WalletCandidate> {
        val stripped = descs.map { DescriptorChecksum.strip(it) }
        // prefer multipath descriptors
        val multi = descs.filter { it.contains(Regex("<\\d+;\\d+>")) || it.contains("/**") }
        if (multi.isNotEmpty()) return multi.map { DescriptorTools.candidate(name, it) }.distinctBy { it.external }
        val used = HashSet<Int>(); val out = ArrayList<WalletCandidate>()
        for ((i, d) in stripped.withIndex()) {
            if (i in used) continue
            if (d.contains("/0/*")) {
                val j = stripped.indexOfFirst { it == d.replace("/0/*", "/1/*") }
                if (j >= 0) { used += j; used += i; out += DescriptorTools.candidate(name, descs[i], descs[j]); continue }
            }
            if (d.contains("/1/*") && stripped.any { it == d.replace("/1/*", "/0/*") }) continue
            used += i; out += DescriptorTools.candidate(name, descs[i])
        }
        return out.distinctBy { it.external }
    }

    // ---------- JSON ----------
    private fun parseJson(text: String, name: String): List<WalletCandidate>? = try {
        if (text.startsWith("[")) { val a = JSONArray(text); pairDescriptors((0 until a.length()).flatMap { DescriptorTools.extract(a.get(it).toString()) }, name) }
        else jsonObject(JSONObject(text), name)
    } catch (e: SecretGuard.SecretMaterialException) { throw e } catch (e: org.json.JSONException) { null }

    private fun jsonObject(o: JSONObject, nameIn: String): List<WalletCandidate>? {
        val name = o.optString("label").ifEmpty { o.optString("name").ifEmpty { nameIn } }
        // Sparrow wallet JSON
        if (o.has("keystores") && o.has("scriptType")) return listOf(sparrow(o, name))
        // Electrum wallet file
        o.optJSONObject("keystore")?.let { ks ->
            val xpub = ks.optString("xpub"); if (xpub.isNotEmpty())
                return listOf(bareKey(xpub, ks.optString("root_fingerprint").ifEmpty { null }, ks.optString("derivation").ifEmpty { null }, name))
        }
        // Coldcard generic JSON export
        val bipKeys = listOf("bip84", "bip86", "bip49", "bip44")
        if (bipKeys.any { o.has(it) } && (o.has("xfp") || o.has("chain"))) {
            val top = o.optString("xfp")
            val list = bipKeys.mapNotNull { k ->
                val s = o.optJSONObject(k) ?: return@mapNotNull null
                val desc = s.optString("desc")
                val label = "$name · ${k.uppercase()}"
                if (desc.isNotEmpty()) DescriptorTools.candidate(label, desc)
                else {
                    val xpub = s.optString("xpub").ifEmpty { s.optString("_pub") }
                    val deriv = s.optString("deriv"); val fp = s.optString("xfp").ifEmpty { top }
                    val d = DescriptorTools.singleSig(null, DescriptorTools.purposeOf(deriv), DescriptorTools.keyExpr(fp, deriv, xpub))
                    DescriptorTools.candidate(label, d)
                }
            }
            if (list.isNotEmpty()) return list
        }
        // Core listdescriptors / Specter / anything with descriptors inside
        val descs = DescriptorTools.extract(o.toString().replace("\\/", "/"))
        if (descs.isNotEmpty()) return pairDescriptors(descs, name)
        return null
    }

    private fun sparrow(o: JSONObject, name: String): WalletCandidate {
        val ks = o.getJSONArray("keystores")
        val keys = (0 until ks.length()).map { i ->
            val k = ks.getJSONObject(i); val kd = k.optJSONObject("keyDerivation")
            DescriptorTools.keyExpr(kd?.optString("masterFingerprint")?.ifEmpty { null }, kd?.optString("derivation")?.ifEmpty { null }, k.getString("extendedPublicKey")) + "/<0;1>/*"
        }
        val st = o.getString("scriptType")
        val mini = o.optJSONObject("defaultPolicy")?.optString("miniscript") ?: ""
        val thr = Regex("multi\\((\\d+)").find(mini)?.groupValues?.get(1)
        val d = if (keys.size == 1 && thr == null) when (st) {
            "P2WPKH" -> "wpkh(${keys[0]})"; "P2SH_P2WPKH" -> "sh(wpkh(${keys[0]}))"; "P2TR" -> "tr(${keys[0]})"; "P2PKH" -> "pkh(${keys[0]})"
            else -> throw IllegalArgumentException("Unsupported script type $st")
        } else {
            val sm = "sortedmulti(${thr ?: error("missing threshold")},${keys.joinToString(",")})"
            when (st) { "P2WSH" -> "wsh($sm)"; "P2SH_P2WSH" -> "sh(wsh($sm))"; "P2SH" -> "sh($sm)"; else -> throw IllegalArgumentException("Unsupported multisig script type $st") }
        }
        return DescriptorTools.candidate(name, d)
    }

    // ---------- Coldcard multisig text ----------
    private fun coldcardMultisig(text: String, nameIn: String): WalletCandidate? {
        val policy = Regex("(?im)^\\s*Policy:\\s*(\\d+)\\s*of\\s*(\\d+)").find(text) ?: return null
        val name = Regex("(?im)^\\s*Name:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: nameIn
        val format = Regex("(?im)^\\s*Format:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.uppercase() ?: "P2WSH"
        var deriv: String? = null; val keys = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            Regex("(?i)^Derivation:\\s*(\\S+)").find(l)?.let { deriv = it.groupValues[1] }
            Regex("^([0-9A-Fa-f]{8})\\s*:\\s*(\\S+)$").find(l)?.let {
                keys += DescriptorTools.keyExpr(it.groupValues[1], deriv ?: error("Missing Derivation"), it.groupValues[2]) + "/<0;1>/*"
            }
        }
        require(keys.size == policy.groupValues[2].toInt()) { "Expected ${policy.groupValues[2]} keys, found ${keys.size}" }
        val sm = "sortedmulti(${policy.groupValues[1]},${keys.joinToString(",")})"
        val d = when (format) { "P2WSH" -> "wsh($sm)"; "P2SH-P2WSH", "P2WSH-P2SH", "P2SH_P2WSH" -> "sh(wsh($sm))"; "P2SH" -> "sh($sm)"; else -> error("Unsupported format $format") }
        return DescriptorTools.candidate(name, d)
    }

    // ---------- UR ----------
    fun parseUr(ur: UR, name: String = "Wallet"): List<WalletCandidate> {
        SecretGuard.checkUrType(ur.type)
        val v = Cbor.decode(ur.cbor)
        return when (ur.type) {
            "bytes" -> parse(String(v as ByteArray), name)
            "crypto-output", "output-descriptor" -> listOf(outputFromCbor(v, name, null))
            "crypto-account", "account-descriptor" -> account(v, name)
            "crypto-hdkey", "hdkey" -> listOf(hdkeyCandidate(untag(v), name))
            else -> throw IllegalArgumentException("Unsupported UR type '${ur.type}' for import")
        }
    }
    private fun tagOf(v: Any?) = (v as? Cbor.Tagged)?.tag?.let { if (it >= 40000) it - 40000 else it }
    private fun untag(v: Any?): Any? = if (v is Cbor.Tagged) v.value else v

    private fun account(v: Any?, name: String): List<WalletCandidate> {
        val m = untag(v) as Map<*, *>
        val fp = (m[1L] as? Long)?.let { "%08x".format(it) }
        val outs = m[2L] as List<*>
        return outs.mapNotNull { o -> try { outputFromCbor(o, name, fp) } catch (e: SecretGuard.SecretMaterialException) { throw e } catch (e: Exception) { null } }
            .map { it.copy(label = "$name · ${it.kind}") }
            .ifEmpty { throw IllegalArgumentException("No supported accounts in UR") }
    }

    private fun outputFromCbor(v: Any?, name: String, accountFp: String?): WalletCandidate {
        val inner = untag(v)
        // new-style output-descriptor: {1: "wpkh(@0)", 2: [keys]}
        if (inner is Map<*, *> && inner[1L] is String) {
            var src = inner[1L] as String
            val keys = (inner[2L] as? List<*>) ?: emptyList<Any?>()
            keys.forEachIndexed { i, k -> src = src.replace("@$i", hdkeyExpr(untag(k) as Map<*, *>, accountFp)) }
            return DescriptorTools.candidate(name, src)
        }
        return DescriptorTools.candidate(name, script(inner as Cbor.Tagged, accountFp))
    }

    private fun script(t: Cbor.Tagged, fp: String?): String = when (tagOf(t)) {
        400L -> "sh(${script(t.value as Cbor.Tagged, fp)})"
        401L -> "wsh(${script(t.value as Cbor.Tagged, fp)})"
        403L -> "pkh(${keyFrom(t.value, fp)})"
        404L -> "wpkh(${keyFrom(t.value, fp)})"
        409L -> "tr(${keyFrom(t.value, fp)})"
        406L, 407L -> {
            val m = t.value as Map<*, *>; val k = m[1L] as Long; val keys = (m[2L] as List<*>).map { keyFrom(it, fp) }
            "${if (tagOf(t) == 407L) "sortedmulti" else "multi"}($k,${keys.joinToString(",")})"
        }
        else -> throw IllegalArgumentException("Unsupported script expression tag ${t.tag}")
    }
    private fun keyFrom(v: Any?, fp: String?): String {
        require(tagOf(v) == 303L) { "Only HD keys are supported" }
        return hdkeyExpr(untag(v) as Map<*, *>, fp) + "/<0;1>/*"
    }

    private data class HdKey(val xpub: String, val fp: String?, val path: String?)
    private fun hdkey(m: Map<*, *>, accountFp: String?): HdKey {
        if (m[2L] == true) throw SecretGuard.SecretMaterialException("UR contains a private HD key. This app is watch-only.")
        val key = m[3L] as ByteArray; val cc = m[4L] as? ByteArray ?: error("HD key without chain code")
        if (key.size == 32 || (key.size == 33 && key[0].toInt() == 0)) throw SecretGuard.SecretMaterialException("UR contains a private key. This app is watch-only.")
        val use = untag(m[5L]) as? Map<*, *>
        val mainnet = ((use?.get(2L) as? Long) ?: 0L) == 0L
        val origin = untag(m[6L]) as? Map<*, *>
        val comps = (origin?.get(1L) as? List<*>) ?: emptyList<Any?>()
        val path = StringBuilder(); var last = 0L; var count = 0
        var i = 0
        while (i + 1 < comps.size) {
            val idx = comps[i] as? Long ?: error("wildcard in origin"); val h = comps[i + 1] == true
            path.append('/').append(idx).append(if (h) "h" else ""); last = idx or (if (h) 0x80000000L else 0); count++; i += 2
        }
        val srcFp = (origin?.get(2L) as? Long)?.let { "%08x".format(it) } ?: accountFp
        val depth = (origin?.get(3L) as? Long)?.toInt() ?: count
        val parent = (m[8L] as? Long) ?: 0L
        val xpub = ExtKeys.build(mainnet, depth, UrUtil.u32be(parent), last, cc, key)
        return HdKey(xpub, srcFp, if (count > 0) path.toString() else null)
    }
    private fun hdkeyExpr(m: Map<*, *>, accountFp: String?): String {
        val k = hdkey(m, accountFp)
        return if (k.fp != null && k.path != null) "[${k.fp}${k.path}]${k.xpub}" else k.xpub
    }
    private fun hdkeyCandidate(v: Any?, name: String): WalletCandidate {
        val m = v as Map<*, *>; val k = hdkey(m, null)
        val purpose = k.path?.let { DescriptorTools.purposeOf(it) }
        require(purpose != 48 && purpose != 45) { "This is a multisig cosigner key. Import the multisig wallet descriptor instead." }
        val expr = if (k.fp != null && k.path != null) "[${k.fp}${k.path}]${k.xpub}" else k.xpub
        return DescriptorTools.candidate(name, DescriptorTools.singleSig(null, purpose ?: 84, expr))
    }
}
