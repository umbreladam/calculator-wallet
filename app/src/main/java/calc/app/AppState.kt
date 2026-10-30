package calc.app

import androidx.compose.runtime.*
import calc.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Home : Screen()
    data class Wallet(val id: String) : Screen()
    data class Tx(val id: String, val txid: String) : Screen()
    data class Receive(val id: String) : Screen()
    data class Send(val id: String) : Screen()
    data class Info(val id: String) : Screen()
    object Import : Screen()
    object Settings : Screen()
}

data class WalletView(
    val total: Long = 0, val confirmed: Long = 0, val pending: Long = 0,
    val txs: List<TxItem> = emptyList(), val tip: Int = 0,
    val syncing: Boolean = false, val error: String? = null, val synced: Boolean = false,
)

class AppState(val repo: Repo, val scope: CoroutineScope, val onLock: () -> Unit) {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen get() = stack.last()
    val wallets = mutableStateListOf<WalletMeta>().apply { addAll(repo.settings.wallets()) }
    val views = mutableStateMapOf<String, WalletView>()
    var hide by mutableStateOf(repo.settings.hideAmounts)
    var sats by mutableStateOf(repo.settings.sats)
    var price by mutableStateOf<Double?>(null)
    var toast by mutableStateOf<String?>(null)
    var configured by mutableStateOf(repo.settings.configured())
    var publicServer by mutableStateOf(repo.settings.isPublicActive())

    init { refreshPrice(); wallets.forEach { loadLocal(it) } }

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.size - 1); return true }; return false }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun meta(id: String) = wallets.firstOrNull { it.id == id }
    fun view(id: String) = views[id] ?: WalletView()

    fun toggleHide() { hide = !hide; repo.settings.hideAmounts = hide }

    fun refreshPrice() { scope.launch { price = repo.gbpPrice() } }

    private fun snapshot(e: WalletEngine, synced: Boolean): WalletView {
        val b = e.balance()
        return WalletView(b.total.toSat().toLong(), b.confirmed.toSat().toLong(),
            (b.trustedPending.toSat() + b.untrustedPending.toSat()).toLong(), e.transactions(), e.tipHeight(), false, null, synced)
    }

    private fun loadLocal(m: WalletMeta) {
        scope.launch {
            try { val v = withContext(Dispatchers.IO) { snapshot(repo.engine(m), false) }; views[m.id] = v.copy(syncing = views[m.id]?.syncing ?: false) }
            catch (e: Exception) { views[m.id] = view(m.id).copy(error = e.message) }
        }
    }

    fun sync(id: String) {
        val m = meta(id) ?: return
        if (view(id).syncing) return
        if (!configured) { views[id] = view(id).copy(error = "No Electrum server set — open Settings to choose your own or a public server."); return }
        views[id] = view(id).copy(syncing = true, error = null)
        scope.launch {
            try {
                val v = withContext(Dispatchers.IO) { val e = repo.engine(m); e.sync(repo.settings.server(), full = true); snapshot(e, true) }
                views[id] = v
            } catch (e: Exception) {
                views[id] = view(id).copy(syncing = false, error = "Sync failed: " + friendly(e))
            }
        }
    }
    fun syncAll() = wallets.forEach { sync(it.id) }

    fun addWallet(c: WalletCandidate, name: String, network: org.bitcoindevkit.Network): WalletMeta {
        val m = repo.add(c, name, network); wallets.add(m); loadLocal(m); return m
    }
    fun rename(id: String, name: String) { repo.rename(id, name); val i = wallets.indexOfFirst { it.id == id }; if (i >= 0) wallets[i] = wallets[i].copy(name = name) }
    fun delete(id: String) { repo.delete(id); wallets.removeAll { it.id == id }; views.remove(id); home() }

    companion object {
        fun friendly(e: Throwable): String {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                m.contains("Connection refused", true) -> "connection refused (is the Electrum server up / VPN connected?)"
                m.contains("timed out", true) || m.contains("timeout", true) -> "timed out reaching server"
                else -> m.take(200)
            }
        }
    }
}
