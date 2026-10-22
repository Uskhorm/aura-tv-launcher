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
fun WeatherIcon(kind: String, modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    fun cloud(dy: Float = 0f, a: Float = 1f) {
        val cc = color.copy(alpha = color.alpha * a)
        drawCircle(cc, w * .2f, Offset(w * .36f, h * .55f + dy))
        drawCircle(cc, w * .26f, Offset(w * .58f, h * .45f + dy))
        drawRoundRect(cc, Offset(w * .14f, h * .52f + dy), Size(w * .74f, h * .26f), CornerRadius(h * .13f))
    }
    when (kind) {
        "sun" -> {
            drawCircle(color, w * .2f, center)
            repeat(8) { k -> rotate(k * 45f) { drawLine(color, Offset(w / 2, h * .06f), Offset(w / 2, h * .18f), w * .08f, StrokeCap.Round) } }
        }
        "part" -> { drawCircle(color.copy(alpha = color.alpha * .85f), w * .17f, Offset(w * .32f, h * .32f)); cloud(h * .06f) }
        "cloud" -> cloud()
        "fog" -> repeat(3) { k -> drawLine(color, Offset(w * .15f, h * (.35f + k * .17f)), Offset(w * .85f, h * (.35f + k * .17f)), w * .08f, StrokeCap.Round) }
        "snow" -> { cloud(-h * .12f); repeat(3) { k -> drawCircle(color, w * .05f, Offset(w * (.32f + k * .18f), h * .86f)) } }
        else -> { cloud(-h * .12f); repeat(3) { k -> drawLine(color, Offset(w * (.34f + k * .18f), h * .76f), Offset(w * (.3f + k * .18f), h * .94f), w * .07f, StrokeCap.Round) } }
    }
}
