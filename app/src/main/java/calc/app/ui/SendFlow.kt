package calc.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calc.app.*
import calc.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private enum class Step { FORM, REVIEW, EXPORT, SCAN, SIGNED, DONE }

@Composable fun SendFlow(s: AppState, id: String) {
    val w = s.meta(id) ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cb = LocalClipboardManager.current
    var step by remember { mutableStateOf(Step.FORM) }
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var inSats by remember { mutableStateOf(s.sats) }
    var sendMax by remember { mutableStateOf(false) }
    var rbf by remember { mutableStateOf(true) }
    var algo by remember { mutableStateOf(CoinSelect.BRANCH_AND_BOUND) }
    var fees by remember { mutableStateOf<Map<Int, Double>?>(null) }
    var feeTarget by remember { mutableIntStateOf(3) }
    var customFee by remember { mutableStateOf("") }
    var scanAddr by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var psbt by remember { mutableStateOf<PsbtResult?>(null) }
    var signedTx by remember { mutableStateOf<org.bitcoindevkit.Transaction?>(null) }
    var broadcastId by remember { mutableStateOf<String?>(null) }
    val v = s.view(id)

    LaunchedEffect(Unit) {
        fees = withContext(Dispatchers.IO) { try { s.repo.engine(w).feeEstimates(s.repo.settings.server()) } catch (e: Exception) { null } }
    }
    val feeRate: Double? = if (feeTarget == 0) customFee.toDoubleOrNull() else fees?.get(feeTarget) ?: if (fees == null) null else 2.0

    fun amountSats(): Long? = try { if (inSats) amount.replace(",", "").toLong() else BigDecimal(amount).movePointRight(8).longValueExact() } catch (e: Exception) { null }

    fun handleSigned(p: SignedPayload) {
        err = null
        scope.launch {
            try {
                signedTx = withContext(Dispatchers.IO) {
                    val e = s.repo.engine(w)
                    when (p) {
                        is SignedPayload.RawTx -> org.bitcoindevkit.Transaction(p.bytes)
                        is SignedPayload.Psbt -> try { e.finalizeSigned(p.base64) } catch (x: Exception) {
                            // some signers strip data: merge with our unsigned PSBT and retry
                            val orig = psbt ?: throw x
                            val merged = org.bitcoindevkit.Psbt(orig.base64).combine(org.bitcoindevkit.Psbt(p.base64))
                            e.finalizeSigned(merged.serialize())
                        }
                    }
                }
                val expected = psbt?.let { org.bitcoindevkit.Psbt(it.base64).extractTxUncheckedFeeRate().computeTxid().toString() }
                val got = signedTx!!.computeTxid().toString()
                if (expected != null && expected != got) err = "⚠ Signed transaction does not match the PSBT you created (txid differs). Review carefully before broadcasting."
                step = Step.SIGNED
            } catch (e: Exception) { err = e.message ?: "Could not read signed data"; step = Step.EXPORT }
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) try {
            val bytes = if (step == Step.SIGNED) signedTx!!.serialize().let { UrUtil.hex(it).toByteArray() } else psbt!!.bytes
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }; s.toast = "Saved"
        } catch (e: Exception) { err = e.message }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { handleSigned(SignedPayload.fromBytes(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() })) } catch (e: Exception) { err = e.message }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(when (step) { Step.FORM -> "Send"; Step.REVIEW -> "Review"; Step.EXPORT -> "Sign on device"; Step.SCAN -> "Scan signed"; Step.SIGNED -> "Broadcast"; Step.DONE -> "Sent" },
            { when (step) { Step.FORM, Step.DONE -> s.back(); Step.REVIEW -> step = Step.FORM; Step.EXPORT -> step = Step.REVIEW; Step.SCAN -> step = Step.EXPORT; Step.SIGNED -> step = Step.EXPORT } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (step) {
                Step.FORM -> {
                    if (scanAddr) {
                        QrScanner(Modifier.fillMaxWidth()) { st ->
                            if (st is ScanCollector.State.DoneText) {
                                val t = st.text.trim()
                                if (t.lowercase().startsWith("bitcoin:")) {
                                    address = t.substringAfter(':').substringBefore('?')
                                    Regex("[?&]amount=([0-9.]+)").find(t)?.let { amount = it.groupValues[1]; inSats = false }
                                } else address = t
                            }
                            scanAddr = false
                        }
                        TextButton(onClick = { scanAddr = false }) { Text("Cancel") }
                    } else {
                        Card {
                            Text("Available: " + Fmt.btc(v.total, s.hide, s.sats), color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(address, { address = it.trim() }, label = { Text("Recipient address") }, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 14.sp),
                                trailingIcon = { IconButton(onClick = { scanAddr = true }) { Icon(Icons.Outlined.QrCodeScanner, "Scan") } })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(if (sendMax) "MAX" else amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, enabled = !sendMax, label = { Text(if (inSats) "Amount (sats)" else "Amount (BTC)") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(inSats, { inSats = !inSats; amount = "" }, { Text("sats") }); Spacer(Modifier.width(8.dp))
                                FilterChip(sendMax, { sendMax = !sendMax }, { Text("Send max") })
                            }
                        }
                        SectionLabel("Fee rate")
                        Card {
                            if (fees == null) Text("Fee estimates unavailable (server offline?) — enter a custom rate.", color = Muted, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1 to "Fast", 3 to "Normal", 6 to "Slow", 25 to "Eco").forEach { (n, l) ->
                                    val r = fees?.get(n)
                                    FilterChip(feeTarget == n, { feeTarget = n }, { Text(if (r != null) "$l\n%.1f".format(r) else l, fontSize = 12.sp) }, enabled = fees != null)
                                }
                                FilterChip(feeTarget == 0, { feeTarget = 0 }, { Text("Custom", fontSize = 12.sp) })
                            }
                            if (feeTarget == 0) OutlinedTextField(customFee, { customFee = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("sat/vB") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            feeRate?.let { Text("≈ %.1f sat/vB".format(it), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        SectionLabel("Advanced")
                        Card {
                            Toggle("Replace-by-fee (RBF)", rbf) { rbf = it }
                            Text("Coin selection", color = Muted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(CoinSelect.BRANCH_AND_BOUND to "Privacy (BnB)", CoinSelect.LARGEST_FIRST to "Largest", CoinSelect.OLDEST_FIRST to "Oldest").forEach { (a, l) -> FilterChip(algo == a, { algo = a }, { Text(l, fontSize = 12.sp) }) }
                            }
                            Text("Change returns to this wallet's change path (…/1/*).", color = Muted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(enabled = !busy && address.isNotBlank() && (sendMax || amountSats() != null) && feeRate != null, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), onClick = {
                            busy = true; err = null
                            scope.launch {
                                try {
                                    psbt = withContext(Dispatchers.IO) { s.repo.engine(w).createPsbt(listOf(Recipient(address, if (sendMax) 0 else amountSats()!!)), feeRate!!, rbf, algo, sendMax) }
                                    step = Step.REVIEW
                                } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
                                busy = false
                            }
                        }) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Bg) else Text("Create PSBT") }
                        if (v.txs.isEmpty()) Text("Tip: sync the wallet first so coins are known.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Step.REVIEW -> psbt?.let { p ->
                    Card {
                        Text("Sending", color = Muted); Text(Fmt.btc(p.sendAmount, false, s.sats), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                        Fmt.gbp(p.sendAmount, s.price, false)?.let { Text(it, color = Muted) }
                        Spacer(Modifier.height(8.dp))
                        KV("Network fee", Fmt.btc(p.fee, false, true) + "  ·  %.1f sat/vB".format(feeRate ?: 0.0))
                        KV("Change back to you", Fmt.btc(p.changeAmount, false, s.sats))
                        KV("Inputs", p.inputs.toString())
                        KV("RBF", if (rbf) "Enabled" else "Disabled")
                    }
                    SectionLabel("Outputs")
                    Card { p.outputs.forEach { (a, amt) -> KV(Fmt.btc(amt, false, s.sats), a, mono = true) } }
                    if (p.fee > p.sendAmount / 10 && !sendMax) { Spacer(Modifier.height(8.dp)); WarnBox("Fee is more than 10% of the amount sent.") }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { step = Step.EXPORT }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Export to signer") }
                }
                Step.EXPORT -> psbt?.let { p -> ExportPsbt(p, onScan = { step = Step.SCAN }, onSave = { LockGuard.allowExternal(); saver.launch("unsigned.psbt") },
                    onOpen = { LockGuard.allowExternal(); opener.launch(arrayOf("*/*")) }, onCopy = { cb.setText(AnnotatedString(p.base64)); s.toast = "PSBT copied (base64)" }) }
                Step.SCAN -> {
                    QrScanner(Modifier.fillMaxWidth()) { st ->
                        try {
                            when (st) {
                                is ScanCollector.State.DoneUr -> handleSigned(SignedPayload.fromUr(st.ur))
                                is ScanCollector.State.DoneBbqr -> handleSigned(SignedPayload.fromBbqr(st.joiner))
                                is ScanCollector.State.DoneText -> handleSigned(SignedPayload.fromText(st.text))
                                else -> {}
                            }
                        } catch (e: Exception) { err = e.message; step = Step.EXPORT }
                    }
                }
                Step.SIGNED -> signedTx?.let { tx ->
                    Card {
                        Text("✓ Fully signed", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        KV("Transaction ID", tx.computeTxid().toString(), mono = true, copy = true)
                        KV("Size", "${tx.vsize()} vB")
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), onClick = {
                        busy = true; err = null
                        scope.launch {
                            try { broadcastId = withContext(Dispatchers.IO) { s.repo.engine(w).broadcast(s.repo.settings.server(), tx) }; step = Step.DONE; s.sync(id) }
                            catch (e: Exception) { err = "Broadcast failed: " + AppState.friendly(e) }
                            busy = false
                        }
                    }) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Bg) else Text("Broadcast via ${s.repo.settings.server().host}") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { cb.setText(AnnotatedString(UrUtil.hex(tx.serialize()))); s.toast = "Raw tx hex copied" }) { Text("Copy hex") }
                        OutlinedButton(onClick = { LockGuard.allowExternal(); saver.launch("signed.txn") }) { Text("Save .txn") }
                    }
                }
                Step.DONE -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Green, modifier = Modifier.size(72.dp))
                        Text("Broadcast", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(broadcastId ?: "", fontFamily = Mono, fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(16.dp)); Button(onClick = { s.back() }) { Text("Done") }
                    }
                }
            }
            err?.let { Spacer(Modifier.height(12.dp)); WarnBox(it, Red) }
        }
    }
}

@Composable fun ExportPsbt(p: PsbtResult, onScan: () -> Unit, onSave: () -> Unit, onOpen: () -> Unit, onCopy: () -> Unit) {
    var fmt by remember { mutableIntStateOf(0) } // 0 BBQr, 1 UR
    var density by remember { mutableIntStateOf(1) } // 0 low 1 med 2 high
    var speed by remember { mutableIntStateOf(1) }
    val frameMs = listOf(500L, 300L, 180L)[speed]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(fmt == 0, { fmt = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("BBQr · Coldcard Q") }
            SegmentedButton(fmt == 1, { fmt = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("UR · SeedSigner/Jade") }
        }
        Spacer(Modifier.height(14.dp))
        if (fmt == 0) {
            val parts = remember(p.base64, density) { PsbtExport.bbqrParts(p.bytes, listOf(240, 480, 960)[density]) }
            var i by remember(parts) { mutableIntStateOf(0) }
            AnimatedQr(parts to density, frameMs, { val f = parts[i % parts.size]; i++; f }, Modifier.fillMaxWidth())
            Text("${parts.size} part${if (parts.size == 1) "" else "s"} · BBQr", color = Muted, fontSize = 12.sp)
        } else {
            val enc = remember(p.base64, density) { PsbtExport.urParts(p.bytes, listOf(60, 120, 250)[density]) }
            AnimatedQr(enc to density, frameMs, { enc.nextPart().uppercase() }, Modifier.fillMaxWidth())
            Text("ur:crypto-psbt · ${enc.seqLen} fragment${if (enc.seqLen == 1) "" else "s"} (fountain)", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Density", color = Muted, fontSize = 12.sp)
            listOf("Low", "Med", "High").forEachIndexed { i, l -> FilterChip(density == i, { density = i }, { Text(l, fontSize = 12.sp) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Speed", color = Muted, fontSize = 12.sp)
            listOf("Slow", "Normal", "Fast").forEachIndexed { i, l -> FilterChip(speed == i, { speed = i }, { Text(l, fontSize = 12.sp) }) }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan signed PSBT / transaction") }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave) { Text("Save .psbt") }
            OutlinedButton(onClick = onOpen) { Text("Load signed file") }
            OutlinedButton(onClick = onCopy) { Text("Copy") }
        }
    }
}
