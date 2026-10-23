package ru.homescreen.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import ru.homescreen.tv.data.Net
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    /** Bumped on every Home press while we are already in front: the screen goes back to the hero of Главная. */
    private val homePresses = mutableIntStateOf(0)
    /** Bumped by the remote's Input button ([KeysService]): the Inputs menu opens. */
    private val inputsPresses = mutableIntStateOf(0)
    /** Bumped by the remote's microphone button ([Assistant]): search opens, listening. */
    private val voicePresses = mutableIntStateOf(0)

    /** ViMu reports where playback stopped, voice input what was said: both come back as activity results. */
    private var onResult: ((Int, Intent?) -> Unit)? = null
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        onResult?.invoke(r.resultCode, r.data)
        onResult = null
    }

    fun forResult(intent: Intent, done: (Int, Intent?) -> Unit): Boolean = try {
        onResult = done
        launcher.launch(intent)
        true
    } catch (e: Exception) {
        onResult = null
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        Showy.init(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(Net.client)
                .crossfade(250)
                // Posters and backdrops have no alpha: half the memory on a 2.4 GB TV.
                .allowRgb565(true)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                .build(),
        )
        vm.start()
        when (intent?.action) {
            ACTION_INPUTS -> inputsPresses.intValue++
            ACTION_VOICE -> voicePresses.intValue++
        }
        setContent { HomeScreen(vm, homePresses.intValue, inputsPresses.intValue, voicePresses.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            Intent.ACTION_MAIN -> homePresses.intValue++
            ACTION_INPUTS -> inputsPresses.intValue++
            ACTION_VOICE -> voicePresses.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        KeysService.ensureEnabled(this)
        vm.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.voice.stop()
        vm.saveSoon()
    }

    companion object {
        const val ACTION_INPUTS = "ru.homescreen.tv.action.INPUTS"
        const val ACTION_VOICE = "ru.homescreen.tv.action.VOICE"
    }

    @Deprecated("Home has nowhere to go back to")
    override fun onBackPressed() {
        // The launcher is the bottom of the stack; Back is handled inside the screen.
    }
}
