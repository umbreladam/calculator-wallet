package calc.core

object DescriptorChecksum {
    private const val INPUT = "0123456789()[],'/*abcdefgh@:\$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
    private const val CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private fun polymod(c0: Long, v: Int): Long {
        var c = c0; val c1 = c ushr 35
        c = ((c and 0x7ffffffffL) shl 5) xor v.toLong()
        if (c1 and 1 != 0L) c = c xor 0xf5dee51989L
        if (c1 and 2 != 0L) c = c xor 0xa9fdca3312L
        if (c1 and 4 != 0L) c = c xor 0x1bab10e32dL
        if (c1 and 8 != 0L) c = c xor 0x3706b1677aL
        if (c1 and 16 != 0L) c = c xor 0x644d626ffdL
        return c
    }
    fun compute(desc: String): String {
        var c = 1L; var cls = 0; var clscount = 0
        for (ch in desc) {
            val pos = INPUT.indexOf(ch); require(pos >= 0) { "invalid char in descriptor" }
            c = polymod(c, pos and 31); cls = cls * 3 + (pos shr 5)
            if (++clscount == 3) { c = polymod(c, cls); cls = 0; clscount = 0 }
        }
        if (clscount > 0) c = polymod(c, cls)
        repeat(8) { c = polymod(c, 0) }
        c = c xor 1
        return String(CharArray(8) { CHARS[((c ushr (5 * (7 - it))) and 31).toInt()] })
    }
    fun strip(d: String) = d.substringBefore('#').trim()
    fun withChecksum(d: String) = strip(d).let { "$it#${compute(it)}" }
    fun verify(d: String): Boolean { if (!d.contains('#')) return true; val (b, c) = d.trim().split('#', limit = 2); return compute(b) == c }
}

data class WalletCandidate(
    val label: String,
    val external: String,
    val internal: String,
    val mainnet: Boolean,
    val kind: String,
    val warnings: List<String> = emptyList(),
)

object DescriptorTools {
    private val TOP = Regex("(?<![a-z_])(wpkh|wsh|sh|tr|pkh)\\(")
    private val KEY = Regex("(\\[[0-9a-fA-F]{8}(?:/[0-9]+['hH]?)*\\])?([xt]pub[1-9A-HJ-NP-Za-km-z]{100,112})((?:/[0-9]+['hH]?)*)((?:/<[0-9]+;[0-9]+>)?)((?:/\\*['hH]?)?)")

    /** Pull all top-level descriptors out of arbitrary text (Sparrow .txt, BSMS, JSON, Core listdescriptors, Specter...). */
    fun extract(text: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        val t = ExtKeys.normalizeAll(text)
        while (true) {
            val m = TOP.find(t, i) ?: break
            var depth = 0; var j = m.range.last; var end = -1
            while (j < t.length) { when (t[j]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { end = j; break } } }; j++ }
            if (end < 0) break
            var d = t.substring(m.range.first, end + 1)
            if (end + 9 < t.length + 0 && t.getOrNull(end + 1) == '#') d += t.substring(end + 1, minOf(t.length, end + 10))
            if (KEY.containsMatchIn(d)) out.add(d.replace(Regex("\\s"), ""))
            i = end + 1
        }
        return out.distinct()
    }

    fun kindOf(d: String): String {
        val s = DescriptorChecksum.strip(d)
        Regex("(sorted)?multi(_a)?\\((\\d+),").find(s)?.let { m ->
            val n = KEY.findAll(s).count(); val k = m.groupValues[3]
            val wrap = when { s.startsWith("sh(wsh(") -> "Nested SegWit"; s.startsWith("wsh(") -> "Native SegWit"; s.startsWith("sh(") -> "Legacy"; s.startsWith("tr(") -> "Taproot"; else -> "" }
            return "$k-of-$n multisig · $wrap"
        }
        return when {
            s.startsWith("wpkh(") -> "Single-sig · Native SegWit"
            s.startsWith("sh(wpkh(") -> "Single-sig · Nested SegWit"
            s.startsWith("tr(") -> "Single-sig · Taproot"
            s.startsWith("pkh(") -> "Single-sig · Legacy"
            else -> "Custom descriptor"
        }
    }

    // Turn one (possibly multipath / BSMS double-star / no-wildcard) descriptor into receive + change descriptors.
    fun split(desc: String, internalHint: String? = null): Pair<String, String> {
        require(DescriptorChecksum.verify(desc)) { "Descriptor checksum mismatch" }
        var d = DescriptorChecksum.strip(ExtKeys.normalizeAll(desc)).replace(Regex("\\s"), "").replace("/**", "/<0;1>/*").replace("'", "h")
        if (internalHint != null) {
            require(DescriptorChecksum.verify(internalHint)) { "Change descriptor checksum mismatch" }
            return DescriptorChecksum.withChecksum(d) to DescriptorChecksum.withChecksum(ExtKeys.normalizeAll(DescriptorChecksum.strip(internalHint)).replace("'", "h"))
        }
        // add wildcard derivation to bare keys
        d = KEY.replace(d) { m ->
            val (origin, key, path, multi, wild) = m.destructured
            if (multi.isEmpty() && wild.isEmpty()) "$origin$key$path/<0;1>/*" else m.value
        }
        val mp = Regex("<(\\d+);(\\d+)>")
        val ext: String; val int: String
        if (mp.containsMatchIn(d)) {
            ext = mp.replace(d) { it.groupValues[1] }; int = mp.replace(d) { it.groupValues[2] }
        } else if (d.contains("/0/*")) {
            ext = d; int = d.replace("/0/*", "/1/*")
        } else { ext = d; int = d }
        return DescriptorChecksum.withChecksum(ext) to DescriptorChecksum.withChecksum(int)
    }

    fun candidate(label: String, desc: String, internal: String? = null): WalletCandidate {
        val (e, i) = split(desc, internal)
        val keys = KEY.findAll(e).toList()
        require(keys.isNotEmpty()) { "No extended public key in descriptor" }
        val mainnet = keys.first().groupValues[2].startsWith("xpub")
        require(keys.all { it.groupValues[2].startsWith(if (mainnet) "xpub" else "tpub") }) { "Mixed mainnet/testnet keys" }
        val warns = ArrayList<String>()
        if (keys.any { it.groupValues[1].isEmpty() }) warns += "Some keys have no origin (fingerprint/path). Hardware signers may refuse to sign PSBTs."
        if (e == i) warns += "Descriptor has no separate change path; change goes to the receive chain."
        return WalletCandidate(label, e, i, mainnet, kindOf(e), warns)
    }

    fun originPath(deriv: String): String = deriv.trim().removePrefix("m").removePrefix("M").replace("'", "h").replace("H", "h")
    fun keyExpr(fp: String?, deriv: String?, xpub: String): String {
        val k = ExtKeys.normalize(xpub)
        return if (fp != null && deriv != null) "[${fp.lowercase()}${originPath(deriv)}]$k" else k
    }
    fun singleSig(script: ExtKeys.Hint?, purpose: Int?, keyExpr: String): String {
        val body = "$keyExpr/<0;1>/*"
        return when {
            purpose == 86 -> "tr($body)"
            purpose == 84 || script == ExtKeys.Hint.P2WPKH -> "wpkh($body)"
            purpose == 49 || script == ExtKeys.Hint.P2SH_P2WPKH -> "sh(wpkh($body))"
            purpose == 44 -> "pkh($body)"
            else -> "pkh($body)"
        }
    }
    fun purposeOf(deriv: String?): Int? = deriv?.let { Regex("^m?/?(\\d+)").find(originPath(it).trimStart('/'))?.groupValues?.get(1)?.toIntOrNull() }
}
