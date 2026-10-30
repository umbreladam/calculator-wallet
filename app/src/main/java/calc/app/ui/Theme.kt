package calc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Orange = Color(0xFFF7931A)
val Bg = Color(0xFF0E0E10)
val Surface1 = Color(0xFF17171B)
val Surface2 = Color(0xFF212127)
val Muted = Color(0xFF8E8E98)
val Green = Color(0xFF3DDC97)
val Red = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Orange, onPrimary = Color.Black, secondary = Orange, background = Bg, surface = Surface1,
    surfaceVariant = Surface2, onSurface = Color(0xFFF2F2F5), onBackground = Color(0xFFF2F2F5), onSurfaceVariant = Muted,
    surfaceContainer = Surface1, surfaceContainerHigh = Surface2, surfaceContainerHighest = Color(0xFF2A2A31), surfaceContainerLow = Surface1,
    error = Red, outline = Color(0xFF3A3A42), outlineVariant = Color(0xFF2A2A31),
)

@Composable fun CalcTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

val Mono = FontFamily.Monospace

object Fmt {
    fun btc(sats: Long, hide: Boolean, satsUnit: Boolean = false): String {
        if (hide) return "••••••"
        if (satsUnit) return "%,d sats".format(sats)
        val neg = sats < 0; val a = Math.abs(sats)
        val s = "%d.%08d".format(a / 100_000_000, a % 100_000_000)
        val pretty = s.substring(0, s.length - 6) + " " + s.substring(s.length - 6, s.length - 3) + " " + s.substring(s.length - 3)
        return (if (neg) "-" else "") + "₿ " + pretty
    }
    fun gbp(sats: Long, price: Double?, hide: Boolean): String? = price?.let { if (hide) "£••••" else "£%,.2f".format(sats / 1e8 * it) }
    fun shortId(s: String) = if (s.length > 20) s.take(10) + "…" + s.takeLast(8) else s
    fun date(t: Long?): String = t?.let { java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK).format(java.util.Date(it * 1000)) } ?: "Pending"
    fun groupAddr(a: String) = a.chunked(4).joinToString(" ")
}

@Composable fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).padding(18.dp), content = content)

@Composable fun SectionLabel(t: String) = Text(t.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(vertical = 8.dp))

@Composable fun Pill(text: String, color: Color = Orange) =
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))

@Composable fun WarnBox(text: String, color: Color = Orange) =
    Text(text, color = color, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(12.dp))
