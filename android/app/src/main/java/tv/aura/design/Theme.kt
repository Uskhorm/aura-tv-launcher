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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Inter is the closest open-licence match to Apple's SF Pro; Inter Display for big titles.
val Text = FontFamily(
    Font(R.font.inter_medium, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val Display = FontFamily(
    Font(R.font.inter_display_bold, FontWeight.Bold),
    Font(R.font.inter_display_extrabold, FontWeight.ExtraBold),
)

object Ink {
    val primary = Color.White
    val secondary = Color(0xC7FFFFFF)
    val tertiary = Color(0x8CFFFFFF)
    val quaternary = Color(0x52FFFFFF)
    val dark = Color(0xFF1C1C1E)
    val darkSecondary = Color(0xFF6E6E73)
    val red = Color(0xFFFF453A)
    val glass = Color(0x2EFFFFFF)
    val glassStrong = Color(0x47FFFFFF)
    val sheet = Color(0xD91C1C1E)
}

fun style(size: Float, weight: FontWeight = FontWeight.Medium, color: Color = Ink.primary, tracking: Float = 0f, display: Boolean = false, lineHeight: Float = 0f) =
    TextStyle(
        fontFamily = if (display) Display else Text,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        letterSpacing = (size * tracking).sp,
        lineHeight = if (lineHeight > 0) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
    )
