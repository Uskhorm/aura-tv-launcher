package tv.aura.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.aura.core.*

/** Temporary lightweight dependencies; package owners replace each implementation during integration. */
class AuraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Aura.config = BootstrapConfigStore()
        Aura.images = BootstrapImagePipeline
        Aura.system = BootstrapSystemBridge(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Aura.images.trimMemory(level)
    }
}

private class BootstrapConfigStore : ConfigStore {
    private val state = MutableStateFlow(AuraConfig())
    override val config: StateFlow<AuraConfig> = state.asStateFlow()
    private val mutex = Mutex()
    override suspend fun update(transform: (AuraConfig) -> AuraConfig) {
        mutex.withLock { state.value = transform(state.value) }
    }
}

private object BootstrapImagePipeline : ImagePipeline {
    override suspend fun bitmap(url: String, w: Int, h: Int, alpha: Boolean): ImageBitmap? = null
    override suspend fun blurred(url: String, level: Int): ImageBitmap? = null
    override suspend fun palette(url: String): AuraPalette? = null
    override fun prefetch(urls: List<String>, w: Int, h: Int) = Unit
    override fun trimMemory(level: Int) = Unit
}

private class BootstrapSystemBridge(private val app: Application) : SystemBridge {
    override val apps: StateFlow<List<InstalledApp>> = MutableStateFlow(emptyList())
    override val nowPlaying: StateFlow<NowPlaying?> = MutableStateFlow(null)
    override fun usageRank(): List<String> = emptyList()
    override fun launch(target: LaunchTarget): Boolean {
        val intent = when (target) {
            is LaunchTarget.App -> app.packageManager.getLeanbackLaunchIntentForPackage(target.pkg)
                ?: app.packageManager.getLaunchIntentForPackage(target.pkg)
            is LaunchTarget.Deep -> Intent(target.action).apply {
                setPackage(target.pkg)
                target.data?.let { data = Uri.parse(it) }
                target.component?.let { component = ComponentName.unflattenFromString(it) }
                target.extras.forEach { (key, value) -> putExtra(key, value) }
            }
        }
        val launched = intent != null && runCatching {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        return launched || (target is LaunchTarget.Deep && target.fallback?.let(::launch) == true)
    }
    override fun quickAction(id: String): Boolean = false
    override fun mediaToggle() = Unit
}
