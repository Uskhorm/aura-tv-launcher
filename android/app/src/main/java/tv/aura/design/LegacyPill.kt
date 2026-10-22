package tv.aura.design

import tv.aura.home.R
import tv.aura.app.*
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


/** tvOS-style capsule button: frosted when idle, white and lifted when focused. */
@Composable
fun Pill(label: String?, glyph: String?, focused: Boolean, danger: Boolean = false, modifier: Modifier = Modifier, height: Float = 42f, onClick: (() -> Unit)? = null) {
    val k = animateFloatAsState(if (focused) 1.09f else 1f, spring(.6f, Spring.StiffnessMedium), label = "pk")
    val bg = animateColorAsState(if (focused) Color.White else Color.White.copy(.17f), tween(180), label = "pbg")
    val fg = when {
        danger -> Ink.red
        focused -> Ink.dark
        else -> Color.White
    }
    Row(
        modifier
            .height(height.dp)
            .graphicsLayer {
                scaleX = k.value; scaleY = k.value
                shadowElevation = if (focused) 18.dp.toPx() else 0f
                shape = RoundedCornerShape(50)
            }
            .background(bg.value, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClick) else Modifier)
            .padding(horizontal = if (label == null) 15.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) Glyph(glyph, fg, Modifier.size(15.dp))
        if (glyph != null && label != null) Spacer(Modifier.width(9.dp))
        if (label != null) BasicText(label, style = style(16f, FontWeight.SemiBold, fg), maxLines = 1)
    }
}

