package calc.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens while '=' is held. Prefers on-device recognition. Audio and transcripts stay in memory only:
 * nothing is logged, stored or sent by this app, and results are handed straight to the hash check.
 */
class VoiceListener(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    private val alts = LinkedHashSet<String>()
    private var done: ((List<String>) -> Unit)? = null
    private var finished = false
    private val main = Handler(Looper.getMainLooper())

    val onDevice: Boolean get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    fun available() = onDevice || SpeechRecognizer.isRecognitionAvailable(ctx)

    private var session = 0
    fun start(): Boolean {
        cancel(); session++
        alts.clear(); finished = false; done = null
        val r = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else return false
        } catch (e: Exception) { return false }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) { collect(b); finish() }
            override fun onPartialResults(b: Bundle?) { collect(b) }
            override fun onError(error: Int) { finish() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        return try { r.startListening(i); true } catch (e: Exception) { cancel(); false }
    }

    private fun collect(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.forEach { if (it.isNotBlank()) alts.add(it) } }

    private fun finish() {
        if (finished) return
        finished = true
        val cb = done; val out = alts.toList(); alts.clear()
        main.post { destroy() }
        if (cb != null) cb(out) else pending = out
    }
    private var pending: List<String>? = null

    /** Stop on release; [onDone] gets the n-best/partial texts (possibly empty) within ~2.5 s. */
    fun stop(onDone: (List<String>) -> Unit) {
        val p = pending
        if (finished || rec == null) { pending = null; onDone(p ?: emptyList()); return }
        done = onDone
        try { rec?.stopListening() } catch (e: Exception) { finish() }
        val sess = session
        main.postDelayed({ if (sess == session) finish() }, 2500)
    }

    fun cancel() { finished = true; done = null; pending = null; alts.clear(); destroy() }
    private fun destroy() { try { rec?.destroy() } catch (e: Exception) {}; rec = null }
}
