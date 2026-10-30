package calc.app

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repo
    private var unlocked by mutableStateOf(false)
    private var session by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(applicationContext)
        setContent {
            CalcTheme {
                AnimatedContent(unlocked, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { open ->
                    if (!open) CalculatorScreen(onSecret = ::tryUnlock)
                    else key(session) {
                        val state = remember { AppState(repo, lifecycleScope, onLock = ::lock) }
                        BackHandler { if (!state.back()) lock() }
                        WalletApp(state)
                    }
                }
            }
        }
    }

    private fun tryUnlock(candidate: String) {
        if (!repo.lock.couldBeCode(candidate)) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { repo.lock.verify(candidate) }
            if (ok) { setSecure(true); session++; unlocked = true }
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
        // Whatever happens (home, recents, screen off, another app), next time we show the calculator.
        if (unlocked && !isChangingConfigurations && System.currentTimeMillis() > LockGuard.externalUntil) { unlocked = false; repo.closeAll() }
    }

    override fun onResume() {
        super.onResume()
        LockGuard.externalUntil = 0
        if (!unlocked) setSecure(false)
    }
}
