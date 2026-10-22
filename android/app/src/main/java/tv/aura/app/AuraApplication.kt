package tv.aura.app

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.aura.core.*
import tv.aura.image.AuraImages
import tv.aura.system.AuraSystem
import tv.aura.content.youtube.DefaultYouTubeChannels
import tv.aura.content.youtube.YouTubeHeroProvider
import tv.aura.content.sports.SportsHeroProvider
import tv.aura.content.nuvio.NuvioHeroProvider
import tv.aura.content.prime.PrimeHeroProvider
import tv.aura.homeguard.HomeGuard

/** App-scoped services, registered before the first Activity starts. */
class AuraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Aura.config = BootstrapConfigStore()
        Aura.images = AuraImages(this)
        Aura.system = AuraSystem(applicationContext).apply {
            beforeAmazonNavigation = { HomeGuard.allowAmazonHome() }
            powerPanelOpener = { HomeGuard.showPowerDialog() }
            powerPanelAvailable = { HomeGuard.status().connected }
            sleepOpener = { HomeGuard.lockScreen() }
        }
        Aura.heroProviders.clear()
        Aura.heroProviders += YouTubeHeroProvider()
        Aura.heroProviders += SportsHeroProvider()
        Aura.heroProviders += NuvioHeroProvider()
        Aura.heroProviders += PrimeHeroProvider()
        HomeGuard.initialize(this)
    }

}

private class BootstrapConfigStore : ConfigStore {
    private val state = MutableStateFlow(AuraConfig(youtubeChannels = DefaultYouTubeChannels.channels))
    override val config: StateFlow<AuraConfig> = state.asStateFlow()
    private val mutex = Mutex()
    override suspend fun update(transform: (AuraConfig) -> AuraConfig) {
        mutex.withLock { state.value = transform(state.value) }
    }
}



