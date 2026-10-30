package calc.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calc.app.*
import calc.core.Candle
import calc.core.Market
import calc.core.Timeframe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val ChartBg = Color(0xFF0B0B0D)
val Up = Color(0xFF2EBD85)
val Down = Color(0xFFF6465D)
private val Grid = Color(0xFF1E1E23)
private val AxisText = Color(0xFF7A7A85)
private val Ma5 = Color(0xFFF0B90B)
private val Ma10 = Color(0xFF4FA3F7)

fun fmtPrice(p: Double) = "%,.1f".format(Locale.US, p)
private fun fmtVol(v: Double) = when { v >= 1e6 -> "%.2fM".format(Locale.US, v / 1e6); v >= 1e3 -> "%.2fK".format(Locale.US, v / 1e3); else -> "%.2f".format(Locale.US, v) }

@Composable fun MarketHome(s: AppState) {
    if (s.market) LaunchedEffect(s.tf) { s.marketLoop(s.tf) }
    Column(Modifier.fillMaxSize().background(ChartBg)) {
        Column(Modifier.fillMaxWidth().weight(1.25f)) {
            if (s.market) PricePanel(s)
            else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ShowChart, null, tint = Muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp)); Text("Price chart is off", color = Muted)
                    Text("No market data is fetched. Turn it on in Settings.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!s.configured) Text("No server set", color = Orange, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { s.onLock() }) { Icon(Icons.Outlined.Lock, "Lock", tint = Muted) }
                IconButton(onClick = { s.go(Screen.Settings) }) { Icon(Icons.Outlined.Settings, "Settings", tint = Muted) }
            }
            Button(onClick = { s.go(Screen.Wallets) }, modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface1, contentColor = Color.White)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).background(Orange.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Orange, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Wallets", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(if (s.wallets.isEmpty()) "Add a watch-only wallet" else "${s.wallets.size} watch-only wallet" + if (s.wallets.size == 1) "" else "s", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable private fun PricePanel(s: AppState) {
    val t = s.ticker
    val last = t?.last ?: s.candles.lastOrNull()?.c
    val ch = t?.changePct
    val dir by animateColorAsState(if ((ch ?: 0.0) >= 0) Up else Down, label = "dir")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("BTC/USD", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(last?.let { fmtPrice(it) } ?: "—", color = dir, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
            Text(ch?.let { "%+.2f%%".format(Locale.US, it) + "  24h" } ?: (s.marketError ?: "Loading…"), color = if (ch != null) dir else Muted, fontSize = 13.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Stat("24h High", t?.high24?.let(::fmtPrice)); Stat("24h Low", t?.low24?.let(::fmtPrice)); Stat("24h Vol (BTC)", t?.vol24?.let { "%,.0f".format(Locale.US, it) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Timeframe.values().forEach { tf ->
            val sel = s.tf == tf
            Column(Modifier.weight(1f).clickable { s.tf = tf }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tf.label, color = if (sel) Color.White else AxisText, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                val w by animateDpAsState(if (sel) 18.dp else 0.dp, label = "ul")
                Spacer(Modifier.height(4.dp)); Box(Modifier.width(w).height(3.dp).background(Orange, RoundedCornerShape(2.dp)))
            }
        }
    }
    HorizontalDivider(color = Grid)
    Box(Modifier.fillMaxSize()) {
        if (s.candles.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (s.marketError != null) Text(s.marketError!!, color = Muted, fontSize = 12.sp) else CircularProgressIndicator(color = Orange, strokeWidth = 2.dp)
        } else CandleChart(s.candles, s.tf, Modifier.fillMaxSize())
        s.marketSource?.let { Text(it, color = AxisText.copy(alpha = 0.6f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) }
    }
}

@Composable private fun Stat(k: String, v: String?) = Row {
    Text(k, color = AxisText, fontSize = 11.sp); Spacer(Modifier.width(10.dp))
    Text(v ?: "—", color = Color.White, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}

@Composable fun CandleChart(all: List<Candle>, tf: Timeframe, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    var touch by remember { mutableStateOf<Offset?>(null) }
    Canvas(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val d = awaitFirstDown(); touch = d.position
            while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; touch = c.position; if (c.positionChange() != Offset.Zero) c.consume() }
            touch = null
        }
    }) { drawChart(all, tf, tm, touch) }
}

private fun DrawScope.label(tm: TextMeasurer, s: String, x: Float, y: Float, color: Color = AxisText, size: Float = 10f, bg: Color? = null, alignRight: Boolean = false) {
    val r = tm.measure(s, TextStyle(color = color, fontSize = size.sp))
    val px = if (alignRight) x - r.size.width else x
    if (bg != null) drawRoundRect(bg, Offset(px - 4f, y - 2f), Size(r.size.width + 8f, r.size.height + 4f), androidx.compose.ui.geometry.CornerRadius(6f))
    drawText(r, topLeft = Offset(px, y))
}

private fun DrawScope.drawChart(all: List<Candle>, tf: Timeframe, tm: TextMeasurer, touch: Offset?) {
    val c = all.takeLast(if (tf == Timeframe.LINE) 160 else 72)
    if (c.isEmpty()) return
    val axisW = 64.dp.toPx(); val timeH = 16.dp.toPx(); val top = 14.dp.toPx()
    val plotW = size.width - axisW
    val volH = (size.height - timeH) * 0.2f
    val priceBottom = size.height - timeH - volH - 14.dp.toPx()
    val volTop = priceBottom + 14.dp.toPx(); val volBottom = size.height - timeH
    val hi = c.maxOf { it.h }; val lo = c.minOf { it.l }
    val pad = (hi - lo).coerceAtLeast(1.0) * 0.06
    val pMax = hi + pad; val pMin = lo - pad
    fun y(p: Double) = (top + (pMax - p) / (pMax - pMin) * (priceBottom - top)).toFloat()
    val step = plotW / c.size; val bodyW = (step * 0.62f).coerceAtLeast(1f)
    fun x(i: Int) = step * i + step / 2

    // grid + price axis
    for (k in 0..4) {
        val p = pMin + (pMax - pMin) * k / 4; val yy = y(p)
        drawLine(Grid, Offset(0f, yy), Offset(plotW, yy), 1f)
        label(tm, fmtPrice(p), size.width - 2f, yy - 14f, alignRight = true)
    }
    for (k in 1..3) { val xx = plotW * k / 4; drawLine(Grid, Offset(xx, top), Offset(xx, volBottom), 1f) }

    if (tf == Timeframe.LINE) {
        val path = Path(); c.forEachIndexed { i, k -> if (i == 0) path.moveTo(x(i), y(k.c)) else path.lineTo(x(i), y(k.c)) }
        val fill = Path().apply { addPath(path); lineTo(x(c.size - 1), priceBottom); lineTo(x(0), priceBottom); close() }
        drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Orange.copy(alpha = 0.25f), Color.Transparent), top, priceBottom))
        drawPath(path, Orange, style = Stroke(1.6.dp.toPx()))
    } else c.forEachIndexed { i, k ->
        val col = if (k.c >= k.o) Up else Down; val xx = x(i)
        drawLine(col, Offset(xx, y(k.h)), Offset(xx, y(k.l)), 1.dp.toPx().coerceAtMost(2f))
        val yt = y(maxOf(k.o, k.c)); val yb = y(minOf(k.o, k.c))
        drawRect(col, Offset(xx - bodyW / 2, yt), Size(bodyW, (yb - yt).coerceAtLeast(1f)))
    }

    // high / low markers
    val hiI = c.indices.maxBy { c[it].h }; val loI = c.indices.minBy { c[it].l }
    fun marker(i: Int, p: Double, above: Boolean) {
        val xx = x(i); val yy = y(p); val left = xx > plotW * 0.6f; val txt = fmtPrice(p)
        val r = tm.measure(txt, TextStyle(fontSize = 10.sp))
        val ex = if (left) xx - 16.dp.toPx() else xx + 16.dp.toPx()
        drawLine(Color(0xFFB0B0B8), Offset(xx, yy), Offset(ex, yy), 1f)
        label(tm, txt, if (left) ex - r.size.width - 2f else ex + 2f, yy - r.size.height / 2f, Color(0xFFD0D0D8))
    }
    marker(hiI, c[hiI].h, true); marker(loI, c[loI].l, false)

    // dashed current price line + tag
    val last = c.last(); val ly = y(last.c); val lc = if (last.c >= last.o) Up else Down
    drawLine(lc.copy(alpha = 0.8f), Offset(0f, ly), Offset(plotW, ly), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    label(tm, fmtPrice(last.c), size.width - 2f, ly - 8f, Color.White, 10f, lc, alignRight = true)

    // volume + MA
    val vMax = c.maxOf { it.v }.coerceAtLeast(1e-9)
    fun vy(v: Double) = (volBottom - v / vMax * (volBottom - volTop)).toFloat()
    c.forEachIndexed { i, k -> val col = (if (k.c >= k.o) Up else Down).copy(alpha = 0.75f); drawRect(col, Offset(x(i) - bodyW / 2, vy(k.v)), Size(bodyW, volBottom - vy(k.v))) }
    val vols = c.map { it.v }
    fun maLine(n: Int, col: Color) { val m = Market.sma(vols, n); val p = Path(); var st = false
        m.forEachIndexed { i, v -> if (v != null) { if (!st) { p.moveTo(x(i), vy(v)); st = true } else p.lineTo(x(i), vy(v)) } }
        drawPath(p, col, style = Stroke(1.dp.toPx())) }
    maLine(5, Ma5); maLine(10, Ma10)
    val m5 = Market.sma(vols, 5).last(); val m10 = Market.sma(vols, 10).last()
    val vr = tm.measure("VOL:${fmtVol(last.v)}  ", TextStyle(fontSize = 9.sp)); val r5 = tm.measure("MA5:${m5?.let(::fmtVol) ?: "-"}  ", TextStyle(fontSize = 9.sp))
    val vLabY = priceBottom + 1f
    label(tm, "VOL:${fmtVol(last.v)}", 4f, vLabY, Down, 9f)
    label(tm, "MA5:${m5?.let(::fmtVol) ?: "-"}", 4f + vr.size.width, vLabY, Ma5, 9f)
    label(tm, "MA10:${m10?.let(::fmtVol) ?: "-"}", 4f + vr.size.width + r5.size.width, vLabY, Ma10, 9f)
    label(tm, fmtVol(vMax), size.width - 2f, volTop - 2f, alignRight = true, size = 9f)

    // time axis
    val fmt = SimpleDateFormat(if (tf.seconds >= 86400) "d MMM" else if (tf.seconds >= 28800) "d MMM HH:mm" else "HH:mm", Locale.UK)
    for (k in 0..3) { val i = ((c.size - 1) * (k + 0.5) / 4).toInt(); val txt = fmt.format(Date(c[i].t * 1000))
        val r = tm.measure(txt, TextStyle(fontSize = 9.sp)); label(tm, txt, (x(i) - r.size.width / 2).coerceIn(0f, plotW - r.size.width), volBottom + 2f, size = 9f) }

    // crosshair
    touch?.let { p ->
        val i = (p.x / step).toInt().coerceIn(0, c.size - 1); val k = c[i]; val xx = x(i)
        val yy = p.y.coerceIn(top, priceBottom)
        val cross = Color(0xFFCFCFD6)
        drawLine(cross, Offset(xx, top), Offset(xx, volBottom), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawLine(cross, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val price = pMax - (yy - top) / (priceBottom - top) * (pMax - pMin)
        label(tm, fmtPrice(price), size.width - 2f, yy - 8f, Color.Black, 10f, cross, alignRight = true)
        val tl = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(k.t * 1000))
        val ch = if (k.o != 0.0) (k.c - k.o) / k.o * 100 else 0.0
        val lines = listOf(tl, "O  ${fmtPrice(k.o)}", "H  ${fmtPrice(k.h)}", "L  ${fmtPrice(k.l)}", "C  ${fmtPrice(k.c)}", "%+.2f%%".format(Locale.US, ch), "V  ${fmtVol(k.v)}")
        val boxW = 112.dp.toPx(); val lh = 13.dp.toPx(); val bx = if (xx > plotW / 2) 6.dp.toPx() else plotW - boxW - 6.dp.toPx()
        drawRoundRect(Color(0xE61A1A1F), Offset(bx, top), Size(boxW, lh * lines.size + 8f), androidx.compose.ui.geometry.CornerRadius(12f))
        lines.forEachIndexed { j, s -> label(tm, s, bx + 8f, top + 4f + j * lh, if (j == 5) (if (ch >= 0) Up else Down) else Color.White, 10f) }
    }
}
