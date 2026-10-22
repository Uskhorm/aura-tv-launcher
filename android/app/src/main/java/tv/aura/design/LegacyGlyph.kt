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

// ---------------------------------------------------------------------------- Glyphs (drawn, so no icon fonts or files)
@Composable
fun Glyph(name: String, color: Color, modifier: Modifier) = Canvas(modifier) { glyph(name, color) }

private fun DrawScope.glyph(name: String, c: Color) {
    val w = size.width
    val h = size.height
    val sw = w * .1f
    val line = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (name) {
        "play" -> drawPath(Path().apply { moveTo(w * .2f, h * .1f); lineTo(w * .92f, h * .5f); lineTo(w * .2f, h * .9f); close() }, c)
        "dots" -> repeat(3) { k -> drawCircle(c, w * .1f, Offset(w * (.18f + k * .32f), h / 2)) }
        "chevron" -> drawPath(Path().apply { moveTo(w * .32f, h * .12f); lineTo(w * .7f, h * .5f); lineTo(w * .32f, h * .88f) }, c, style = Stroke(w * .14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        "moon" -> {
            val p = Path.combine(PathOperation.Difference,
                Path().apply { addOval(Rect(w * .1f, h * .1f, w * .9f, h * .9f)) },
                Path().apply { addOval(Rect(w * .32f, -h * .08f, w * 1.08f, h * .68f)) })
            drawPath(p, c)
        }
        "wifi" -> {
            for (k in 0..2) {
                val r = w * (.18f + k * .17f)
                drawArc(c, 225f, 90f, false, Offset(w / 2 - r, h * .78f - r), Size(r * 2, r * 2), style = line)
            }
            drawCircle(c, w * .07f, Offset(w / 2, h * .78f))
        }
        "display" -> {
            drawRoundRect(c, Offset(w * .08f, h * .14f), Size(w * .84f, h * .56f), CornerRadius(w * .08f), style = Stroke(sw))
            drawLine(c, Offset(w * .32f, h * .86f), Offset(w * .68f, h * .86f), sw, StrokeCap.Round)
        }
        "sound" -> {
            drawPath(Path().apply { moveTo(w * .1f, h * .38f); lineTo(w * .28f, h * .38f); lineTo(w * .5f, h * .16f); lineTo(w * .5f, h * .84f); lineTo(w * .28f, h * .62f); lineTo(w * .1f, h * .62f); close() }, c)
            drawArc(c, -45f, 90f, false, Offset(w * .38f, h * .3f), Size(w * .4f, h * .4f), style = line)
            drawArc(c, -50f, 100f, false, Offset(w * .3f, h * .14f), Size(w * .68f, h * .72f), style = line)
        }
        "bluetooth" -> drawPath(Path().apply {
            moveTo(w * .28f, h * .3f); lineTo(w * .72f, h * .68f); lineTo(w * .5f, h * .88f); lineTo(w * .5f, h * .12f); lineTo(w * .72f, h * .32f); lineTo(w * .28f, h * .7f)
        }, c, style = line)
        "gear" -> {
            val cc = center
            val r = size.minDimension / 2
            fun oval(x: Float, y: Float, rr: Float) = Rect(x - rr, y - rr, x + rr, y + rr)
            val teeth = Path().apply {
                // 12 tapered teeth
                repeat(12) { k ->
                    val a = Math.toRadians(k * 30.0)
                    fun pt(rad: Float, off: Float): Offset {
                        val x = rad * kotlin.math.cos(a) - off * kotlin.math.sin(a)
                        val y = rad * kotlin.math.sin(a) + off * kotlin.math.cos(a)
                        return Offset(cc.x + x.toFloat(), cc.y + y.toFloat())
                    }
                    val p1 = pt(r * .6f, -r * .15f); val p2 = pt(r * .98f, -r * .09f)
                    val p3 = pt(r * .98f, r * .09f); val p4 = pt(r * .6f, r * .15f)
                    moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
                }
            }
            val body = Path.combine(PathOperation.Union, Path().apply { addOval(oval(cc.x, cc.y, r * .74f)) }, teeth)
            val holes = Path().apply { addOval(oval(cc.x, cc.y, r * .34f)) }
            drawPath(Path.combine(PathOperation.Difference, body, holes), c)
            drawCircle(c, r * .14f, cc)
        }
        "sliders" -> for (k in 0..2) {
            val y = h * (.22f + k * .28f)
            drawLine(c, Offset(w * .1f, y), Offset(w * .9f, y), sw * .8f, StrokeCap.Round)
            drawCircle(c, w * .12f, Offset(w * (.3f + (k % 2) * .4f), y))
        }
        "search" -> {
            drawCircle(c, w * .28f, Offset(w * .43f, h * .43f), style = Stroke(sw * 1.1f))
            drawLine(c, Offset(w * .64f, h * .64f), Offset(w * .88f, h * .88f), sw * 1.3f, StrokeCap.Round)
        }
        "house" -> {
            drawPath(Path().apply { moveTo(w * .5f, h * .1f); lineTo(w * .92f, h * .48f); lineTo(w * .8f, h * .48f); lineTo(w * .8f, h * .9f); lineTo(w * .2f, h * .9f); lineTo(w * .2f, h * .48f); lineTo(w * .08f, h * .48f); close() }, c)
        }
    }
}

