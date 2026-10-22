package tv.aura.system

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.aura.core.InstalledApp
import tv.aura.core.LaunchTarget
import tv.aura.core.NowPlaying
import tv.aura.core.SystemBridge

data class SystemQuickAction(val id: String, val label: String, val available: Boolean,
    val detail: String? = null)
data class AuraTvInput(val id: String, val label: String, val connected: Boolean)
/** Available RAM change is a system-wide observation, not a guaranteed amount reclaimed by kills. */
data class BoostResult(val beforeAvailableMb: Long, val afterAvailableMb: Long, val requestedPackages: Int,
    val failedPackages: Int) {
    val freedMb: Long get() = (afterAvailableMb - beforeAvailableMb).coerceAtLeast(0)
}

/** App-scoped bridge. Construct once from applicationContext; close only if replacing the instance. */
class AuraSystem(context: Context) : SystemBridge, AutoCloseable {
    private val context = context.applicationContext
    private val pm = this.context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        Log.w(TAG, "System refresh failed: ${error.javaClass.simpleName}")
    })
    private val catalog = SystemAppCatalog(this.context)
    private val ranking = UsageRanking(this.context)
    private val media = AuraMediaSessions(this.context, scope)
    private val refreshMutex = Mutex()
    private val boostMutex = Mutex()
    private val appState = MutableStateFlow<List<InstalledApp>>(emptyList())
    override val apps: StateFlow<List<InstalledApp>> = appState.asStateFlow()
    override val nowPlaying: StateFlow<NowPlaying?> = media.nowPlaying
    private val actionState = MutableStateFlow<List<SystemQuickAction>>(emptyList())
    val quickActions: StateFlow<List<SystemQuickAction>> = actionState.asStateFlow()
    private val boostState = MutableStateFlow<BoostResult?>(null)
    val boostResult: StateFlow<BoostResult?> = boostState.asStateFlow()
    @Volatile private var usage = UsageSnapshot()
    @Volatile private var lastUsageRefresh = 0L
    @Volatile private var lastLaunched: String? = null
    @Volatile private var lastLaunchedAt = 0L
    @Volatile private var closed = false
    /** Optional UI hook, e.g. a focused dock card's makeScaleUpAnimation bundle. */
    @Volatile var launchOptionsProvider: (() -> Bundle?)? = null
    /** Set only while the homeguard AccessibilityService is connected; uses the public global action. */
    @Volatile var powerPanelOpener: (() -> Boolean)? = null
        set(value) { field = value; if (!closed) scope.launch { updateActions() } }
    @Volatile var powerPanelAvailable: (() -> Boolean)? = null
        set(value) { field = value; if (!closed) scope.launch { updateActions() } }
    /** One-shot homeguard bypass for Amazon UI which is hosted in HomeActivity_vNext. */
    @Volatile var beforeAmazonNavigation: (() -> Unit)? = null
    /** Enable only after app-UID/device verification; resolver success does not prove vendor UI works. */
    @Volatile var inputsVerified: Boolean = false
        set(value) { field = value; if (!closed) scope.launch { updateActions() } }
    @Volatile var sleepVerified: Boolean = false
        set(value) { field = value; if (!closed) scope.launch { updateActions() } }
    /** Accessibility GLOBAL_ACTION_LOCK_SCREEN, supplied by the homeguard owner. */
    @Volatile var sleepOpener: (() -> Boolean)? = null
        set(value) { field = value; if (!closed) scope.launch { updateActions() } }
    private var receiverRegistered = false
    private val packagesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.scheme == "package" && !intent.data?.schemeSpecificPart.isNullOrBlank()) refreshApps()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(this.context, packagesReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            true
        }.getOrDefault(false)
        refreshApps()
        scope.launch {
            while (true) {
                delay(60_000)
                refreshUsage()
                updateActions()
            }
        }
    }

    fun refreshApps() { if (!closed) scope.launch { refreshMutex.withLock {
        runCatching { catalog.installed() }.onSuccess { appState.value = it }
        refreshUsage()
        updateActions()
        Log.i(TAG, "Apps refreshed count=${appState.value.size} usageAllowed=${usage.permitted}")
    } } }

    private fun refreshUsage() {
        val launchable = appState.value.map { it.pkg }.filterNot { it in RESUME_EXCLUDED }.toSet()
        usage = ranking.read(launchable)
        lastUsageRefresh = android.os.SystemClock.elapsedRealtime()
    }

    override fun usageRank(): List<String> {
        if (!closed && android.os.SystemClock.elapsedRealtime() - lastUsageRefresh > 60_000) scope.launch {
            refreshMutex.withLock { refreshUsage() }
        }
        return usage.ranked
    }

    val usageAccessGranted: Boolean get() = usage.permitted

    /** Package graphics loading is bounded and dispatched away from the caller's UI thread. */
    suspend fun appArtwork(pkg: String, banner: Boolean = true): ImageBitmap? = withContext(Dispatchers.IO) {
        appState.value.firstOrNull { it.pkg == pkg }?.let { catalog.artwork(it, banner) }
    }

    override fun launch(target: LaunchTarget): Boolean = !closed && runCatching { launchTarget(target, 0) }.getOrDefault(false)

    private fun launchTarget(target: LaunchTarget, depth: Int): Boolean {
        if (depth >= 12) return false
        return when (target) {
            is LaunchTarget.App -> {
                val intent = appIntent(target.pkg)
                val launched = intent != null && start(intent)
                if (launched && target.pkg !in RESUME_EXCLUDED) rememberLaunch(target.pkg)
                launched
            }
            is LaunchTarget.Deep -> {
                val launched = runCatching {
                    val intent = Intent(target.action).setPackage(target.pkg)
                    target.data?.let { intent.data = Uri.parse(it) }
                    target.component?.let { flat ->
                        val component = ComponentName.unflattenFromString(flat) ?: return@runCatching false
                        if (component.packageName != target.pkg) return@runCatching false
                        intent.component = component
                    }
                    target.extras.forEach { (key, value) -> intent.putExtra(key, value) }
                    start(intent)
                }.getOrDefault(false)
                if (launched) {
                    if (target.pkg !in RESUME_EXCLUDED) rememberLaunch(target.pkg)
                    true
                } else target.fallback?.let { launchTarget(it, depth + 1) } ?: false
            }
        }
    }

    private fun rememberLaunch(pkg: String) {
        lastLaunched = pkg
        lastLaunchedAt = System.currentTimeMillis()
        scope.launch { updateActions() }
    }

    private fun resumePackage(): String? {
        val installed = appState.value.map { it.pkg }.toSet()
        val recent = usage.recent.firstOrNull { it in installed }
        val launched = lastLaunched?.takeIf { it in installed }
        return if (lastLaunchedAt > usage.lastUsedAt) launched ?: recent else recent ?: launched
    }

    private fun appIntent(pkg: String): Intent? = runCatching {
        if (pkg == SystemAppCatalog.SETTINGS_PACKAGE) return settingsIntents().firstOrNull { canStart(it) }
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: appState.value.firstOrNull { it.pkg == pkg }?.let {
                ComponentName.unflattenFromString(it.component)?.let { component ->
                    Intent(Intent.ACTION_MAIN).setComponent(component)
                }
            }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun canStart(intent: Intent): Boolean = runCatching {
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        info.enabled && info.applicationInfo.enabled && (info.exported || info.packageName == context.packageName) &&
            (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    private fun start(intent: Intent): Boolean = runCatching {
        if (!canStart(intent)) return false
        if (intent.component?.packageName == AMAZON_LAUNCHER || intent.`package` == AMAZON_LAUNCHER)
            runCatching { beforeAmazonNavigation?.invoke() }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = runCatching { launchOptionsProvider?.invoke() }.getOrNull()
        try { context.startActivity(intent, options) }
        catch (_: IllegalArgumentException) { context.startActivity(intent) }
        true
    }.getOrDefault(false)

    override fun quickAction(id: String): Boolean {
        if (closed) return false
        return when (id) {
            "resume" -> resumePackage()?.let { launch(LaunchTarget.App(it)) } ?: false
            "vpn" -> launch(LaunchTarget.App(VPN_PACKAGE))
            "boost" -> {
                if (context.checkSelfPermission("android.permission.KILL_BACKGROUND_PROCESSES") != PackageManager.PERMISSION_GRANTED) false
                else { scope.launch { boost() }; true }
            }
            "sleep" -> sleepVerified && runCatching { sleepOpener?.invoke() ?: false }.getOrDefault(false)
            "inputs" -> inputsVerified && actionIntents(id).any { start(it) }
            else -> actionIntents(id).any { start(it) }
        }
    }

    override fun mediaToggle() { if (!closed) media.toggle() }

    private fun settingsIntents() = listOf(
        Intent("amazon.intent.action.MAINSETTINGS").setComponent(ComponentName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.MainSettingsActivity")),
        Intent(Settings.ACTION_SETTINGS).setComponent(ComponentName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.SettingsActivity")),
    )

    private fun actionIntents(id: String): List<Intent> = when (id) {
        "settings" -> settingsIntents()
        "wifi" -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(SystemAppCatalog.SETTINGS_PACKAGE))
        "bluetooth" -> listOf(Intent("com.amazon.device.settings.action.CONTROLLERS").setPackage(SystemAppCatalog.SETTINGS_PACKAGE),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "inputs" -> listOf(Intent("com.amazon.tv.action.NAVIGATE_TO_INPUTS").setPackage(AMAZON_LAUNCHER))
        "display" -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(SystemAppCatalog.SETTINGS_PACKAGE)) + settingsIntents()
        // QuickSettingsProxyActivity is SDK>=34 only; API28 requires the accessibility power dialog hook.
        // Amazon's SHOW_TEXT_SEARCH is signature protected. ACTION_SEARCH is offered only if permitted.
        "search" -> listOf(Intent(Intent.ACTION_SEARCH).setPackage(AMAZON_LAUNCHER))
        else -> emptyList()
    }

    private fun updateActions() {
        actionState.value = listOf("settings", "wifi", "bluetooth", "inputs", "display", "sleep", "boost", "vpn", "resume", "search").map { id ->
            val available = when (id) {
                "boost" -> context.checkSelfPermission("android.permission.KILL_BACKGROUND_PROCESSES") == PackageManager.PERMISSION_GRANTED
                "vpn" -> appIntent(VPN_PACKAGE)?.let { canStart(it) } == true
                "resume" -> resumePackage() != null
                "inputs" -> inputsVerified && actionIntents(id).any { canStart(it) }
                "sleep" -> sleepVerified && sleepOpener != null && runCatching { powerPanelAvailable?.invoke() ?: false }.getOrDefault(false)
                else -> actionIntents(id).any { canStart(it) }
            }
            val directDisplay = id == "display" && canStart(Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(SystemAppCatalog.SETTINGS_PACKAGE))
            SystemQuickAction(id, when (id) {
                "settings" -> "Settings"
                "wifi" -> "Wi-Fi"
                "bluetooth" -> "Controllers & Bluetooth"
                "inputs" -> "Inputs"
                "display" -> if (directDisplay) "Display & sound" else "Display · Settings"
                "sleep" -> "Sleep"
                "boost" -> "Boost"
                "vpn" -> "Mullvad VPN"
                "resume" -> "Resume last app"
                else -> "Search"
            }, available, when (id) {
                "display" -> if (directDisplay) null else "Open Settings, then Display & Sounds"
                "sleep" -> null
                "boost" -> "Close background apps; measured RAM change appears after completion"
                "vpn" -> "Opens Mullvad; connection stays under your control"
                else -> null
            })
        }
    }

    suspend fun tvInputs(): List<AuraTvInput> = withContext(Dispatchers.IO) { runCatching {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return@withContext emptyList()
        manager.tvInputList.filter { it.isPassthroughInput }.map {
            AuraTvInput(it.id, it.loadCustomLabel(context)?.toString() ?: it.loadLabel(context).toString(),
                manager.getInputState(it.id) != TvInputManager.INPUT_STATE_DISCONNECTED)
        }
    }.getOrDefault(emptyList()) }

    fun switchInput(id: String): Boolean = !closed && runCatching {
        val manager = context.getSystemService(TvInputManager::class.java)
        if (manager?.getTvInputInfo(id)?.isPassthroughInput != true) return false
        start(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)))
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private suspend fun boost() = boostMutex.withLock {
        boostState.value = null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return@withLock
        val before = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
        val protected = mutableSetOf(context.packageName, VPN_PACKAGE, "tv.glass.launcher", "com.spocky.projengmenu",
            "io.github.toolicious.homeonfire")
        nowPlaying.value?.pkg?.let { protected += it }
        // Running process visibility is restricted on newer releases; retain last foreground app too.
        resumePackage()?.let { protected += it }
        manager.runningAppProcesses.orEmpty().filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE }
            .forEach { protected += it.pkgList.orEmpty() }
        for (key in listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "enabled_notification_listeners")) {
            runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()?.split(':')?.forEach {
                ComponentName.unflattenFromString(it)?.packageName?.let { pkg -> protected += pkg }
            }
        }
        var requested = 0
        var failed = 0
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList()).filter {
            it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                it.packageName !in protected && !it.packageName.startsWith("com.amazon.")
        }.forEach {
            requested++
            if (runCatching { manager.killBackgroundProcesses(it.packageName) }.isFailure) failed++
        }
        delay(750)
        val after = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
        boostState.value = BoostResult(before / 1_048_576, after / 1_048_576, requested, failed)
        Log.i(TAG, "Boost beforeMb=${before / 1_048_576} afterMb=${after / 1_048_576} requested=$requested failed=$failed")
    }

    override fun close() {
        if (closed) return
        closed = true
        if (receiverRegistered) runCatching { context.unregisterReceiver(packagesReceiver) }
        receiverRegistered = false
        launchOptionsProvider = null
        powerPanelOpener = null
        sleepOpener = null
        powerPanelAvailable = null
        beforeAmazonNavigation = null
        media.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AuraSystem"
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val VPN_PACKAGE = "net.mullvad.mullvadvpn"
        private val RESUME_EXCLUDED = setOf("com.amazon.tv.settings.v2", AMAZON_LAUNCHER, "tv.glass.launcher",
            "com.spocky.projengmenu", "io.github.toolicious.homeonfire")
        fun scaleUpOptions(source: View, width: Int = source.width, height: Int = source.height): Bundle? =
            runCatching { ActivityOptions.makeScaleUpAnimation(source, 0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1)).toBundle() }.getOrNull()
    }
}
