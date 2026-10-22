package tv.aura.ui.hero

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
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


// ---------------------------------------------------------------------------- Hero / Top Shelf backdrop
@Composable
fun Hero(s: HomeState) {
    val slide = s.currentSlide
    val photo = slide?.photo ?: 0
    val overlay = s.mode == Mode.Sheet || s.mode == Mode.Control || s.mode == Mode.Settings || s.mode == Mode.CityInput
    val blurA = animateFloatAsState(if (s.gridMode || overlay) 1f else 0f, tween(550, easing = FastOutSlowInEasing), label = "blur")
    val dimA = animateFloatAsState(
        when {
            s.mode == Mode.Settings || s.mode == Mode.CityInput -> .62f
            overlay -> .42f
            s.gridMode -> .28f
            else -> 0f
        }, tween(450), label = "dim",
    )

    Box(Modifier.fillMaxSize()) {
        if (s.photoShelf) {
            Crossfade(photo, animationSpec = tween(900, easing = FastOutSlowInEasing), label = "hero") { p -> KenBurns(p, s.calm) }
            Crossfade(photo, Modifier.graphicsLayer { alpha = blurA.value }, animationSpec = tween(900), label = "heroBlur") { p ->
                rememberBlurredPhoto(p)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low) }
            }
        } else {
            ColourShelf(s)
        }
        // scrims that keep text and icons readable on bright photos
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - blurA.value }.drawWithContent {
            drawRect(Brush.horizontalGradient(0f to Color.Black.copy(.62f), .38f to Color.Black.copy(.22f), .62f to Color.Transparent))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(.32f), .16f to Color.Transparent, .5f to Color.Transparent, .78f to Color.Black.copy(.28f), 1f to Color.Black.copy(.72f)))
        })
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimA.value }.background(Color.Black))
    }
}

/** A photo with a slow, continuous zoom and drift (the "Ken Burns" effect). */
@Composable
fun KenBurns(p: Int, calm: Boolean, durationMs: Int = 26_000, from: Float = 1.02f, to: Float = 1.1f, pan: Float = 18f) {
    val img = rememberPhoto(p) ?: return
    val t = rememberInfiniteTransition(label = "kb").animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Reverse), label = "kbt")
    Image(
        img, null,
        Modifier.fillMaxSize().graphicsLayer {
            val k = if (calm) 1f else t.value
            val sc = from + (to - from) * k
            scaleX = sc; scaleY = sc
            translationX = (k - .5f) * pan.dp.toPx()
        },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun ColourShelf(s: HomeState) {
    val pal = s.colorPalette()
    val spec = tween<Color>(900)
    val c1 = animateColorAsState(pal[0], spec, label = "c1")
    val c2 = animateColorAsState(pal[1], spec, label = "c2")
    val c3 = animateColorAsState(pal[2], spec, label = "c3")
    val c4 = animateColorAsState(pal[3], spec, label = "c4")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(c2.value, c1.value, Color.Black), Offset(w * .7f, h * .3f), w * 1.05f))
        drawCircle(Brush.radialGradient(listOf(c3.value.copy(.85f), Color.Transparent), Offset(w * .75f, h * .15f), w * .4f), w * .4f, Offset(w * .75f, h * .15f))
        drawCircle(Brush.radialGradient(listOf(c4.value.copy(.55f), Color.Transparent), Offset(w * .85f, h * .85f), w * .3f), w * .3f, Offset(w * .85f, h * .85f))
    }
}

// ---------------------------------------------------------------------------- Top Shelf text & buttons
@Composable
fun ShelfText(s: HomeState) {
    val expanded = s.mode == Mode.Shelf
    val visible = (s.mode == Mode.Home || s.mode == Mode.Shelf || s.mode == Mode.Jiggle) && !s.gridMode && s.loaded
    val a = animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 500 else 250), label = "sa")
    val drop = animateFloatAsState(if (expanded) 1f else 0f, spring(.85f, Spring.StiffnessLow), label = "drop")
    val app = s.shelfApp
    val slide = s.currentSlide

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = EDGE + 4.dp, bottom = 200.dp)
                .width(600.dp)
                .graphicsLayer {
                    translationY = drop.value * 112.dp.toPx()
                    val k = 1f + drop.value * .12f
                    scaleX = k; scaleY = k
                    transformOrigin = TransformOrigin(0f, 1f)
                }
        ) {
            AnimatedContent(
                targetState = (app?.key ?: "") to slide,
                transitionSpec = { (fadeIn(tween(500, 120)) + slideInHorizontally(tween(600, 120, FastOutSlowInEasing)) { it / 18 }) togetherWith fadeOut(tween(220)) },
                contentKey = { it.first + (it.second?.title ?: "") },
                label = "shelfText",
            ) { (_, sl) ->
                if (sl == null) return@AnimatedContent
                Column {
                    BasicText(sl.kicker, style = style(12f, FontWeight.SemiBold, Ink.secondary, .14f))
                    Spacer(Modifier.height(7.dp))
                    BasicText(sl.title, style = style(44f, FontWeight.ExtraBold, tracking = -.025f, display = true, lineHeight = 48f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(9.dp))
                    BasicText(sl.body, Modifier.width(470.dp), style = style(15f, FontWeight.Medium, Ink.secondary, lineHeight = 21f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            PageDots(s.slides.size, s.slide.mod(s.slides.size.coerceAtLeast(1)))
        }

        // buttons appear when you move up into the Top Shelf
        val b = animateFloatAsState(if (expanded) 1f else 0f, tween(if (expanded) 380 else 160, if (expanded) 140 else 0), label = "btn")
        Row(
            Modifier.offset(EDGE + 4.dp, 466.dp).graphicsLayer { alpha = b.value; translationY = (1 - b.value) * 16.dp.toPx() },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Pill("Open ${app?.label ?: ""}", "play", expanded && s.shelfButton == 0)
            Pill(null, "dots", expanded && s.shelfButton == 1)
        }
        val hint = animateFloatAsState(if (expanded) 1f else 0f, tween(300, 500), label = "hint")
        BasicText(
            "▲  Control Center",
            Modifier.align(Alignment.TopCenter).padding(top = 22.dp).graphicsLayer { alpha = hint.value * .9f },
            style = style(12f, FontWeight.SemiBold, Ink.tertiary, .04f),
        )
    }
}

@Composable
fun PageDots(n: Int, current: Int) {
    if (n <= 1) return
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { k ->
            val on = k == current
            val w = animateDpAsState(if (on) 18.dp else 6.dp, spring(.8f, Spring.StiffnessMediumLow), label = "dw")
            val c = animateColorAsState(if (on) Color.White else Color.White.copy(.38f), tween(300), label = "dc")
            Box(Modifier.size(w.value, 6.dp).background(c.value, RoundedCornerShape(50)))
        }
    }
}

