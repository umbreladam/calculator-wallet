package calc.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import calc.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the wallet open the system file picker without being locked by onStop (max 2 min). */
object LockGuard { @Volatile var externalUntil = 0L; fun allowExternal() { externalUntil = System.currentTimeMillis() + 120_000 } }

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var repo: Repo
    private var unlocked by mutableStateOf(false)
    private var session by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(applicationContext)
        voice = VoiceListener(this)
        setContent {
            CalcTheme {
                AnimatedContent(unlocked, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { open ->
                    if (!open) CalculatorScreen(hooks)
                    else key(session) {
                        val state = remember { AppState(repo, lifecycleScope, onLock = ::lock) }
                        BackHandler { if (!state.back()) lock() }
                        WalletApp(state)
                    }
                }
            }
        }
    }

    private fun open() { setSecure(true); session++; unlocked = true }

    private lateinit var voice: VoiceListener
    private var codeCheck: Deferred<Boolean>? = null
    private var listening = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* next hold will use it */ }

    /** Code + held '=' + spoken passphrase (or code + '=' when voice is turned off in Settings). */
    private val hooks = object : EqualsHooks {
        override fun onPress(candidate: String?) {
            codeCheck = if (candidate != null && repo.lock.couldBeCode(candidate))
                lifecycleScope.async(Dispatchers.Default) { repo.lock.verify(candidate) } else null
        }
        override fun onHold(): Boolean {
            val cc = codeCheck ?: return false
            if (!repo.settings.voiceRequired) return false
            if (cc.isCompleted && !cc.getCompleted()) return false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // only ask once the code itself is confirmed, so ordinary maths never triggers the prompt
                if (cc.isCompleted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                return false
            }
            listening = voice.start()
            return listening
        }
        override fun onRelease(held: Boolean) {
            val cc = codeCheck; codeCheck = null
            if (listening) {
                listening = false
                voice.stop { alts ->
                    if (cc == null || alts.isEmpty()) return@stop
                    lifecycleScope.launch {
                        val ok = cc.await() && withContext(Dispatchers.Default) { repo.voice.verify(alts) }
                        if (ok) open()
                    }
                }
            } else if (!held && cc != null && !repo.settings.voiceRequired) {
                lifecycleScope.launch { if (cc.await()) open() }
            }
        }
    }

    fun lock() { unlocked = false; setSecure(false) }

    private fun setSecure(on: Boolean) {
        if (BuildConfig.ALLOW_SCREENSHOTS) return
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        super.onPause()
        // keep the recents thumbnail blank while the wallet is visible
        if (unlocked && !BuildConfig.ALLOW_SCREENSHOTS) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        if (listening) { listening = false; voice.cancel() }
        // Whatever happens (home, recents, screen off, another app), next time we show the calculator.
        if (unlocked && !isChangingConfigurations && System.currentTimeMillis() > LockGuard.externalUntil) { unlocked = false; repo.closeAll() }
    }

    override fun onResume() {
        super.onResume()
        LockGuard.externalUntil = 0
        if (!unlocked) setSecure(false)
    }
}
