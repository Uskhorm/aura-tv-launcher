package tv.aura.ui.settings

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
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

// ---------------------------------------------------------------------------- Settings (tvOS Settings app layout)
@Composable
fun SettingsScreen(s: HomeState) {
    val items = s.settingsItems()
    val cur = items.getOrNull(s.settingsIndex)
    Box(Modifier.fillMaxSize()) {
        BasicText("Home", Modifier.align(Alignment.TopCenter).padding(top = 30.dp), style = style(24f, FontWeight.Bold, display = true))
        // left: big icon and a description of the focused row
        Column(Modifier.width(470.dp).fillMaxHeight().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SpringIn(.94f) {
                Box(Modifier.size(270.dp, 162.dp).graphicsLayer { shadowElevation = 34.dp.toPx(); shape = RoundedCornerShape(19.dp); clip = true }) {
                    TileFace(AppEntry(SETTINGS_KEY, "Home Settings", null), AppVisual(null, null, Color(0xFFF2F2F5), Color(0xFFA9A9B0)))
                }
            }
            Spacer(Modifier.height(30.dp))
            Crossfade(cur?.note ?: "", animationSpec = tween(220), label = "note") { note ->
                BasicText(note, Modifier.width(330.dp), style = style(14.5f, color = Ink.secondary, lineHeight = 21f).copy(textAlign = TextAlign.Center))
            }
        }
        // right: the list
        Column(Modifier.padding(start = 488.dp, top = 84.dp).width(430.dp)) {
            items.forEachIndexed { k, it ->
                SettingRow(it, k == s.settingsIndex) { s.settingsIndex = k; it.action?.invoke() ?: it.change?.invoke(1) }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SettingRow(item: SettingItem, focused: Boolean, onClick: () -> Unit) {
    val k = animateFloatAsState(if (focused) 1.045f else 1f, spring(.65f, Spring.StiffnessMedium), label = "rowk")
    val bg = animateColorAsState(if (focused) Color.White else Color.Transparent, tween(150), label = "rowbg")
    val fg = when {
        item.danger && !focused -> Ink.red
        focused -> Ink.dark
        else -> Color.White
    }
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .graphicsLayer { scaleX = k.value; scaleY = k.value; shadowElevation = if (focused) 14.dp.toPx() else 0f; shape = RoundedCornerShape(10.dp) }
            .background(bg.value, RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(item.label, Modifier.weight(1f), style = style(16.5f, FontWeight.Medium, if (item.danger && focused) Ink.red else fg))
        item.value?.let { BasicText(it, style = style(16f, color = if (focused) Ink.darkSecondary else Ink.tertiary), maxLines = 1) }
        if (item.action != null && item.value == null || item.change == null && item.action != null) {
            Spacer(Modifier.width(8.dp))
            Glyph("chevron", if (focused) Ink.darkSecondary else Ink.quaternary, Modifier.size(11.dp))
        }
    }
}

@Composable
fun CityDialog(s: HomeState) = Box(Modifier.fillMaxSize().background(Color.Black.copy(.4f)), contentAlignment = Alignment.Center) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    SpringIn {
        Column(Modifier.width(460.dp).background(Color(0xF2232327), RoundedCornerShape(24.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Weather", style = style(24f, FontWeight.Bold, display = true))
            Spacer(Modifier.height(6.dp))
            BasicText("Type a city, then press Done. Leave it empty to turn weather off.", style = style(13f, color = Ink.secondary).copy(textAlign = TextAlign.Center))
            Spacer(Modifier.height(18.dp))
            BasicTextField(
                value = s.cityDraft,
                onValueChange = { s.cityDraft = it },
                singleLine = true,
                textStyle = style(19f, FontWeight.SemiBold),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { s.closeCity(save = true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
                    .background(Color.White.copy(.14f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

