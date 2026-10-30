package calc.core

import org.bitcoindevkit.*

data class ServerConfig(val host: String, val port: Int, val ssl: Boolean, val socks5: String? = null, val validateTls: Boolean = false) {
    val url get() = (if (ssl) "ssl://" else "tcp://") + host + ":" + port
}

data class TxItem(val txid: String, val delta: Long, val fee: Long?, val feeRate: Double?, val height: Int?, val time: Long?, val sent: Long, val received: Long)
data class AddrItem(val index: Int, val address: String, val change: Boolean)
data class PsbtResult(val base64: String, val bytes: ByteArray, val fee: Long, val sendAmount: Long, val changeAmount: Long, val inputs: Int, val outputs: List<Pair<String, Long>>)
data class Recipient(val address: String, val sats: Long)
enum class CoinSelect { BRANCH_AND_BOUND, LARGEST_FIRST, OLDEST_FIRST }

fun networkOf(name: String): Network = Network.valueOf(name)

/** Thin watch-only wrapper around BDK. Never sees private keys. */
class WalletEngine(external: String, internal: String, val network: Network, dbPath: String?) : AutoCloseable {
    private val persister: Persister = if (dbPath == null) Persister.newInMemory() else Persister.newSqlite(dbPath)
    val wallet: Wallet

    init {
        SecretGuard.check(external); SecretGuard.check(internal)
        val kind = if (network == Network.BITCOIN) NetworkKind.MAIN else NetworkKind.TEST
        val ext = Descriptor(external, kind); val int = Descriptor(internal, kind)
        require(ext.toString() == ext.toStringWithSecret() && int.toString() == int.toStringWithSecret()) { "Private descriptors are not allowed" }
        wallet = try { Wallet.load(ext, int, persister) } catch (e: Exception) {
            if (external == internal) Wallet.createSingle(ext, network, persister) else Wallet(ext, int, network, persister)
        }
        wallet.persist(persister)
    }

    private fun client(s: ServerConfig) = ElectrumClient(s.url, s.socks5?.ifBlank { null }, 30u.toUByte(), 2u.toUByte(), s.validateTls)

    fun sync(server: ServerConfig, full: Boolean = true) {
        client(server).use { c ->
            val update = if (full) c.fullScan(wallet.startFullScan().build(), 25uL, 10uL, true)
                         else c.sync(wallet.startSyncWithRevealedSpks().build(), 10uL, true)
            wallet.applyUpdate(update)
            wallet.persist(persister)
        }
    }
    fun testServer(server: ServerConfig): String = client(server).use { c -> c.ping(); val h = c.blockHeadersSubscribe(); "OK · tip height ${h.height}" }
    fun feeEstimates(server: ServerConfig): Map<Int, Double> = client(server).use { c ->
        listOf(1, 3, 6, 25).associateWith { n -> (c.estimateFee(n.toULong()) * 100_000.0).coerceAtLeast(1.0) }
    }

    fun balance(): Balance = wallet.balance()
    fun tipHeight(): Int = wallet.latestCheckpoint().height.toInt()

    fun transactions(): List<TxItem> = wallet.transactions().map { ct ->
        val txid = ct.transaction.computeTxid()
        val d = wallet.txDetails(txid)
        val sr = wallet.sentAndReceived(ct.transaction)
        val (h, t) = when (val p = ct.chainPosition) {
            is ChainPosition.Confirmed -> p.confirmationBlockTime.blockId.height.toInt() to p.confirmationBlockTime.confirmationTime.toLong()
            is ChainPosition.Unconfirmed -> null to p.timestamp?.toLong()
        }
        TxItem(txid.toString(), d?.balanceDelta ?: (sr.received.toSat().toLong() - sr.sent.toSat().toLong()),
            d?.fee?.toSat()?.toLong(), d?.feeRate?.let { it.toSatPerKwu().toDouble() / 250.0 }, h, t,
            sr.sent.toSat().toLong(), sr.received.toSat().toLong())
    }.sortedWith(compareBy<TxItem> { it.height != null }.thenByDescending { it.height ?: Int.MAX_VALUE }.thenByDescending { it.time ?: 0 })

    fun txHex(txid: String): String? = wallet.getTx(Txid.fromString(txid))?.let { UrUtil.hex(it.transaction.serialize()) }

    fun nextReceive(): AddrItem { val a = wallet.nextUnusedAddress(KeychainKind.EXTERNAL); wallet.persist(persister); return AddrItem(a.index.toInt(), a.address.toString(), false) }
    fun peek(change: Boolean, index: Int): AddrItem {
        val a = wallet.peekAddress(if (change) KeychainKind.INTERNAL else KeychainKind.EXTERNAL, index.toUInt())
        return AddrItem(index, a.address.toString(), change)
    }
    fun publicDescriptor(change: Boolean) = wallet.publicDescriptor(if (change) KeychainKind.INTERNAL else KeychainKind.EXTERNAL)

    fun validateAddress(addr: String): Address = Address(addr.trim(), network)

    fun createPsbt(recipients: List<Recipient>, satPerVb: Double, rbf: Boolean = true, coinSelect: CoinSelect = CoinSelect.BRANCH_AND_BOUND, sendMax: Boolean = false): PsbtResult {
        require(recipients.isNotEmpty()) { "No recipient" }
        require(satPerVb >= 1.0 && satPerVb <= 2000.0) { "Fee rate out of range" }
        var b = TxBuilder()
            .feeRate(FeeRate.fromSatPerKwu(Math.round(satPerVb * 250.0).toULong()))
            .coinSelection(CoinSelectionAlgorithm.valueOf(coinSelect.name))
        if (!rbf) b = b.setExactSequence(0xFFFFFFFEu)
        if (sendMax) {
            require(recipients.size == 1) { "Send max supports one recipient" }
            b = b.drainWallet().drainTo(validateAddress(recipients[0].address).scriptPubkey())
        } else for (r in recipients) {
            require(r.sats > 0) { "Amount must be > 0" }
            b = b.addRecipient(validateAddress(r.address).scriptPubkey(), Amount.fromSat(r.sats.toULong()))
        }
        if (wallet.publicDescriptor(KeychainKind.EXTERNAL).contains("multi")) b = b.addGlobalXpubs()
        val psbt = b.finish(wallet)
        // BDK reveals the change address during finish(); persist so it is not reused
        wallet.persist(persister)
        return describe(psbt)
    }

    fun describe(psbt: Psbt): PsbtResult {
        val tx = psbt.extractTxUncheckedFeeRate()
        val outs = tx.output().map { o -> (try { Address.fromScript(o.scriptPubkey, network).toString() } catch (e: Exception) { "script:" + UrUtil.hex(o.scriptPubkey.toBytes()) }) to o.value.toSat().toLong() }
        var change = 0L; var send = 0L
        for (o in tx.output()) { val mine = wallet.derivationOfSpk(o.scriptPubkey)?.keychain == KeychainKind.INTERNAL || wallet.isMine(o.scriptPubkey); if (mine) change += o.value.toSat().toLong() else send += o.value.toSat().toLong() }
        val b64 = psbt.serialize()
        return PsbtResult(b64, java.util.Base64.getDecoder().decode(b64), psbt.fee().toLong(), send, change, tx.input().size, outs)
    }

    /** Finalize a signed PSBT and extract the network transaction. */
    fun finalizeSigned(psbtBase64: String): Transaction {
        val p = Psbt(psbtBase64)
        val f = p.finalize()
        if (!f.couldFinalize) throw IllegalStateException("PSBT is not fully signed: " + (f.errors?.joinToString { it.message ?: it.toString() } ?: "missing signatures"))
        return f.psbt.extractTx()
    }
    fun broadcast(server: ServerConfig, tx: Transaction): String = client(server).use { it.transactionBroadcast(tx).toString() }

    override fun close() { wallet.close(); persister.close() }
}
