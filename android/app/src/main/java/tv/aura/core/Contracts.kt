package tv.aura.core

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

// ---------- Content shown in the Hero / Top Shelf ----------
@Immutable
data class HeroItem(
    val id: String,                 // stable, unique per provider (e.g. "yt:VIDEOID", "nuvio:tt0111161")
    val appPackage: String,         // dock app this item belongs to, e.g. "com.nuvio.tv"
    val title: String,
    val subtitle: String? = null,   // "2024 · Action · ★ 8.1" / "ANI News · 2 h ago"
    val description: String? = null,
    val backdropUrl: String? = null,// landscape, ideally ≥1280 px wide
    val logoUrl: String? = null,    // transparent title logo (PNG/SVG→PNG), optional
    val posterUrl: String? = null,
    val badge: String? = null,      // "LIVE", "NEW", "TODAY 19:30", "CONTINUE"
    val accentArgb: Int? = null,    // optional brand/palette hint
    val launch: LaunchTarget,
)

sealed interface LaunchTarget {
    data class App(val pkg: String) : LaunchTarget
    data class Deep(
        val pkg: String,
        val action: String = "android.intent.action.VIEW",
        val data: String? = null,
        val component: String? = null,           // "pkg/.Activity" if needed
        val extras: Map<String, String> = emptyMap(),
        val fallback: LaunchTarget? = App(pkg),   // used if the deep link fails to resolve/start
    ) : LaunchTarget
}

interface HeroProvider {
    val id: String                    // "youtube", "nuvio", "prime", "sports"
    val appPackages: Set<String>      // dock apps this provider powers
    val refreshIntervalMs: Long
    /** Off main thread. Network allowed. Must return within ~8 s; return cached/last-good data on failure, never throw. */
    suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem>
}

// ---------- Images (sol-3) ----------
@Immutable data class AuraPalette(val dominant: Int, val vibrant: Int, val muted: Int, val onDark: Int)

interface ImagePipeline {
    /** Decoded to fit w×h (never larger), RGB_565 unless alpha needed. Memory + disk cached. */
    suspend fun bitmap(url: String, w: Int, h: Int, alpha: Boolean = false): androidx.compose.ui.graphics.ImageBitmap?
    /** Pre-blurred copy (computed once, cached). level 1 = light (glass), 2 = medium, 3 = heavy (background). */
    suspend fun blurred(url: String, level: Int): androidx.compose.ui.graphics.ImageBitmap?
    suspend fun palette(url: String): AuraPalette?
    fun prefetch(urls: List<String>, w: Int, h: Int)
    fun trimMemory(level: Int)
}

// ---------- Config (son-5) ----------
@Immutable data class YtChannel(val id: String, val name: String)   // id = "UC…" channel id

@Immutable
data class AuraConfig(
    val dockOrder: List<String> = listOf(
        "io.gh.reisxd.tizentube.cobalt", "com.nuvio.tv", "com.amazon.firebat", "app.aksports.live", "com.spotify.tv.android"),
    val hidden: Set<String> = emptySet(),
    val renamed: Map<String, String> = mapOf("io.gh.reisxd.tizentube.cobalt" to "YouTube"),
    val youtubeChannels: List<YtChannel> = emptyList(),   // sol-5 supplies the household default list
    val quickActions: List<String> = listOf("settings", "wifi", "inputs", "boost", "bluetooth", "display", "sleep", "vpn"),
    val heroAutoAdvanceMs: Long = 9_000,
    val motion: Motion = Motion.Full,                     // Full | Reduced
    val clock24: Boolean = true,
    val showWeather: Boolean = true,
    val city: String = "London", val lat: Double = 51.5072, val lon: Double = -0.1276,
    val screensaverMinutes: Int = 10,
    val clickSounds: Boolean = true,
    val sportsFocus: List<String> = listOf("football", "f1"),
    val heroDisabled: Set<String> = emptySet(),
)
enum class Motion { Full, Reduced }

interface ConfigStore {
    val config: StateFlow<AuraConfig>
    suspend fun update(transform: (AuraConfig) -> AuraConfig)
}

// ---------- System (sol-6) ----------
@Immutable
data class InstalledApp(val pkg: String, val label: String, val component: String, val leanback: Boolean, val system: Boolean)

@Immutable
data class NowPlaying(
    val pkg: String, val title: String?, val subtitle: String?, val artUrl: String?,
    val art: androidx.compose.ui.graphics.ImageBitmap?, val playing: Boolean,
    val positionMs: Long?, val durationMs: Long?,
)

interface SystemBridge {
    val apps: StateFlow<List<InstalledApp>>           // refreshed on package add/remove/change
    val nowPlaying: StateFlow<NowPlaying?>            // needs NotificationListener (granted via adb by sol-8)
    fun usageRank(): List<String>                     // packages by recent usage (PACKAGE_USAGE_STATS via appops)
    fun launch(target: LaunchTarget): Boolean         // resolves Deep → fallback; returns false if nothing started
    fun quickAction(id: String): Boolean              // "settings","wifi","bluetooth","inputs","display","sleep","boost","vpn","resume",...
    fun mediaToggle()
}

// ---------- Service locator (sol-1) ----------
object Aura {
    lateinit var config: ConfigStore
    lateinit var images: ImagePipeline
    lateinit var system: SystemBridge
    val heroProviders: MutableList<HeroProvider> = mutableListOf()
}

