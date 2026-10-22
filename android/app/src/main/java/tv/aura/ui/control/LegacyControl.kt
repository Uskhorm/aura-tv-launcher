package tv.aura.ui.control

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*
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

@Composable
fun SpringIn(from: Float = .9f, originX: Float = .5f, originY: Float = .5f, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, spring(.72f, Spring.StiffnessMediumLow)) }
    Box(Modifier.graphicsLayer {
        val k = from + (1 - from) * p.value
        scaleX = k; scaleY = k
        alpha = p.value.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(originX, originY)
    }) { content() }
}

// ---------------------------------------------------------------------------- Action sheet (≡ / Play-Pause)
@Composable
fun ActionSheet(s: HomeState) {
    val app = s.sheetApp ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SpringIn {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(ICON_W * 1.25f, ICON_H * 1.25f).graphicsLayer { shadowElevation = 30.dp.toPx(); shape = ICON_SHAPE; clip = true }) {
                    TileFace(app, s.visuals[app.key])
                }
                Spacer(Modifier.height(20.dp))
                BasicText(app.label, style = style(26f, FontWeight.Bold, display = true))
                Spacer(Modifier.height(4.dp))
                BasicText(if (app.isBuiltin) "Built into Home" else app.packageName, style = style(13f, color = Ink.tertiary))
                Spacer(Modifier.height(22.dp))
                s.sheet.forEachIndexed { k, item ->
                    SheetButton(item.label, k == s.sheetIndex, item.danger) { s.sheetIndex = k; item.action() }
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, focused: Boolean, danger: Boolean, onClick: () -> Unit) {
    val k = animateFloatAsState(if (focused) 1.06f else 1f, spring(.6f, Spring.StiffnessMedium), label = "sk")
    val bg = animateColorAsState(if (focused) Color.White else Color.White.copy(.13f), tween(160), label = "sbg")
    Box(
        Modifier
            .width(380.dp).height(44.dp)
            .graphicsLayer { scaleX = k.value; scaleY = k.value; shadowElevation = if (focused) 16.dp.toPx() else 0f; shape = RoundedCornerShape(12.dp) }
            .background(bg.value, RoundedCornerShape(12.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = style(16.5f, FontWeight.SemiBold, if (danger) Ink.red else if (focused) Ink.dark else Color.White))
    }
}

// ---------------------------------------------------------------------------- Control Center
@Composable
fun ControlCenter(s: HomeState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000L - now % 60_000L + 50) } }
    val rows = s.controlRows()
    Box(Modifier.fillMaxSize().padding(top = 22.dp, end = 26.dp), contentAlignment = Alignment.TopEnd) {
        SpringIn(.88f, originX = 1f, originY = 0f) {
            Column(
                Modifier
                    .width(408.dp)
                    .graphicsLayer { shadowElevation = 40.dp.toPx(); shape = RoundedCornerShape(30.dp) }
                    .background(Color(0xD9202024), RoundedCornerShape(30.dp))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(.28f), Color.White.copy(.05f))), RoundedCornerShape(30.dp))
                    .padding(22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(clock(now, s.clock24), style = style(38f, FontWeight.Bold, display = true, tracking = -.02f))
                        BasicText(SimpleDateFormat("EEEE, d MMMM", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(now)), style = style(14f, color = Ink.secondary))
                    }
                    Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(Color(0xFF8E8CFF), Color(0xFFFF6FA8))), CircleShape), contentAlignment = Alignment.Center) {
                        Glyph("house", Color.White, Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                rows.forEachIndexed { r, items ->
                    if (items.all { !it.wide }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            items.forEachIndexed { c, it -> RoundControl(it, s.controlRow == r && s.controlCol == c) }
                        }
                        Spacer(Modifier.height(14.dp))
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items.forEachIndexed { c, it -> WideControl(it, s.controlRow == r && s.controlCol == c, Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundControl(item: ControlItem, focused: Boolean) {
    val k = animateFloatAsState(if (focused) 1.12f else 1f, spring(.6f, Spring.StiffnessMedium), label = "rk")
    val bg = animateColorAsState(if (focused) Color.White else Color.White.copy(.14f), tween(150), label = "rbg")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp).clickable(remember { MutableInteractionSource() }, null) { item.action() }) {
        Box(
            Modifier.size(56.dp).graphicsLayer { scaleX = k.value; scaleY = k.value; shadowElevation = if (focused) 14.dp.toPx() else 0f; shape = CircleShape }
                .background(bg.value, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(item.glyph, if (focused) Ink.dark else Color.White, Modifier.size(24.dp)) }
        Spacer(Modifier.height(7.dp))
        BasicText(item.label, style = style(11f, FontWeight.SemiBold, if (focused) Color.White else Ink.secondary), maxLines = 1)
    }
}

@Composable
private fun WideControl(item: ControlItem, focused: Boolean, modifier: Modifier) {
    val k = animateFloatAsState(if (focused) 1.05f else 1f, spring(.6f, Spring.StiffnessMedium), label = "wk")
    val bg = animateColorAsState(if (focused) Color.White else Color.White.copy(.12f), tween(150), label = "wbg")
    Row(
        modifier.height(64.dp)
            .graphicsLayer { scaleX = k.value; scaleY = k.value; shadowElevation = if (focused) 14.dp.toPx() else 0f; shape = RoundedCornerShape(18.dp) }
            .background(bg.value, RoundedCornerShape(18.dp))
            .clickable(remember { MutableInteractionSource() }, null) { item.action() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(if (focused) Color(0x14000000) else Color.White.copy(.14f), CircleShape), contentAlignment = Alignment.Center) {
            if (item.glyph == "weather") WeatherIcon("part", Modifier.size(20.dp), if (focused) Ink.dark else Color.White)
            else Glyph(item.glyph, if (focused) Ink.dark else Color.White, Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            BasicText(item.label, style = style(14f, FontWeight.SemiBold, if (focused) Ink.dark else Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.detail?.let { BasicText(it, style = style(11.5f, color = if (focused) Ink.darkSecondary else Ink.tertiary), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---------------------------------------------------------------------------- Toast
@Composable
fun Toast(s: HomeState) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(s.toastTick) {
        if (s.toastTick == 0) return@LaunchedEffect
        visible = true
        delay(2800)
        visible = false
    }
    Box(Modifier.fillMaxSize().padding(bottom = 30.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it } + scaleIn(initialScale = .9f), exit = fadeOut() + slideOutVertically { it / 2 } + scaleOut(targetScale = .95f)) {
            BasicText(
                s.toast ?: "",
                Modifier.graphicsLayer { shadowElevation = 18.dp.toPx(); shape = RoundedCornerShape(50) }
                    .background(Color(0xF0232327), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(.1f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 11.dp),
                style = style(13.5f, FontWeight.SemiBold),
            )
        }
    }
}

