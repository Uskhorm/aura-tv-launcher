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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun AuraRoot(
    s: HomeState,
    homeViewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    heroLayer: @Composable (HomeState) -> Unit = { Hero(it); ShelfText(it) },
    dock: @Composable (HomeState) -> Unit = { Grid(it) },
    statusLine: @Composable (HomeState) -> Unit = { StatusBar(it) },
    controlCenter: @Composable (HomeState) -> Unit = { ControlCenter(it) },
    settingsScreens: @Composable (HomeState) -> Unit = { SettingsScreen(it) },
    ambient: @Composable (HomeState) -> Unit = { Aerial(it) },
) {
    LaunchedEffect(s) {
        snapshotFlow { s.focused?.packageName to s.mode }.collect { (pkg, mode) ->
            homeViewModel.focusApp(pkg)
            homeViewModel.setMode(mode.toUiMode())
        }
    }
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { s.onKey(it.nativeKeyEvent) }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        heroLayer(s)
        dock(s)
        statusLine(s)

        AnimatedVisibility(s.mode == Mode.Sheet, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) { ActionSheet(s) }
        AnimatedVisibility(s.mode == Mode.Control, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) { controlCenter(s) }
        AnimatedVisibility(s.mode == Mode.Settings || s.mode == Mode.CityInput, enter = fadeIn(tween(260)), exit = fadeOut(tween(200))) { settingsScreens(s) }
        if (s.mode == Mode.CityInput) CityDialog(s)
        AnimatedVisibility(s.mode == Mode.Saver, enter = fadeIn(tween(1800)), exit = fadeOut(tween(450))) { ambient(s) }
        Toast(s)
    }

    LaunchedEffect(s.mode == Mode.CityInput) { if (s.mode != Mode.CityInput) runCatching { focusRequester.requestFocus() } }

    // screensaver timer
    LaunchedEffect(s.lastInput, s.saverMinutes, s.mode) {
        if ((s.mode == Mode.Home || s.mode == Mode.Shelf) && s.saverMinutes > 0) {
            delay(s.saverMinutes * 60_000L)
            s.startSaver()
        }
    }
    // the Top Shelf carousel turns its own pages, like tvOS
    LaunchedEffect(s.slideTick, s.slide, s.mode, s.focus < COLS) {
        if ((s.mode == Mode.Home && s.focus < COLS) || s.mode == Mode.Shelf) {
            delay(9_000)
            s.nextSlide()
        }
    }
}

