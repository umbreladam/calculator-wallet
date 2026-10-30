package calc.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import calc.core.ScanCollector
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Camera QR scanner (CameraX + ZXing, no Google Play services). Handles animated UR and BBQr via [ScanCollector].
 * [onDone] receives the final state (DoneUr / DoneBbqr / DoneText).
 */
@Composable fun QrScanner(modifier: Modifier = Modifier, onDone: (ScanCollector.State) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val collector = remember { ScanCollector() }
    var progress by remember { mutableStateOf<ScanCollector.State>(ScanCollector.State.Idle) }
    var finished by remember { mutableStateOf(false) }
    val exec = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black).border(2.dp, Orange.copy(alpha = 0.6f), RoundedCornerShape(24.dp))) {
            if (!granted) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission needed", color = Muted)
                    TextButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            } else AndroidView(factory = { c ->
                val pv = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    val reader = QRCodeReader()
                    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                    val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(1280, 960))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(exec) { img ->
                        try {
                            if (!finished) {
                                val plane = img.planes[0]; val buf = plane.buffer
                                val data = ByteArray(buf.remaining()); buf.get(data)
                                val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                                val text = try { reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text } catch (e: Exception) { null } finally { reader.reset() }
                                if (text != null) {
                                    val st = try { collector.feed(text) } catch (e: Exception) { ScanCollector.State.Error(e.message ?: "error") }
                                    pv.post {
                                        progress = st
                                        if (!finished && (st is ScanCollector.State.DoneUr || st is ScanCollector.State.DoneBbqr || st is ScanCollector.State.DoneText)) { finished = true; onDone(st) }
                                        if (st is ScanCollector.State.Error) collector.reset()
                                    }
                                }
                            }
                        } finally { img.close() }
                    }
                    try { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (e: Exception) { }
                }, ContextCompat.getMainExecutor(c))
                pv
            }, modifier = Modifier.fillMaxSize(), onRelease = { runCatching { ProcessCameraProvider.getInstance(it.context).get().unbindAll() } })
        }
        Spacer(Modifier.height(12.dp))
        when (val p = progress) {
            is ScanCollector.State.Progress -> {
                LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Orange, trackColor = Surface2)
                Text(p.label, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            is ScanCollector.State.Error -> Text(p.message, color = Red)
            else -> Text("Point at a QR code. Animated UR and BBQr are supported.", color = Muted)
        }
    }
}
