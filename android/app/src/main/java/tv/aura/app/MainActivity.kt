package tv.aura.app

import tv.aura.home.R
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tv.aura.homeguard.HomeGuard

class MainActivity : ComponentActivity() {
    private lateinit var state: HomeState
    private var lastHomeReset = 0L
    private val homeViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[HomeViewModel::class.java]
    }

    // Refresh the grid when apps are installed, updated or removed.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            state.reload(dropArtFor = i.data?.schemeSpecificPart)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        state = HomeState(this)
        state.refreshWeather()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HomeGuard.resetRequests.collect { request ->
                    if (request > lastHomeReset) {
                        lastHomeReset = request
                        state.goHome()
                        homeViewModel.resetHome(state.focused?.packageName)
                    }
                }
            }
        }

        ContextCompat.registerReceiver(this, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)

        setContent {
            // Lay everything out on a 960×540 dp stage so it looks identical on 720p, 1080p and 4K TVs.
            val dm = resources.displayMetrics
            val d = minOf(dm.widthPixels / 960f, dm.heightPixels / 540f)
            CompositionLocalProvider(LocalDensity provides Density(d, 1f)) {
                AuraRoot(state, homeViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the top, like tvOS.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            state.goHome()
            homeViewModel.resetHome(state.focused?.packageName)
        }
    }

    override fun onResume() {
        super.onResume()
        state.reload()
    }

    override fun onStart() {
        super.onStart()
        homeViewModel.refreshIfStale()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        state.dispose()
        super.onDestroy()
    }
}
