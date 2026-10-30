package calc.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

fun qrBitmap(text: String, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.L): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ec))
    val w = m.width; val h = m.height; val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable fun QrImage(text: String, modifier: Modifier = Modifier, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M) {
    val bmp = remember(text) { qrBitmap(text, ec) }
    Box(modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp)) {
        Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
    }
}

/** Animated QR: [next] is called for each frame (UR fountain parts are infinite, BBQr parts cycle). */
@Composable fun AnimatedQr(key: Any, frameMs: Long, next: () -> String, modifier: Modifier = Modifier) {
    var frame by remember(key) { mutableStateOf(next()) }
    LaunchedEffect(key, frameMs) { while (true) { delay(frameMs); frame = next() } }
    QrImage(frame, modifier, ErrorCorrectionLevel.L)
}
