package calc.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calc.app.*
import calc.core.TxItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WalletApp(s: AppState) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.toast) { s.toast?.let { snack.showSnackbar(it); s.toast = null } }
    Scaffold(containerColor = Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (s.publicServer && s.screen !is Screen.Settings) Row(Modifier.fillMaxWidth().background(Red.copy(alpha = 0.15f)).clickable { s.go(Screen.Settings) }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Public, null, tint = Red, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text("Public server in use — reduced privacy", color = Red, fontSize = 12.sp)
            }
        Box(Modifier.weight(1f).fillMaxSize()) {
            AnimatedContent(s.screen, transitionSpec = {
                val fwd = s.lastNavForward
                (slideInHorizontally(tween(260)) { w -> if (fwd) w / 4 else -w / 4 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(220)) { w -> if (fwd) -w / 4 else w / 4 } + fadeOut(tween(180)))
            }, label = "nav") { sc -> when (sc) {
                Screen.Home -> MarketHome(s)
                Screen.Wallets -> WalletsScreen(s)
                is Screen.History -> HistoryScreen(s, sc.id)
                is Screen.Wallet -> WalletDetail(s, sc.id)
                is Screen.Tx -> TxDetail(s, sc.id, sc.txid)
                is Screen.Receive -> ReceiveScreen(s, sc.id)
                is Screen.Send -> SendFlow(s, sc.id)
                is Screen.Info -> WalletInfo(s, sc.id)
                Screen.Import -> ImportScreen(s)
                Screen.Settings -> SettingsScreen(s)
            } }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) =
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Bg))

@Composable fun HideButton(s: AppState) = IconButton(onClick = { s.toggleHide() }) { Icon(if (s.hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Hide amounts") }

@Composable fun WalletsScreen(s: AppState) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Wallets", { s.back() }) {
            HideButton(s)
            IconButton(onClick = { s.syncAll() }) { Icon(Icons.Outlined.Sync, "Sync") }
            IconButton(onClick = { s.go(Screen.Settings) }) { Icon(Icons.Outlined.Settings, "Settings") }
            IconButton(onClick = { s.onLock() }) { Icon(Icons.Outlined.Lock, "Lock") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val total = s.wallets.sumOf { s.view(it.id).total }
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total balance", color = Muted, fontSize = 14.sp)
                    Text(Fmt.btc(total, s.hide, s.sats), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.clickable { s.toggleHide() })
                    Fmt.gbp(total, s.price, s.hide)?.let { Text(it, color = Muted, fontSize = 16.sp) }
                    Spacer(Modifier.height(6.dp)); Pill("Watch-only · keys stay on your signer", Muted)
                }
            }
            items(s.wallets, key = { it.id }) { w ->
                val v = s.view(w.id)
                Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).clickable { s.go(Screen.Wallet(w.id)); if (!v.synced) s.sync(w.id) }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Orange.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (w.kind.contains("multisig")) Icons.Outlined.Groups else Icons.Outlined.AccountBalanceWallet, null, tint = Orange)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.kind + if (w.network != "BITCOIN") " · ${w.network.lowercase()}" else "", color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (v.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Orange)
                        Text(Fmt.btc(v.total, s.hide, s.sats), fontFamily = Mono, fontSize = 14.sp)
                        Fmt.gbp(v.total, s.price, s.hide)?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    }
                }
            }
            if (!s.configured) item {
                Card {
                    Text("Set up a server", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("No Electrum server is configured. Enter your own node (recommended) or pick a public server.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp)); Button(onClick = { s.go(Screen.Settings) }) { Text("Choose server") }
                }
            }
            item {
                OutlinedButton(onClick = { s.go(Screen.Import) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add watch-only wallet")
                }
                if (s.wallets.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    WarnBox("Import an xpub / zpub or output descriptor from Sparrow, Coldcard, SeedSigner, Jade or Keystone. Private keys and seeds are always rejected.", Muted)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable fun WalletDetail(s: AppState, id: String) {
    val w = s.meta(id) ?: return
    val v = s.view(id)
    Column(Modifier.fillMaxSize()) {
        TopBar(w.name, { s.back() }) {
            HideButton(s)
            IconButton(onClick = { s.sync(id) }) { if (v.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange) else Icon(Icons.Outlined.Sync, "Sync") }
            IconButton(onClick = { s.go(Screen.Info(id)) }) { Icon(Icons.Outlined.Settings, "Wallet settings") }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f).background(Surface1, RoundedCornerShape(28.dp)).clickable { s.go(Screen.History(id)) }.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Pill(w.kind + if (w.network != "BITCOIN") " · ${w.network.lowercase()}" else "", Muted)
                Spacer(Modifier.height(14.dp))
                Text(Fmt.btc(v.total, s.hide, s.sats), fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, maxLines = 1)
                Fmt.gbp(v.total, s.price, s.hide)?.let { Text(it, color = Muted, fontSize = 16.sp) }
                if (v.pending != 0L) Text("Pending: " + Fmt.btc(v.pending, s.hide, s.sats), color = Orange, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, null, tint = Orange, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Transaction history (${v.txs.size})", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                v.error?.let { Spacer(Modifier.height(10.dp)); WarnBox(it, Red) }
                if (!v.synced && !v.syncing && v.error == null) { Spacer(Modifier.height(8.dp)); Text("Showing cached data · tap sync", color = Muted, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { s.go(Screen.Send(id)) }, Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(24.dp)) {
                Icon(Icons.Filled.NorthEast, null, Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)); Text("Send", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { s.go(Screen.Receive(id)) }, Modifier.fillMaxWidth().height(84.dp), shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Orange)) {
                Icon(Icons.Filled.SouthWest, null, Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)); Text("Receive", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable fun HistoryScreen(s: AppState, id: String) {
    val w = s.meta(id) ?: return
    val v = s.view(id)
    Column(Modifier.fillMaxSize()) {
        TopBar("Transactions · ${w.name}", { s.back() }) {
            HideButton(s)
            IconButton(onClick = { s.sync(id) }) { if (v.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange) else Icon(Icons.Outlined.Sync, "Sync") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            v.error?.let { item { WarnBox(it, Red) } }
            if (v.txs.isEmpty()) item { Text(if (v.syncing) "Syncing…" else "No transactions yet", color = Muted, modifier = Modifier.padding(16.dp)) }
            items(v.txs, key = { it.txid }) { t -> TxRow(s, t, v.tip) { s.go(Screen.Tx(id, t.txid)) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable fun TxRow(s: AppState, t: TxItem, tip: Int, onClick: () -> Unit) {
    val incoming = t.delta >= 0
    Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background((if (incoming) Green else Orange).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (incoming) Icons.Filled.SouthWest else Icons.Filled.NorthEast, null, tint = if (incoming) Green else Orange, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (incoming) "Received" else "Sent", fontWeight = FontWeight.Medium)
            Text(if (t.height == null) "Unconfirmed" else Fmt.date(t.time), color = if (t.height == null) Orange else Muted, fontSize = 12.sp)
        }
        Text((if (incoming && !s.hide) "+" else "") + Fmt.btc(t.delta, s.hide, s.sats), fontFamily = Mono, fontSize = 14.sp, color = if (incoming) Green else Color.White)
    }
}

@Composable fun KV(k: String, v: String, mono: Boolean = false, copy: Boolean = false) {
    val cb = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).let { if (copy) it.clickable { cb.setText(AnnotatedString(v)) } else it }) {
        Text(k, color = Muted, fontSize = 12.sp)
        Text(v, fontFamily = if (mono) Mono else null, fontSize = 15.sp)
    }
}

@Composable fun TxDetail(s: AppState, id: String, txid: String) {
    val v = s.view(id); val t = v.txs.firstOrNull { it.txid == txid } ?: return
    val w = s.meta(id) ?: return
    var outs by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    LaunchedEffect(txid) {
        outs = withContext(Dispatchers.IO) { try {
            val e = s.repo.engine(w); val hex = e.txHex(txid) ?: return@withContext emptyList()
            org.bitcoindevkit.Transaction(calc.core.UrUtil.unhex(hex)).output().map { o ->
                (try { org.bitcoindevkit.Address.fromScript(o.scriptPubkey, s.repo.network(w)).toString() } catch (x: Exception) { "(script)" }) +
                    (if (e.wallet.isMine(o.scriptPubkey)) "  · yours" else "") to o.value.toSat().toLong()
            } } catch (x: Exception) { emptyList() } }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (t.delta >= 0) "Received" else "Sent", { s.back() }) { HideButton(s) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card {
                Text(Fmt.btc(t.delta, s.hide, s.sats), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = if (t.delta >= 0) Green else Color.White)
                Fmt.gbp(Math.abs(t.delta), s.price, s.hide)?.let { Text(it, color = Muted) }
                Spacer(Modifier.height(8.dp))
                val conf = t.height?.let { v.tip - it + 1 } ?: 0
                Pill(if (conf > 0) "$conf confirmation${if (conf == 1) "" else "s"}" else "Unconfirmed", if (conf > 0) Green else Orange)
            }
            Spacer(Modifier.height(12.dp))
            Card {
                KV("Transaction ID (tap to copy)", txid, mono = true, copy = true)
                KV("Date", Fmt.date(t.time))
                t.height?.let { KV("Block", "%,d".format(it)) }
                t.fee?.let { KV("Fee", Fmt.btc(it, s.hide, true) + (t.feeRate?.let { r -> " · %.1f sat/vB".format(r) } ?: "")) }
                KV("Sent from wallet", Fmt.btc(t.sent, s.hide, s.sats)); KV("Received by wallet", Fmt.btc(t.received, s.hide, s.sats))
            }
            if (outs.isNotEmpty()) {
                SectionLabel("Outputs")
                Card { outs.forEach { (a, amt) -> KV(Fmt.btc(amt, s.hide, s.sats), a, mono = true, copy = true) } }
            }
        }
    }
}

@Composable fun ReceiveScreen(s: AppState, id: String) {
    val w = s.meta(id) ?: return
    var addr by remember { mutableStateOf<calc.core.AddrItem?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val cb = LocalClipboardManager.current
    LaunchedEffect(bump) {
        try { addr = withContext(Dispatchers.IO) { val e = s.repo.engine(w); if (bump == 0) e.nextReceive() else { val n = e.wallet.revealNextAddress(org.bitcoindevkit.KeychainKind.EXTERNAL); calc.core.AddrItem(n.index.toInt(), n.address.toString(), false) } } }
        catch (e: Exception) { err = e.message }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Receive", { s.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            err?.let { WarnBox(it, Red) }
            addr?.let { a ->
                QrImage("bitcoin:" + a.address.uppercase().let { if (a.address.startsWith("bc1") || a.address.startsWith("tb1") || a.address.startsWith("bcrt1")) it else a.address }, Modifier.fillMaxWidth(0.85f))
                Spacer(Modifier.height(16.dp))
                Text(Fmt.groupAddr(a.address), fontFamily = Mono, fontSize = 17.sp, modifier = Modifier.clickable { cb.setText(AnnotatedString(a.address)); s.toast = "Address copied" })
                Spacer(Modifier.height(8.dp)); Pill("Receive address #${a.index}  ·  path …/0/${a.index}", Muted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { cb.setText(AnnotatedString(a.address)); s.toast = "Address copied" }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                    OutlinedButton(onClick = { bump++ }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("New address") }
                }
                Spacer(Modifier.height(16.dp))
                WarnBox("Verify on your hardware wallet before receiving significant amounts: on Coldcard use Address Explorer, on SeedSigner/Jade/Keystone use “Verify address” and compare all characters. This phone only knows public keys — a compromised phone could show a fake address.")
            }
        }
    }
}

@Composable fun WalletInfo(s: AppState, id: String) {
    val w = s.meta(id) ?: return
    var name by remember { mutableStateOf(w.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Wallet settings", { s.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { s.rename(id, name.trim().ifEmpty { w.name }); s.toast = "Renamed" }, enabled = name.isNotBlank() && name != w.name) { Text("Rename") }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                KV("Type", w.kind); KV("Network", w.network.lowercase())
                Regex("\\[([0-9a-fA-F]{8})").findAll(w.external).map { it.groupValues[1].lowercase() }.distinct().toList().takeIf { it.isNotEmpty() }?.let { KV("Master fingerprint" + if (it.size > 1) "s" else "", it.joinToString(", "), mono = true, copy = true) }
                KV("Receive descriptor (tap to copy)", w.external, mono = true, copy = true)
                KV("Change descriptor (tap to copy)", w.internal, mono = true, copy = true)
                TextButton(onClick = { showQr = !showQr }) { Text(if (showQr) "Hide QR" else "Show descriptor QR") }
                if (showQr) QrImage(w.external, Modifier.fillMaxWidth(), com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)
            }
            w.warnings.forEach { Spacer(Modifier.height(8.dp)); WarnBox(it) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Red), modifier = Modifier.fillMaxWidth()) { Text("Remove wallet from this phone") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        confirmButton = { TextButton(onClick = { confirmDelete = false; s.delete(id) }) { Text("Remove", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        title = { Text("Remove wallet?") }, text = { Text("Only the watch-only data on this phone is deleted. Funds are unaffected.") })
}

@Composable fun IconLabel(icon: ImageVector, text: String) = Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text) }
