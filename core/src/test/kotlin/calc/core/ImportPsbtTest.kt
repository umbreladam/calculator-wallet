package calc.core

import org.bitcoindevkit.*
import org.junit.Assert.*
import org.junit.Test

class ImportPsbtTest {
    // Public BIP84 test vector (mnemonic "abandon ... about"), from the BIP itself. Not a real wallet.
    private val BIP84_ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    /** Freshly generated throw-away testnet account key: returns "[fp/path]tpub" and the matching secret descriptor key. */
    private fun genKey(path: String): Pair<String, String> {
        val sk = DescriptorSecretKey(NetworkKind.TEST, Mnemonic(WordCount.WORDS12), null).derive(DerivationPath(path))
        return sk.asPublic().toString() to sk.toString()
    }

    @Test fun bip84VectorZpub() {
        val c = ImportParser.parse(BIP84_ZPUB).single()
        assertTrue(c.mainnet); assertEquals("Single-sig · Native SegWit", c.kind)
        WalletEngine(c.external, c.internal, Network.BITCOIN, null).use { e ->
            assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", e.peek(false, 0).address)
            assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", e.peek(true, 0).address)
            // our checksum implementation must agree with BDK/Core
            val pub = e.publicDescriptor(false)
            assertEquals(pub.substringAfter('#'), DescriptorChecksum.compute(pub.substringBefore('#')))
        }
    }

    @Test fun bip84WithOriginViaColdcardJson() {
        val json = """{"chain":"BTC","xfp":"73C5DA0A","account":0,"bip84":{"name":"p2wpkh","xfp":"73C5DA0A","deriv":"m/84'/0'/0'","xpub":"$BIP84_ZPUB","first":"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"}}"""
        val c = ImportParser.parse(json).single()
        assertTrue(c.external.startsWith("wpkh([73c5da0a/84h/0h/0h]xpub")); assertTrue(c.warnings.isEmpty())
        WalletEngine(c.external, c.internal, Network.BITCOIN, null).use { assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", it.peek(false, 0).address) }
    }

    @Test fun descriptorFormats() {
        val (k, _) = genKey("m/84h/1h/0h")
        val kk = k.replace("'", "h")
        // multipath (Sparrow BIP389 export with comments)
        val txt = "# Receive and change descriptor (BIP389):\nwpkh($kk/<0;1>/*)\n"
        val a = ImportParser.parse(txt).single()
        assertTrue(a.external.contains("/0/*") && a.internal.contains("/1/*")); assertFalse(a.mainnet)
        // separate receive/change (Core style JSON)
        val core = """{"descriptors":[{"desc":"${DescriptorChecksum.withChecksum("wpkh($kk/0/*)")}","internal":false},{"desc":"${DescriptorChecksum.withChecksum("wpkh($kk/1/*)")}","internal":true}]}"""
        val b = ImportParser.parse(core).single()
        assertEquals(a.external, b.external); assertEquals(a.internal, b.internal)
        // bad checksum rejected
        assertThrows(IllegalArgumentException::class.java) { ImportParser.parse("wpkh($kk/0/*)#qqqqqqqq") }
        // taproot / nested
        assertEquals("Single-sig · Taproot", ImportParser.parse("tr(${genKey("m/86h/1h/0h").first}/<0;1>/*)").single().kind)
        assertEquals("Single-sig · Nested SegWit", ImportParser.parse("sh(wpkh(${genKey("m/49h/1h/0h").first}/<0;1>/*))").single().kind)
        // all parse with BDK and derive addresses
        for (c in listOf(a, b)) WalletEngine(c.external, c.internal, Network.TESTNET, null).use { assertTrue(it.peek(false, 0).address.startsWith("tb1q")) }
    }

    @Test fun multisigBip48AndFormats() {
        val keys = (0 until 3).map { genKey("m/48h/1h/0h/2h").first }
        val desc = "wsh(sortedmulti(2,${keys.joinToString(",") { "$it/<0;1>/*" }}))"
        val c = ImportParser.parse(desc).single()
        assertEquals("2-of-3 multisig · Native SegWit", c.kind)
        // BSMS 1.0
        val bsms = "BSMS 1.0\n${desc.replace("/<0;1>/*", "/**")}\n/0/*,/1/*\ntb1qxyz\n"
        assertEquals(c.external, ImportParser.parse(bsms).single().external)
        // Coldcard multisig text
        val cc = StringBuilder("# Coldcard Multisig setup file\nName: Vault\nPolicy: 2 of 3\nDerivation: m/48'/1'/0'/2'\nFormat: P2WSH\n\n")
        keys.forEach { val fp = it.substring(1, 9); val x = it.substringAfter(']'); cc.append("${fp.uppercase()}: $x\n") }
        val c2 = ImportParser.parse(cc.toString()).single()
        assertEquals("Vault", c2.label); assertEquals(c.external, c2.external)
        // Sparrow wallet JSON
        val ks = keys.joinToString(",") { val fp = it.substring(1, 9); val x = it.substringAfter(']'); """{"keyDerivation":{"masterFingerprint":"$fp","derivation":"m/48'/1'/0'/2'"},"extendedPublicKey":"$x"}""" }
        val sp = """{"name":"Vault","policyType":"MULTI","scriptType":"P2WSH","defaultPolicy":{"name":"Default","miniscript":"sortedmulti(2,A,B,C)"},"keystores":[$ks]}"""
        assertEquals(c.external, ImportParser.parse(sp).single().external)
        WalletEngine(c.external, c.internal, Network.TESTNET, null).use { assertTrue(it.peek(false, 0).address.length == 62) }
    }

    @Test fun urCryptoAccountAndOutput() {
        val (k, _) = genKey("m/84h/1h/0h")
        val fp = k.substring(1, 9); val x = Base58.checkDecode(k.substringAfter(']'))
        fun hdkey(w: Cbor.Writer) { w.tag(303).map(5)
            w.uint(3).bytes(x.copyOfRange(45, 78)); w.uint(4).bytes(x.copyOfRange(13, 45))
            w.uint(5).tag(305).map(1).uint(2).uint(1)
            w.uint(6).tag(304).map(3).uint(1).array(6).uint(84).bool(true).uint(1).bool(true).uint(0).bool(true).uint(2).uint(fp.toLong(16)).uint(3).uint(3)
            w.uint(8).uint(UrUtil.unhex(UrUtil.hex(x.copyOfRange(5, 9))).let { (it[0].toLong() and 0xff shl 24) or (it[1].toLong() and 0xff shl 16) or (it[2].toLong() and 0xff shl 8) or (it[3].toLong() and 0xff) }) }
        val w = Cbor.Writer(); w.map(2).uint(1).uint(fp.toLong(16)).uint(2).array(1).tag(308).tag(404); hdkey(w)
        val ur = UrEncoder.encodeSingle(UR("crypto-account", w.toByteArray()))
        val c = ImportParser.parse(ur).single()
        assertEquals("wpkh(${k.replace("'", "h")}/0/*)", DescriptorChecksum.strip(c.external))
        val w2 = Cbor.Writer(); w2.tag(308).tag(404); hdkey(w2)
        assertEquals(c.external, ImportParser.parse(UrEncoder.encodeSingle(UR("crypto-output", w2.toByteArray()))).single().external)
    }

    @Test fun rejectsSecrets() {
        val (_, sec) = genKey("m/84h/1h/0h")
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse("wpkh($sec/0/*)") }
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about") }
        // randomly generated WIF
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse("key: " + Base58.checkEncode(byteArrayOf(0x80.toByte()) + Xoshiro256("wif").nextData(32) + byteArrayOf(1))) }
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse(UrEncoder.encodeSingle(UR("crypto-seed", Cbor.Writer().map(1).uint(1).bytes(ByteArray(16)).toByteArray()))) }
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse("""{"mnemonic":"x y z"}""") }
        assertThrows(SecretGuard.SecretMaterialException::class.java) { ImportParser.parse("011513251154012711900771041507421289190620080870026613431420201617920614089619290300152408010643") }
    }

    @Test fun serverCheck() {
        assertTrue(ServerCheck.isPrivate("100.64.1.2")); assertTrue(ServerCheck.isPrivate("192.168.1.5")); assertTrue(ServerCheck.isPrivate("umbrel.local"))
        assertTrue(ServerCheck.isPrivate("abc.onion")); assertFalse(ServerCheck.isPrivate("electrum.blockstream.info")); assertFalse(ServerCheck.isPrivate("8.8.8.8"))
    }

    // ---------- PSBT ----------
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xff).toByte() }
    private fun fundingTx(spk: ByteArray, sats: Long, salt: Int): Transaction {
        val prev = UrUtil.sha256(byteArrayOf(salt.toByte()))
        val raw = le(2, 4) + byteArrayOf(1) + prev + le(0, 4) + byteArrayOf(0) + le(0xffffffffL, 4) +
            byteArrayOf(1) + le(sats, 8) + byteArrayOf(spk.size.toByte()) + spk + le(0, 4)
        return Transaction(raw)
    }
    private fun fund(e: WalletEngine, sats: Long, salt: Int) {
        val a = e.nextReceive(); val spk = e.validateAddress(a.address).scriptPubkey().toBytes()
        e.wallet.applyUnconfirmedTxs(listOf(UnconfirmedTx(fundingTx(spk, sats, salt), 1_700_000_000uL)))
    }

    @Test fun psbtSingleSigCreateSignFinalize() {
        val (pub, sec) = genKey("m/84h/1h/0h")
        val c = ImportParser.parse("wpkh($pub/<0;1>/*)").single()
        WalletEngine(c.external, c.internal, Network.TESTNET, null).use { e ->
            fund(e, 100_000, 1); fund(e, 50_000, 2)
            assertEquals(150_000uL, e.balance().total.toSat())
            val dest = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
            val r = e.createPsbt(listOf(Recipient(dest, 30_000)), 5.0, rbf = true)
            assertTrue(SignedPayload.isPsbt(r.bytes)); assertEquals(30_000, r.sendAmount)
            assertTrue(r.fee in 500..2000); assertTrue(r.changeAmount > 0)
            val psbt = Psbt(r.base64)
            val inp = psbt.input()
            assertTrue(inp.all { it.bip32Derivation.isNotEmpty() && it.witnessUtxo != null })
            // change goes to our internal chain
            val tx = psbt.extractTxUncheckedFeeRate()
            assertTrue(tx.output().any { e.wallet.derivationOfSpk(it.scriptPubkey)?.keychain == KeychainKind.INTERNAL })
            assertTrue(tx.input().all { it.sequence == 0xFFFFFFFDu }) // RBF signalled
            // air-gap round trip: BBQr + UR
            val j = Bbqr.Joiner(); PsbtExport.bbqrParts(r.bytes).forEach { j.add(it) }; assertArrayEquals(r.bytes, j.result())
            val enc = PsbtExport.urParts(r.bytes); val d = UrDecoder(); while (!d.isComplete) d.receive(enc.nextPart())
            assertArrayEquals(r.bytes, (SignedPayload.fromUr(d.result!!) as SignedPayload.Psbt).bytes)
            // unsigned cannot be finalized
            assertThrows(IllegalStateException::class.java) { e.finalizeSigned(r.base64) }
            // simulate the air-gapped signer with a throwaway private wallet (test only)
            val kind = NetworkKind.TEST
            val signer = Wallet(Descriptor("wpkh($sec/0/*)", kind), Descriptor("wpkh($sec/1/*)", kind), Network.TESTNET, Persister.newInMemory())
            val p2 = Psbt(r.base64); signer.sign(p2, SignOptions(false, null, false, false, true, true))
            val signed = e.finalizeSigned(p2.serialize())
            assertEquals(tx.computeTxid().toString(), signed.computeTxid().toString())
            assertTrue(signed.input().all { it.witness.isNotEmpty() })
        }
    }

    @Test fun psbtNoRbfAndSendMaxAndMultisig() {
        val keys = (0 until 3).map { genKey("m/48h/1h/0h/2h").first }
        val c = ImportParser.parse("wsh(sortedmulti(2,${keys.joinToString(",") { "$it/<0;1>/*" }}))").single()
        WalletEngine(c.external, c.internal, Network.TESTNET, null).use { e ->
            fund(e, 200_000, 3)
            val dest = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
            val r = e.createPsbt(listOf(Recipient(dest, 10_000)), 2.5, rbf = false, coinSelect = CoinSelect.LARGEST_FIRST)
            val p = Psbt(r.base64)
            assertTrue(p.extractTxUncheckedFeeRate().input().all { it.sequence == 0xFFFFFFFEu })
            assertTrue(p.input().all { it.witnessScript != null && it.bip32Derivation.size == 3 })
            assertTrue(p.jsonSerialize().contains("xpub") || p.jsonSerialize().contains("tpub"))
            val m = e.createPsbt(listOf(Recipient(dest, 0)), 3.0, sendMax = true)
            assertEquals(0L, m.changeAmount); assertEquals(200_000L, m.sendAmount + m.fee)
        }
    }
}
