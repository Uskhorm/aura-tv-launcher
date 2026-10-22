package tv.aura.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.aura.core.Aura
import tv.aura.core.AuraConfig
import tv.aura.core.HeroItem
import tv.aura.core.HeroProvider

enum class UiMode { Home, Expanded, Control, Edit, Sheet, Settings, Ambient }

/** Owns content work independently of composition and cancels it when the Activity is destroyed. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _focusedApp = MutableStateFlow<String?>(null)
    val focusedApp: StateFlow<String?> = _focusedApp.asStateFlow()
    private val _heroItems = MutableStateFlow<Map<String, List<HeroItem>>>(emptyMap())
    val heroItems: StateFlow<Map<String, List<HeroItem>>> = _heroItems.asStateFlow()
    private val _mode = MutableStateFlow(UiMode.Home)
    val mode: StateFlow<UiMode> = _mode.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private var refreshJob: Job? = null
    private class RefreshState {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var nextLoadAt = 0L
    }
    private val refreshStates = mutableMapOf<String, RefreshState>()

    init {
        viewModelScope.launch { delay(6_000L); _loaded.value = true }
        refreshProviders()
    }

    fun focusApp(pkg: String?) {
        if (_focusedApp.value == pkg) return
        _focusedApp.value = pkg
        prefetch(_heroItems.value[pkg].orEmpty())
    }

    fun setMode(mode: UiMode) { _mode.value = mode }

    fun resetHome(pkg: String? = Aura.config.config.value.dockOrder.firstOrNull()) {
        focusApp(pkg)
        _mode.value = UiMode.Home
    }

    /** Called on return to Home; a suspended/delayed schedule should never keep stale cards visible. */
    fun refreshIfStale() {
        val now = SystemClock.elapsedRealtime()
        refreshStates.values.filter { now >= it.nextLoadAt }.forEach { it.wake.trySend(Unit) }
    }

    /** Call after changing the provider registry; owners register before the first Activity starts. */
    fun refreshProviders() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Reordering the dock or changing the clock must not restart network requests.
            Aura.config.config.map { Triple(it.youtubeChannels, it.sportsFocus, it.heroDisabled) }.distinctUntilChanged().collectLatest {
                supervisorScope {
                    refreshStates.clear()
                    val providers = Aura.heroProviders.toList().filter { it.id !in Aura.config.config.value.heroDisabled }
                    val activePackages = providers.flatMap { it.appPackages }.toSet()
                    _heroItems.update { it.filterKeys(activePackages::contains) }
                    providers.forEach { provider ->
                        val state = RefreshState()
                        refreshStates[provider.id] = state
                        launch {
                            var failures = 0
                            while (true) {
                                val remaining = state.nextLoadAt - SystemClock.elapsedRealtime()
                                if (remaining > 0) withTimeoutOrNull(remaining) { state.wake.receive() }
                                if (SystemClock.elapsedRealtime() < state.nextLoadAt) continue
                                val success = refresh(provider, Aura.config.config.value)
                                failures = if (success) 0 else failures + 1
                                val interval = provider.refreshIntervalMs.coerceAtLeast(30_000L)
                                val wait = if (success) interval else providerRetryDelayMs(interval, failures)
                                state.nextLoadAt = SystemClock.elapsedRealtime() + wait
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun refresh(provider: HeroProvider, config: AuraConfig): Boolean {
        val items = try {
            withTimeoutOrNull(8_000L) {
                withContext(Dispatchers.IO) { provider.load(getApplication(), config) }
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        _loaded.value = true
        // A failed/offline refresh keeps the last good cards visible.
        if (items.isEmpty()) return false
        val byPackage = items.filter { it.appPackage in provider.appPackages }.groupBy { it.appPackage }
        if (byPackage.isEmpty()) return false
        _heroItems.update { it + byPackage }
        prefetch(byPackage[_focusedApp.value].orEmpty())
        return true
    }

    private fun prefetch(items: List<HeroItem>) {
        val urls = items.take(3).mapNotNull { it.backdropUrl }.distinct()
        if (urls.isNotEmpty()) Aura.images.prefetch(urls, 1280, 720)
        val logos = items.take(2).mapNotNull { it.logoUrl }.distinct()
        if (logos.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            logos.forEach { Aura.images.bitmap(it, 640, 240, alpha = true) }
        }
    }
}

internal fun providerRetryDelayMs(refreshIntervalMs: Long, consecutiveFailures: Int): Long {
    val retry = when {
        consecutiveFailures <= 1 -> 30_000L
        consecutiveFailures == 2 -> 60_000L
        consecutiveFailures == 3 -> 120_000L
        else -> 300_000L
    }
    return minOf(refreshIntervalMs.coerceAtLeast(30_000L), retry)
}

internal fun Mode.toUiMode(): UiMode = when (this) {
    Mode.Home -> UiMode.Home
    Mode.Shelf -> UiMode.Expanded
    Mode.Jiggle -> UiMode.Edit
    Mode.Sheet -> UiMode.Sheet
    Mode.Control -> UiMode.Control
    Mode.Settings, Mode.CityInput -> UiMode.Settings
    Mode.Saver -> UiMode.Ambient
}
