package calc.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calc.app.*
import calc.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoindevkit.Network

@Composable fun ImportScreen(s: AppState) {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 choose, 1 scan
    var text by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<WalletCandidate>>(emptyList()) }
    var selected by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var testNet by remember { mutableStateOf(Network.TESTNET) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun parse(input: String) {
        err = null; candidates = emptyList()
        try { candidates = ImportParser.parse(input, "Wallet"); selected = 0; name = candidates.first().label; mode = 0 }
        catch (e: SecretGuard.SecretMaterialException) { err = "⛔ " + e.message; text = "" }
        catch (e: Exception) { err = e.message ?: "Could not parse" }
    }
    fun parseUr(ur: UR) {
        err = null
        try { candidates = ImportParser.parseUr(ur, "Wallet"); selected = 0; name = candidates.first().label; mode = 0 }
        catch (e: Exception) { err = (if (e is SecretGuard.SecretMaterialException) "⛔ " else "") + (e.message ?: "Could not parse") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size < 2_000_000) { "File too large" }
            parse(String(bytes))
        } catch (e: Exception) { err = e.message }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Add wallet", { s.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (mode == 1) {
                QrScanner(Modifier.fillMaxWidth()) { st ->
                    when (st) {
                        is ScanCollector.State.DoneUr -> parseUr(st.ur)
                        is ScanCollector.State.DoneBbqr -> parse(String(st.joiner.result()))
                        is ScanCollector.State.DoneText -> parse(st.text)
                        else -> {}
                    }
                    mode = 0
                }
                TextButton(onClick = { mode = 0 }) { Text("Cancel scan") }
            } else if (candidates.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigChoice(Icons.Outlined.QrCodeScanner, "Scan QR", "UR / BBQr / text", Modifier.weight(1f)) { mode = 1 }
                    BigChoice(Icons.Outlined.FileOpen, "Open file", "Sparrow / Coldcard", Modifier.weight(1f)) { LockGuard.allowExternal(); picker.launch(arrayOf("*/*")) }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(text, { text = it }, label = { Text("…or paste xpub / zpub / descriptor") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { parse(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                Spacer(Modifier.height(16.dp))
                WarnBox("Supported: xpub/ypub/zpub/tpub/vpub, output descriptors (wpkh, sh(wpkh), tr, pkh, wsh/sh(wsh) sortedmulti incl. BIP48), Sparrow descriptor .txt / wallet JSON, BSMS, Coldcard generic JSON & multisig .txt, Electrum JSON, UR crypto-account / crypto-output / crypto-hdkey.\n\nNever enter a seed or xprv — they are rejected.", Muted)
            } else {
                if (candidates.size > 1) {
                    SectionLabel("Choose account")
                    candidates.forEachIndexed { i, c ->
                        Row(Modifier.fillMaxWidth().clickable { selected = i; name = c.label }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == i, { selected = i; name = c.label }); Text(c.kind)
                        }
                    }
                }
                val c = candidates[selected]
                Card {
                    Text(c.kind, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(if (c.mainnet) "Bitcoin mainnet" else "Test network", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    Text(c.external, fontFamily = Mono, fontSize = 11.sp, color = Muted, maxLines = 6)
                }
                c.warnings.forEach { Spacer(Modifier.height(8.dp)); WarnBox(it) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Wallet name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!c.mainnet) {
                    SectionLabel("Test network")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Network.TESTNET, Network.TESTNET4, Network.SIGNET, Network.REGTEST).forEach { n ->
                            FilterChip(testNet == n, { testNet = n }, { Text(n.name.lowercase()) })
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        try {
                            val m = withContext(Dispatchers.IO) { s.repo.add(c, name, if (c.mainnet) Network.BITCOIN else testNet) }
                            s.wallets.add(m); s.back(); s.go(Screen.Wallet(m.id)); if (s.configured) s.sync(m.id) else { s.go(Screen.Settings); s.toast = "Choose an Electrum server to sync" }
                        } catch (e: Exception) { err = "Invalid wallet: " + e.message }
                    }
                }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add watch-only wallet") }
                TextButton(onClick = { candidates = emptyList() }) { Text("Back") }
            }
            err?.let { Spacer(Modifier.height(12.dp)); WarnBox(it, Red) }
        }
    }
}

@Composable fun BigChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(Surface1, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(18.dp)) {
        Icon(icon, null, tint = Orange, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold); Text(sub, color = Muted, fontSize = 12.sp)
    }
}

@Composable fun SettingsScreen(s: AppState) {
    val st = s.repo.settings
    var host by remember { mutableStateOf(st.host) }
    var port by remember { mutableStateOf(st.port.toString()) }
    var ssl by remember { mutableStateOf(st.ssl) }
    var tlsv by remember { mutableStateOf(st.validateTls) }
    var proxy by remember { mutableStateOf(st.proxy) }
    var fiat by remember { mutableStateOf(st.fiat) }
    var satsUnit by remember { mutableStateOf(st.sats) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var code1 by remember { mutableStateOf("") }
    var code2 by remember { mutableStateOf("") }
    var codeMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isPublic = host.isNotBlank() && !ServerCheck.isPrivate(host)
    var usePublic by remember { mutableStateOf(st.usePublic) }
    var pubHost by remember { mutableStateOf(st.pubHost) }
    var pubPort by remember { mutableStateOf(st.pubPort.toString()) }
    var confirmPublic by remember { mutableStateOf(false) }

    fun save() { st.usePublic = usePublic; st.pubHost = pubHost.trim(); st.pubPort = pubPort.toIntOrNull() ?: 50002;  st.host = host.trim(); st.port = port.toIntOrNull() ?: 50001; st.ssl = ssl; st.validateTls = tlsv; st.proxy = proxy.trim(); s.publicServer = st.isPublicActive(); s.configured = st.configured() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", { save(); s.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionLabel("Electrum server")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!usePublic, { usePublic = false; save() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("My own server") }
                SegmentedButton(usePublic, { if (!usePublic) confirmPublic = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Public server") }
            }
            Spacer(Modifier.height(8.dp))
            if (usePublic) Card {
                WarnBox("⚠ Public server in use: it can see your addresses, balances and IP and link them together.", Red)
                Spacer(Modifier.height(8.dp))
                PUBLIC_SERVERS.forEach { (h, pt) ->
                    Row(Modifier.fillMaxWidth().clickable { pubHost = h; pubPort = pt.toString(); save() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(pubHost == h && pubPort == pt.toString(), { pubHost = h; pubPort = pt.toString(); save() }); Text("$h:$pt  (SSL)", fontSize = 14.sp)
                    }
                }
                Text("Custom public server", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(pubHost, { pubHost = it.trim() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pubPort, { pubPort = it.filter(Char::isDigit).take(5) }, label = { Text("SSL port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(proxy, { proxy = it }, label = { Text("SOCKS5 proxy (Tor/Orbot: 127.0.0.1:9050)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(); s.toast = "Saved" }) { Text("Save") }
                    OutlinedButton(onClick = {
                        save(); testResult = "Connecting…"
                        scope.launch { testResult = withContext(Dispatchers.IO) { try { org.bitcoindevkit.ElectrumClient(st.server().url, st.server().socks5, 15u.toUByte(), 1u.toUByte(), true).use { c -> c.ping(); "✓ Connected · tip height ${c.blockHeadersSubscribe().height}" } } catch (e: Exception) { "✗ " + AppState.friendly(e) } } }
                    }) { Text("Test connection") }
                }
                testResult?.let { Text(it, color = if (it.startsWith("✓")) Green else Muted, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (!usePublic) Card {
                OutlinedTextField(host, { host = it }, label = { Text("Host (your node, e.g. 192.168.x.x / tailnet IP / .onion)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Toggle("SSL/TLS", ssl) { ssl = it; if (it && port == "50001") port = "50002" else if (!it && port == "50002") port = "50001" }
                if (ssl) Toggle("Verify TLS certificate (off for self-signed node certs)", tlsv) { tlsv = it }
                OutlinedTextField(proxy, { proxy = it }, label = { Text("SOCKS5 proxy (Tor/Orbot: 127.0.0.1:9050)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (isPublic) { Spacer(Modifier.height(8.dp)); WarnBox("⚠ “$host” looks like a PUBLIC server. A public Electrum server learns all your addresses, balances and IP. Prefer your own node (e.g. a home node over a VPN) or at least use Tor.", Red) }
                if (host.endsWith(".onion") && proxy.isBlank()) { Spacer(Modifier.height(8.dp)); WarnBox("Onion hosts need a SOCKS5 proxy (Orbot).") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(); s.toast = "Saved" }) { Text("Save") }
                    OutlinedButton(onClick = {
                        save(); testResult = "Connecting…"
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                try { org.bitcoindevkit.ElectrumClient(st.server().url, st.server().socks5, 15u.toUByte(), 1u.toUByte(), st.validateTls).use { c -> c.ping(); "✓ Connected · tip height ${c.blockHeadersSubscribe().height}" } }
                                catch (e: Exception) { "✗ " + AppState.friendly(e) }
                            }
                        }
                    }) { Text("Test connection") }
                }
                testResult?.let { Text(it, color = if (it.startsWith("✓")) Green else Muted, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (confirmPublic) AlertDialog(onDismissRequest = { confirmPublic = false },
                title = { Text("Use a public server?") },
                text = { Text("A public Electrum server can see all your wallet addresses, balances and transaction history, and your IP address, and can link them all together. Anyone running it (or watching it) learns what you own.\n\nStrongly recommended: use your own node (e.g. a home node over a VPN), or at least connect through Tor.") },
                confirmButton = { TextButton(onClick = { confirmPublic = false; usePublic = true; save() }) { Text("I understand, use public", color = Red) } },
                dismissButton = { TextButton(onClick = { confirmPublic = false }) { Text("Keep my own server") } })
            SectionLabel("Privacy & display")
            Card {
                Toggle("Hide amounts", s.hide) { s.toggleHide() }
                Toggle("Show amounts in sats", satsUnit) { satsUnit = it; st.sats = it; s.sats = it }
                Toggle("Live BTC/USD price chart on home", s.market) { s.setMarketEnabled(it) }
                Text(if (s.market) "Fetches public market data only (Kraken, falling back to Coinbase / Bitstamp) about every 10 s while the home screen is open. No wallet data is sent; your IP is visible to the exchange (use the SOCKS proxy to hide it)." else "Off: no market data is fetched and the chart is hidden.", color = Muted, fontSize = 12.sp)
                Toggle("Show GBP value (fetches price from mempool.space)", fiat) { fiat = it; st.fiat = it; s.refreshPrice() }
                if (fiat) Text("Price requests reveal your IP to mempool.space (via the SOCKS proxy if set). No addresses are sent.", color = Muted, fontSize = 12.sp)
            }
            SectionLabel("Secret code")
            Card {
                Text(if (st.voiceRequired) "Type the code on the calculator, then hold = and say the passphrase." else "Type the code on the calculator and press = to open the vault.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(code1, { code1 = it.filter(Char::isDigit).take(15) }, label = { Text("New code (4–15 digits)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code2, { code2 = it.filter(Char::isDigit).take(15) }, label = { Text("Repeat new code") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    when {
                        !CodeLock.isValidCode(code1) -> codeMsg = "Code must be 4–15 digits"
                        code1 != code2 -> codeMsg = "Codes don't match"
                        else -> scope.launch { withContext(Dispatchers.Default) { s.repo.lock.setCode(code1) }; code1 = ""; code2 = ""; codeMsg = "✓ Code changed" }
                    }
                }) { Text("Change code") }
                codeMsg?.let { Text(it, color = if (it.startsWith("✓")) Green else Red, modifier = Modifier.padding(top = 6.dp)) }
            }
            SectionLabel("Voice passphrase")
            Card {
                var voiceOn by remember { mutableStateOf(st.voiceRequired) }
                var ph1 by remember { mutableStateOf("") }; var ph2 by remember { mutableStateOf("") }
                var phMsg by remember { mutableStateOf<String?>(null) }
                Toggle("Require spoken passphrase", voiceOn) { voiceOn = it; st.voiceRequired = it }
                Text(if (voiceOn) "Type the code, then press and hold = and say your passphrase; let go to open. A quick tap of = just does maths. Recognition runs on-device where available; audio and text are never stored or sent by this app, and only a salted hash of the passphrase is kept."
                     else "Off: the code plus a tap of = opens the vault (code only).", color = Muted, fontSize = 12.sp)
                if (voiceOn) {
                    OutlinedTextField(ph1, { ph1 = it }, label = { Text("New passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ph2, { ph2 = it }, label = { Text("Repeat passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        when {
                            !VoicePass.isValidPhrase(ph1) -> phMsg = "Use at least ${VoicePass.MIN_CHARS} letters (two or more words work best)"
                            VoicePass.canonical(ph1) != VoicePass.canonical(ph2) -> phMsg = "Passphrases don't match"
                            else -> { val p = ph1; scope.launch { withContext(Dispatchers.Default) { s.repo.voice.setPhrase(p) }; ph1 = ""; ph2 = ""; phMsg = "✓ Passphrase changed" } }
                        }
                    }) { Text("Change passphrase") }
                    phMsg?.let { Text(it, color = if (it.startsWith("✓")) Green else Red, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            SectionLabel("About")
            Card {
                Text("Watch-only. This app never sees seeds or private keys; it builds unsigned PSBTs for your air-gapped signer (Coldcard Q via BBQr, SeedSigner / Jade / Keystone / Sparrow via UR) and broadcasts only through your Electrum server. No analytics, no Google services.", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

val PUBLIC_SERVERS = listOf("electrum.blockstream.info" to 50002, "fulcrum.sethforprivacy.com" to 50002, "electrum.emzy.de" to 50002, "bitcoin.lu.ke" to 50002, "electrum.bitaroo.net" to 50002)

@Composable fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Orange))
    }
