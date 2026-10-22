package tv.aura.ui.dock

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
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


// Everything is laid out on a 960×540 dp stage; MainActivity scales density so 1 dp = 2 px on 1080p.
val ICON_W = 156.dp
val ICON_H = 94.dp
private val GAP = 24.dp
val EDGE = 42.dp
private val ROW_H = 132.dp
private const val TOP_ROW_Y = 376f
val ICON_SHAPE = RoundedCornerShape(11.dp)

// ---------------------------------------------------------------------------- App grid
@Composable
fun Grid(s: HomeState) {
    val row = s.focus / COLS
    val target = when {
        s.mode == Mode.Shelf -> 200f
        s.gridMode || (s.mode != Mode.Home && s.mode != Mode.Jiggle && row > 0) -> 196f - TOP_ROW_Y - row * 132f
        else -> 0f
    }
    val scroll = animateFloatAsState(target, spring(.86f, Spring.StiffnessLow), label = "scroll")
    val boot = animateFloatAsState(if (s.loaded) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "boot")
    val fade = animateFloatAsState(
        when (s.mode) {
            Mode.Settings, Mode.CityInput, Mode.Saver, Mode.Sheet -> 0f
            Mode.Control -> .3f
            else -> 1f
        }, tween(300), label = "gridFade",
    )

    Box(Modifier.fillMaxSize().graphicsLayer {
        translationY = scroll.value.dp.toPx() + (1 - boot.value) * 60.dp.toPx()
        alpha = boot.value * fade.value
    }) {
        s.apps.forEachIndexed { i, app ->
            val r = i / COLS
            if (r < row - 3 || r > row + 4) return@forEachIndexed
            key(app.key) {
                Box(Modifier.offset(EDGE + (ICON_W + GAP) * (i % COLS), TOP_ROW_Y.dp + ROW_H * r)) { Tile(s, i, app) }
            }
        }
    }
}

@Composable
private fun Tile(s: HomeState, i: Int, app: AppEntry) {
    val focused = i == s.focus && (s.mode == Mode.Home || s.mode == Mode.Jiggle || s.mode == Mode.Sheet)
    val jiggling = focused && s.mode == Mode.Jiggle
    val v = s.visuals[app.key]

    val scale = animateFloatAsState(if (focused) 1.17f else 1f, spring(.62f, 340f), label = "scale")
    val lift = animateFloatAsState(if (focused) 1f else 0f, tween(if (focused) 240 else 180), label = "lift")

    // parallax tilt: the icon leans toward the direction focus came from, then settles with a wobble
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    LaunchedEffect(focused, s.moveTick) {
        if (focused && !s.calm) {
            val (ry, rx) = when (s.lastDir) { Dir.Right -> -10f to 0f; Dir.Left -> 10f to 0f; Dir.Down -> 0f to 9f; Dir.Up -> 0f to -9f }
            tiltY.snapTo(ry); tiltX.snapTo(rx)
            launch { tiltY.animateTo(0f, spring(.32f, 220f)) }
            tiltX.animateTo(0f, spring(.32f, 220f))
        }
    }
    // edge bump
    val bump = remember { Animatable(0f) }
    LaunchedEffect(s.bumpTick) {
        if (focused && s.bumpTick > 0) {
            bump.snapTo(0f)
            bump.animateTo(1f, tween(90, easing = FastOutSlowInEasing))
            bump.animateTo(0f, spring(.35f, 500f))
        }
    }
    val jig = if (jiggling) rememberInfiniteTransition(label = "jig").animateFloat(-1.7f, 1.7f, infiniteRepeatable(tween(130), RepeatMode.Reverse), label = "jr") else null
    val bootDelay = remember { Animatable(0f) }
    LaunchedEffect(s.loaded) { if (s.loaded) { delay(60L * i.coerceAtMost(10)); bootDelay.animateTo(1f, spring(.75f, Spring.StiffnessLow)) } }

    Box(
        Modifier
            .size(ICON_W, ICON_H)
            .onGloballyPositioned { s.tileBounds[app.key] = it.boundsInWindow() }
            .graphicsLayer {
                val b = bump.value * 9.dp.toPx()
                scaleX = scale.value; scaleY = scale.value
                rotationY = tiltY.value
                rotationX = tiltX.value
                rotationZ = jig?.value ?: 0f
                cameraDistance = 14f * density
                translationX = when (s.bumpDir) { Dir.Left -> -b; Dir.Right -> b; else -> 0f }
                translationY = when (s.bumpDir) { Dir.Up -> -b; Dir.Down -> b * .6f; else -> 0f } - lift.value * 3.dp.toPx() + (1 - bootDelay.value) * 30.dp.toPx()
                alpha = bootDelay.value
                shadowElevation = lift.value * 34.dp.toPx() + 3.dp.toPx()
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
                shape = ICON_SHAPE
                clip = true
            }
            .clickable(remember { MutableInteractionSource() }, null) { if (s.mode == Mode.Home) s.activate(i) }
            .drawWithContent {
                drawContent()
                // glass: soft top sheen, a light rim, and a glare that slides opposite the tilt
                drawRect(Brush.verticalGradient(0f to Color.White.copy(.14f), .5f to Color.Transparent))
                if (lift.value > 0f) {
                    val gx = size.width * (.5f - tiltY.value / 22f)
                    val gy = size.height * (.08f + tiltX.value / 30f)
                    val r = size.width * .7f
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(.30f * lift.value), Color.Transparent), Offset(gx, gy), r), r, Offset(gx, gy))
                }
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(.38f), Color.White.copy(.06f), Color.White.copy(.12f))),
                    cornerRadius = CornerRadius(11.dp.toPx()), style = Stroke(1.dp.toPx()),
                )
            }
    ) { TileFace(app, v) }

    BasicText(
        app.label,
        Modifier.width(ICON_W + 40.dp).offset(x = (-20).dp, y = ICON_H + 17.dp).graphicsLayer { alpha = lift.value; translationY = (1 - lift.value) * -5.dp.toPx() },
        style = style(14.5f, FontWeight.SemiBold).copy(textAlign = TextAlign.Center, shadow = Shadow(Color.Black.copy(.5f), Offset(0f, 2f), 8f)),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TileFace(app: AppEntry, v: AppVisual?, modifier: Modifier = Modifier.fillMaxSize(), glyphScale: Float = 1f) {
    val a = v?.a ?: Color(0xFF3A3A3C)
    val b = v?.b ?: Color(0xFF1C1C1E)
    Box(modifier.background(Brush.linearGradient(listOf(a, b), Offset.Zero, Offset(0f, Float.POSITIVE_INFINITY))), contentAlignment = Alignment.Center) {
        when {
            app.key == SETTINGS_KEY -> Glyph("gear", Color(0xFF5B5B61), Modifier.fillMaxHeight(.56f * glyphScale).aspectRatio(1f))
            v?.banner != null -> Image(v.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
            v?.icon != null -> {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(.22f), Color.Transparent), Offset(0f, 0f), 900f)))
                Image(v.icon, app.label, Modifier.fillMaxHeight(.62f * glyphScale).aspectRatio(1f), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
            }
            else -> BasicText(app.label.take(1).uppercase(), style = style(30f, FontWeight.Bold, Ink.secondary, display = true))
        }
    }
}
