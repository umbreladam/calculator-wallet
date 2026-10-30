package calc.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calc.core.ExprCalc

/** Hooks for the '=' key. A press reports the secret-code candidate; a hold (≥ [HOLD_MS]) and the release follow. */
interface EqualsHooks {
    fun onPress(candidate: String?)
    /** Returns true if listening started (a subtle haptic tick is given). */
    fun onHold(): Boolean
    fun onRelease(held: Boolean)
}
const val HOLD_MS = 450L

// Pixel Calculator, Material You warm-brown palette
private val CBg = Color(0xFF140F0C)
private val CDigit = Color(0xFF2E241F)
private val CAc = Color(0xFF7A4A2E)
private val CFn = Color(0xFF4A3428)
private val COp = Color(0xFF4F3A2E)
private val CEq = Color(0xFFFDF6EC)
private val CEqText = Color(0xFF5A5A1E)
private val CText = Color(0xFFF3E0D3)
private val COpText = Color(0xFFF0D6C6)
private val CMuted = Color(0xFFA8968A)
private val CCursor = Color(0xFFF2B8A0)

private enum class Kind { DIGIT, AC, FN, OP, EQ }
private data class K(val label: String, val kind: Kind, val action: ExprCalc.() -> Unit)

private fun prettyExpr(s: String): String =
    Regex("\\d+").replace(s) { m -> val st = m.range.first; if (st > 0 && s[st - 1] == '.') m.value else m.value.reversed().chunked(3).joinToString(",").reversed() }

/** The disguise: a Pixel-style expression calculator. '=' evaluates on release; [hooks] decide whether anything else happens. */
@Composable fun CalculatorScreen(hooks: EqualsHooks) {
    val calc = remember { ExprCalc() }
    var tick by remember { mutableIntStateOf(0) }
    var sci by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    fun act(f: ExprCalc.() -> Unit) { calc.f(); tick++; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    val ac = K("AC", Kind.AC) { clear() }
    val paren = K("( )", Kind.FN) { paren() }
    val pct = K("%", Kind.FN) { percent() }
    fun d(c: Char) = K(c.toString(), Kind.DIGIT) { digit(c) }
    fun o(sym: String) = K(sym, Kind.OP) { op(sym) }
    val dot = K(".", Kind.DIGIT) { dot() }
    val bs = K("⌫", Kind.DIGIT) { backspace() }
    val eq = K("=", Kind.EQ) { equals() }

    BoxWithConstraints(Modifier.fillMaxSize().background(CBg).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight
        val screenH = maxHeight
        val rows: List<List<K>> = if (!landscape) listOf(
            listOf(ac, paren, pct, o("÷")), listOf(d('7'), d('8'), d('9'), o("×")), listOf(d('4'), d('5'), d('6'), o("−")),
            listOf(d('1'), d('2'), d('3'), o("+")), listOf(d('0'), dot, bs, eq))
        else listOf(
            listOf(d('7'), d('8'), d('9'), ac, o("×")), listOf(d('4'), d('5'), d('6'), paren, o("−")),
            listOf(d('1'), d('2'), d('3'), pct, o("+")), listOf(d('0'), dot, bs, o("÷"), eq))
        val sciKeys: List<K> = listOf(
            K(if (calc.inverse) "INV" else "inv", Kind.FN) { inverse = !inverse }, K(if (calc.degrees) "deg" else "rad", Kind.FN) { degrees = !degrees },
            K(if (calc.inverse) "x²" else "√", Kind.FN) { function(if (inverse) "x²" else "√") }, K("π", Kind.FN) { constant("π") },
            K(if (calc.inverse) "sin⁻¹" else "sin", Kind.FN) { function(if (inverse) "sin⁻¹" else "sin") }, K(if (calc.inverse) "cos⁻¹" else "cos", Kind.FN) { function(if (inverse) "cos⁻¹" else "cos") },
            K(if (calc.inverse) "tan⁻¹" else "tan", Kind.FN) { function(if (inverse) "tan⁻¹" else "tan") }, K("e", Kind.FN) { constant("e") },
            K(if (calc.inverse) "eˣ" else "ln", Kind.FN) { function(if (inverse) "exp" else "ln") }, K(if (calc.inverse) "10ˣ" else "log", Kind.FN) { function(if (inverse) "10^" else "log") },
            K("^", Kind.FN) { op("^") }, K("!", Kind.FN) { factorial() })

        Column(Modifier.fillMaxSize().padding(horizontal = if (landscape) 24.dp else 4.dp, vertical = 4.dp)) {
            // top bar: history and overflow
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "History", tint = CMuted) }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options", tint = CMuted) }
                    DropdownMenu(menu, { menu = false }, containerColor = Color(0xFF2A211C)) {
                        DropdownMenuItem({ Text("History", color = CText) }, { menu = false; showHistory = true })
                        DropdownMenuItem({ Text("Clear history", color = CText) }, { menu = false; calc.clearHistory(); tick++ })
                    }
                }
            }
            // display
            key(tick) { Display(calc, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), landscape) }
            // chevron
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = { sci = !sci }) {
                    Icon(if (landscape) Icons.Filled.Code else if (sci) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore, "Scientific", tint = CMuted, modifier = Modifier.size(20.dp))
                }
            }
            val gap = if (landscape) 6.dp else 8.dp
            key(tick) {
                AnimatedVisibility(sci, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val per = if (landscape) 6 else 4
                    Column(Modifier.padding(bottom = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                        sciKeys.chunked(per).forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            r.forEach { k -> Key(k, Modifier.weight(1f).height(if (landscape) 30.dp else 40.dp), pill = true, small = true) { act(k.action) } }
                        } }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    rows.forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        r.forEach { k ->
                            val m = if (landscape) Modifier.weight(1f).height(((screenH - 140.dp) / 4 - gap).coerceIn(34.dp, 64.dp)) else Modifier.weight(1f).aspectRatio(1f)
                            if (k.kind == Kind.EQ) EqKey(m, landscape, onPress = { hooks.onPress(calc.secretCandidate()) },
                                onHold = { if (hooks.onHold()) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onRelease = { held -> act(k.action); hooks.onRelease(held) })
                            else Key(k, m, pill = landscape) { act(k.action) }
                        }
                    } }
                }
            }
            Spacer(Modifier.height(if (landscape) 4.dp else 8.dp))
        }
        if (showHistory) HistorySheet(calc, onPick = { calc.load(it); tick++; showHistory = false }, onClear = { calc.clearHistory(); tick++ }, onDismiss = { showHistory = false })
    }
}

@Composable private fun Display(calc: ExprCalc, modifier: Modifier, landscape: Boolean) {
    val expr = prettyExpr(calc.expression)
    val preview = prettyExpr(calc.preview())
    val base: TextUnit = when { expr.length <= 8 -> if (landscape) 56.sp else 80.sp; expr.length <= 11 -> if (landscape) 48.sp else 62.sp; expr.length <= 15 -> if (landscape) 40.sp else 48.sp; else -> 36.sp }
    val blink = rememberInfiniteTransition(label = "cursor").animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Reverse), label = "c")
    Column(modifier, verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
        Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true), verticalAlignment = Alignment.CenterVertically) {
            Text(expr, color = CText, fontSize = base, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(3.dp))
            Box(Modifier.width(2.dp).height(with(androidx.compose.ui.platform.LocalDensity.current) { base.toDp() * 1.1f }).background(CCursor.copy(alpha = blink.value)))
        }
        Text(preview, color = CMuted, fontSize = if (landscape) 24.sp else 32.sp, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.padding(top = 4.dp, end = 5.dp).height(if (landscape) 32.dp else 42.dp))
    }
}

private fun colors(kind: Kind): Pair<Color, Color> = when (kind) {
    Kind.DIGIT -> CDigit to CText; Kind.AC -> CAc to Color(0xFFFFE3D2); Kind.FN -> CFn to COpText; Kind.OP -> COp to COpText; Kind.EQ -> CEq to CEqText
}

/** Circle (or pill) that morphs toward a rounded square while pressed, like the Pixel calculator. */
@Composable private fun morphShape(pressed: Boolean, pill: Boolean): RoundedCornerShape {
    val pctV by animateIntAsState(if (pressed) (if (pill) 30 else 28) else 50, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "m")
    return RoundedCornerShape(pctV)
}

@Composable private fun KeyContent(k: K, fg: Color, small: Boolean) {
    when (k.label) {
        "⌫" -> Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace", tint = fg, modifier = Modifier.size(if (small) 18.dp else 30.dp))
        else -> Text(k.label, color = fg, fontSize = if (small) 15.sp else if (k.label.length > 1) 30.sp else 36.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable private fun Key(k: K, modifier: Modifier, pill: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val (bg, fg) = colors(k.kind)
    val shape = morphShape(pressed, pill)
    Box(modifier.clip(shape).background(if (pressed) lerpColor(bg, Color.White, 0.08f) else bg)
        .clickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) { KeyContent(k, fg, small || pill && k.label.length > 3) }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** '=' looks like the Pixel's cream key, but reports press / hold / release. Cancelled gestures count as a release. */
@Composable private fun EqKey(modifier: Modifier, pill: Boolean, onPress: () -> Unit, onHold: () -> Unit, onRelease: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress); val hold by rememberUpdatedState(onHold); val rel by rememberUpdatedState(onRelease)
    val shape = morphShape(pressed, pill)
    Box(modifier.clip(shape).background(if (pressed) Color(0xFFEDE3D6) else CEq)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(); pressed = true; press()
                var held = false
                val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
                if (up == null && currentEvent.changes.any { it.pressed }) { held = true; hold(); waitForUpOrCancellation() }
                pressed = false; rel(held)
            }
        }, contentAlignment = Alignment.Center) {
        Text("=", color = CEqText, fontSize = 40.sp, fontWeight = FontWeight.Normal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HistorySheet(calc: ExprCalc, onPick: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF221A16)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History", color = CText, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (calc.history.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear", color = CCursor) }
        }
        if (calc.history.isEmpty()) Text("No history yet", color = CMuted, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(calc.history.reversed()) { e ->
                Column(Modifier.fillMaxWidth().clickable { onPick(e.result) }.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
                    Text(prettyExpr(e.expr), color = CMuted, fontSize = 18.sp)
                    Text("= " + prettyExpr(e.result), color = CText, fontSize = 26.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
