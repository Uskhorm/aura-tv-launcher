package tv.aura.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private var refreshJob: Job? = null

    init { refreshProviders() }

    fun focusApp(pkg: String?) {
        if (_focusedApp.value == pkg) return
        _focusedApp.value = pkg
        prefetch(_heroItems.value[pkg].orEmpty())
    }

    fun setMode(mode: UiMode) { _mode.value = mode }

    fun resetHome() {
        _focusedApp.value = null
        _mode.value = UiMode.Home
    }

    /** Call after changing the provider registry; owners register before the first Activity starts. */
    fun refreshProviders() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            Aura.config.config.collectLatest { config ->
                supervisorScope {
                    Aura.heroProviders.toList().forEach { provider ->
                        launch {
                            while (true) {
                                refresh(provider, config)
                                delay(provider.refreshIntervalMs.coerceAtLeast(30_000L))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun refresh(provider: HeroProvider, config: AuraConfig) {
        val items = try {
            withTimeoutOrNull(8_000L) {
                withContext(Dispatchers.IO) { provider.load(getApplication(), config) }
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        // A failed/offline refresh keeps the last good cards visible.
        if (items.isEmpty()) return
        val byPackage = items.filter { it.appPackage in provider.appPackages }.groupBy { it.appPackage }
        _heroItems.update { it + byPackage }
        prefetch(byPackage[_focusedApp.value].orEmpty())
    }

    private fun prefetch(items: List<HeroItem>) {
        val urls = items.take(3).mapNotNull { it.backdropUrl }.distinct()
        if (urls.isNotEmpty()) Aura.images.prefetch(urls, 1280, 720)
    }
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
