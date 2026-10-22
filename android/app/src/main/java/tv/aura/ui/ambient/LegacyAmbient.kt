package tv.aura.ui.ambient

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Springs a panel in from slightly smaller, like tvOS sheets. */

// ---------------------------------------------------------------------------- Aerial screensaver
@Composable
fun Aerial(s: HomeState) {
    var index by remember { mutableIntStateOf(Random.nextInt(Photos.all.size)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            index = (index + 1 + Random.nextInt(Photos.all.size - 1)) % Photos.all.size
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L - now % 60_000L + 50) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(index, animationSpec = tween(2600, easing = LinearEasing), label = "aerial") { p ->
            val dir = remember { if (Random.nextBoolean()) 1f else -1f }
            val t = remember { Animatable(0f) }
            LaunchedEffect(Unit) { t.animateTo(1f, tween(19_000, easing = LinearEasing)) }
            val img = rememberPhoto(p)
            if (img != null) Image(img, null, Modifier.fillMaxSize().graphicsLayer {
                val k = if (s.calm) 0f else t.value
                val sc = 1.04f + .14f * k
                scaleX = sc; scaleY = sc
                translationX = dir * (k - .5f) * 50.dp.toPx()
                translationY = (k - .5f) * -14.dp.toPx()
            }, contentScale = ContentScale.Crop)
            // location title, like Apple's Aerials
            val cap = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(1800); cap.animateTo(1f, tween(1200)); delay(6500); cap.animateTo(0f, tween(1400)) }
            Box(Modifier.fillMaxSize().padding(start = 50.dp, bottom = 46.dp), contentAlignment = Alignment.BottomStart) {
                BasicText(
                    Photos.all[p].caption,
                    Modifier.graphicsLayer { alpha = cap.value; translationY = (1 - cap.value) * 8.dp.toPx() },
                    style = style(30f, FontWeight.Bold, display = true).copy(shadow = Shadow(Color.Black.copy(.45f), Offset(0f, 3f), 18f)),
                )
            }
        }
        BasicText(
            clock(now, s.clock24),
            Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 46.dp),
            style = style(30f, FontWeight.Bold, Color.White.copy(.9f), display = true).copy(shadow = Shadow(Color.Black.copy(.4f), Offset(0f, 2f), 14f)),
        )
    }
}

