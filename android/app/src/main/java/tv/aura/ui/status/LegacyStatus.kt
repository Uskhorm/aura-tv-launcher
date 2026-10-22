package tv.aura.ui.status

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
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


// ---------------------------------------------------------------------------- Status (clock, weather)
@Composable
fun StatusBar(s: HomeState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L - now % 60_000L + 50) } }
    val show = s.showClock && (s.mode == Mode.Home || s.mode == Mode.Shelf || s.mode == Mode.Jiggle || s.mode == Mode.Sheet)
    val a = animateFloatAsState(if (show) 1f else 0f, tween(300), label = "status")
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, end = EDGE + 4.dp).graphicsLayer { alpha = a.value },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        s.weather?.let { w ->
            WeatherIcon(Weather.kind(w.code), Modifier.size(19.dp), Ink.secondary)
            Spacer(Modifier.width(6.dp))
            BasicText("${w.temp}°", style = style(18f, FontWeight.SemiBold, Ink.secondary))
            Spacer(Modifier.width(16.dp))
        }
        BasicText(clock(now, s.clock24), style = style(18f, FontWeight.SemiBold))
    }
}

fun clock(t: Long, h24: Boolean): String = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(t))

