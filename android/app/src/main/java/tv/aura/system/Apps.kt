package tv.aura.system

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/** One tile on the Home screen. Built-in tiles have no [component]. */
data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName?,
    val leanback: Boolean = false,
    val system: Boolean = false,
    val category: String? = null,
) {
    val isBuiltin get() = component == null
    val packageName get() = component?.packageName ?: key
}

/** Artwork and colours for a tile, loaded off the main thread. */
class AppVisual(
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val a: Color,
    val b: Color,
)

const val SETTINGS_KEY = "builtin:settings"

/** Streaming apps that should lead the grid on a fresh install, in this order. */
private val PREFERRED = listOf(
    "io.gh.reisxd.tizentube.cobalt", "com.nuvio.tv", "com.amazon.firebat", "app.aksports.live", "com.spotify.tv.android",
    "com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged",
    "com.netflix.ninja", "com.netflix.mediaclient",
    "com.amazon.amazonvideo.livingroom", "com.amazon.avod",
    "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv",
    "com.wbd.stream", "com.hbo.hbonow",
    "com.spotify.tv.android", "tv.twitch.android.app", "tv.twitch.android.viewer",
    "com.google.android.youtube.tvmusic", "com.plexapp.android", "org.jellyfin.androidtv",
    "tv.pluto.android", "com.google.android.youtube.tvkids",
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    var order: List<String>
        get() = sp.getString("order", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit { putString("order", v.joinToString("\n")) }
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("hidden", v) }
    var photoShelf: Boolean
        get() = sp.getBoolean("photoShelf", true)
        set(v) = sp.edit { putBoolean("photoShelf", v) }
    var showClock: Boolean
        get() = sp.getBoolean("showClock", true)
        set(v) = sp.edit { putBoolean("showClock", v) }
    var clicks: Boolean
        get() = sp.getBoolean("clicks", true)
        set(v) = sp.edit { putBoolean("clicks", v) }
    var clock24: Boolean
        get() = sp.getBoolean("clock24", true)
        set(v) = sp.edit { putBoolean("clock24", v) }
    var calm: Boolean
        get() = sp.getBoolean("calm", false)
        set(v) = sp.edit { putBoolean("calm", v) }
    var saverMinutes: Int
        get() = sp.getInt("saver", 5)
        set(v) = sp.edit { putInt("saver", v) }
    var city: String
        get() = sp.getString("city", "")!!
        set(v) = sp.edit { putString("city", v) }
    var place: String
        get() = sp.getString("place", "")!!
        set(v) = sp.edit { putString("place", v) }
    var lat: Double
        get() = sp.getString("lat", null)?.toDoubleOrNull() ?: Double.NaN
        set(v) = sp.edit { putString("lat", v.toString()) }
    var lon: Double
        get() = sp.getString("lon", null)?.toDoubleOrNull() ?: Double.NaN
        set(v) = sp.edit { putString("lon", v.toString()) }

    fun reset() = sp.edit { remove("order"); remove("hidden") }
}

class AppRepository(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager

    /** Every launchable app, one per package. TV (leanback) entries win over phone ones. */
    fun installed(): List<AppEntry> {
        return SystemAppCatalog(ctx).installed().filterNot { it.pkg == SystemAppCatalog.SETTINGS_PACKAGE }.map { app ->
            AppEntry(app.pkg, app.label, ComponentName.unflattenFromString(app.component), app.leanback,
                app.system, if (app.pkg == SystemAppCatalog.SETTINGS_PACKAGE) "Settings" else runCatching {
                    @Suppress("DEPRECATION")
                    categoryName(pm.getApplicationInfo(app.pkg, 0))
                }.getOrNull())
        }
    }

    /** Applies the saved order (new apps go before Settings, preferred apps first). */
    fun arrange(apps: List<AppEntry>, saved: List<String>, hidden: Set<String>): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        val settings = AppEntry(SETTINGS_KEY, "Home Settings", null, category = "Settings")
        val all = byKey + (SETTINGS_KEY to settings)
        val result = saved.mapNotNull { all[it] }.toMutableList()
        val fresh = apps.filter { it.key !in saved }.sortedWith(
            compareBy<AppEntry> { PREFERRED.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { !it.leanback }
                .thenBy { it.label.lowercase() }
        )
        if (saved.isEmpty()) {
            result += fresh
            result += settings
        } else {
            val at = result.indexOfFirst { it.key == SETTINGS_KEY }.let { if (it < 0) result.size else it }
            result.addAll(at, fresh)
            if (result.none { it.key == SETTINGS_KEY }) result += settings
        }
        return result.filter { it.key !in hidden }
    }

    fun launchIntent(e: AppEntry): Intent? {
        val c = e.component ?: return null
        if (c.packageName == "com.amazon.tv.launcher") runCatching {
            (tv.aura.core.Aura.system as? AuraSystem)?.beforeAmazonNavigation?.invoke()
        }
        return Intent(Intent.ACTION_MAIN)
            .addCategory(if (e.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(c)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun loadVisual(e: AppEntry): AppVisual {
        val c = e.component ?: return AppVisual(null, null, Color(0xFFF2F2F5), Color(0xFFA9A9B0))
        val ai = try {
            pm.getActivityInfo(c, 0)
        } catch (_: Exception) {
            return AppVisual(null, null, Color(0xFF3A3A3C), Color(0xFF1C1C1E))
        }
        val banner: Bitmap? = runCatching { ai.loadBanner(pm) }.getOrNull()?.let { draw(it, 320, 180) }
        val icon: Bitmap? = if (banner != null) null else runCatching { hiResIcon(ai.packageName, ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon) ?: ai.loadIcon(pm) }
            .getOrNull()?.let { draw(it, 192, 192) }
        val src = banner ?: icon
        var a = Color(0xFF5E5CE6)
        var b = Color(0xFF2C2A99)
        if (src != null) {
            val p = Palette.from(src).maximumColorCount(16).generate()
            val light = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
            val dark = p.darkVibrantSwatch ?: p.darkMutedSwatch ?: p.mutedSwatch ?: p.dominantSwatch
            light?.let { a = Color(it.rgb) }
            dark?.let { b = Color(it.rgb) }
            if (light == null && dark != null) a = lerp(b, Color.White, .25f)
        }
        return AppVisual(banner?.asImageBitmap(), icon?.asImageBitmap(), a, b)
    }

    private fun hiResIcon(pkg: String, res: Int): Drawable? = runCatching {
        val r = pm.getResourcesForApplication(pkg)
        r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    private fun draw(d: Drawable, maxW: Int, maxH: Int): Bitmap? {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: maxW
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: maxH
        val s = minOf(maxW.toFloat() / iw, maxH.toFloat() / ih)
        return runCatching { d.toBitmap((iw * s).toInt().coerceAtLeast(1), (ih * s).toInt().coerceAtLeast(1)) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun query(i: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentActivities(i, 0)

    private fun categoryName(app: ApplicationInfo): String? {
        if (Build.VERSION.SDK_INT < 26) return null
        return when (app.category) {
            ApplicationInfo.CATEGORY_VIDEO -> "Watch"
            ApplicationInfo.CATEGORY_AUDIO -> "Listen"
            ApplicationInfo.CATEGORY_GAME -> "Play"
            ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Tools"
            ApplicationInfo.CATEGORY_MAPS -> "Maps"
            else -> null
        }
    }
}

fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    1f,
)
