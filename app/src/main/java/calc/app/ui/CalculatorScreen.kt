package calc.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import calc.core.Calculator

private val KeyNum = Color(0xFF2A2A2E)
private val KeyFn = Color(0xFF4B4B52)
private val KeyOp = Color(0xFFFF9F0A)

/** Hooks for the '=' key. A press reports the secret-code candidate; a hold (≥ [HOLD_MS]) and the release follow. */
interface EqualsHooks {
    fun onPress(candidate: String?)
    /** Returns true if listening started (a subtle haptic tick is given). */
    fun onHold(): Boolean
    fun onRelease(held: Boolean)
}
const val HOLD_MS = 450L

/** A plain-looking calculator. '=' always evaluates on release; [hooks] decide whether anything else happens. */
@Composable fun CalculatorScreen(hooks: EqualsHooks) {
    val calc = remember { Calculator() }
    var tick by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun act(f: Calculator.() -> Unit) { calc.f(); tick++; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Spacer(Modifier.weight(1f))
        key(tick) {
            Text(calc.history, color = Color(0xFF8A8A8E), fontSize = 22.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            val d = calc.display
            val size = when { d.length <= 7 -> 84.sp; d.length <= 9 -> 68.sp; d.length <= 12 -> 52.sp; else -> 40.sp }
            Text(pretty(d), color = Color.White, fontSize = size, fontWeight = FontWeight.Light, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))
        }
        val op = remember(tick) { calc.pendingOp }
        val rows: List<List<Triple<String, Color, () -> Unit>>> = listOf(
            listOf(Triple(if (calc.isAllClear || !canC(calc)) "AC" else "C", KeyFn) { act { clear() } }, Triple("±", KeyFn) { act { negate() } }, Triple("%", KeyFn) { act { percent() } }, Triple("÷", KeyOp) { act { op(Calculator.Op.DIV) } }),
            listOf(Triple("7", KeyNum) { act { digit('7') } }, Triple("8", KeyNum) { act { digit('8') } }, Triple("9", KeyNum) { act { digit('9') } }, Triple("×", KeyOp) { act { op(Calculator.Op.MUL) } }),
            listOf(Triple("4", KeyNum) { act { digit('4') } }, Triple("5", KeyNum) { act { digit('5') } }, Triple("6", KeyNum) { act { digit('6') } }, Triple("−", KeyOp) { act { op(Calculator.Op.SUB) } }),
            listOf(Triple("1", KeyNum) { act { digit('1') } }, Triple("2", KeyNum) { act { digit('2') } }, Triple("3", KeyNum) { act { digit('3') } }, Triple("+", KeyOp) { act { op(Calculator.Op.ADD) } }),
            listOf(Triple("⌫", KeyNum) { act { backspace() } }, Triple("0", KeyNum) { act { digit('0') } }, Triple(".", KeyNum) { act { dot() } },
                Triple("=", KeyOp) { act { equals() } }),
        )
        key(tick) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, color, onClick) ->
                        val selected = color == KeyOp && label != "=" && op?.sym == label
                        if (label == "=") EqKey(Modifier.weight(1f), onPress = { hooks.onPress(calc.secretCandidate()) },
                            onHold = { if (hooks.onHold()) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onRelease = { held -> onClick(); hooks.onRelease(held) })
                        else CalcKey(label, if (selected) Color.White else color, if (selected) KeyOp else Color.White, Modifier.weight(1f), onClick)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun canC(c: Calculator) = c.display != "0"

private fun pretty(d: String): String {
    if (d == "Error" || d.contains('e')) return d
    val neg = d.startsWith("-"); val body = d.removePrefix("-")
    val int = body.substringBefore('.'); val frac = if (body.contains('.')) "." + body.substringAfter('.') else ""
    val grouped = int.reversed().chunked(3).joinToString(",").reversed()
    return (if (neg) "-" else "") + grouped + frac
}

@Composable private fun CalcKey(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, label = "k")
    Box(modifier.aspectRatio(1f).scale(s).clip(CircleShape).background(if (pressed) bg.copy(alpha = 0.7f) else bg)
        .clickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        if (label == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, "delete", tint = fg, modifier = Modifier.size(28.dp))
        else Text(label, color = fg, fontSize = if (label.length > 1) 28.sp else 36.sp, fontWeight = FontWeight.Normal)
    }
}

/** '=' looks and animates like any key, but reports press / hold / release. Cancelled gestures count as a release. */
@Composable private fun EqKey(modifier: Modifier, onPress: () -> Unit, onHold: () -> Unit, onRelease: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, label = "k")
    val press by rememberUpdatedState(onPress); val hold by rememberUpdatedState(onHold); val rel by rememberUpdatedState(onRelease)
    Box(modifier.aspectRatio(1f).scale(s).clip(CircleShape).background(if (pressed) KeyOp.copy(alpha = 0.7f) else KeyOp)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(); pressed = true; press()
                var held = false
                val up = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
                if (up == null && currentEvent.changes.any { it.pressed }) { held = true; hold(); waitForUpOrCancellation() }
                pressed = false; rel(held)
            }
        }, contentAlignment = Alignment.Center) {
        Text("=", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Normal)
    }
}
