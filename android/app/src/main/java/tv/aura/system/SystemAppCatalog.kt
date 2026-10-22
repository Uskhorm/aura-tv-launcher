package tv.aura.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import tv.aura.core.InstalledApp

/** Package discovery and artwork decoding are called on AuraSystem's worker dispatcher. */
internal class SystemAppCatalog(private val context: Context) {
    private val pm = context.packageManager

    @Suppress("DEPRECATION")
    fun installed(): List<InstalledApp> {
        val found = linkedMapOf<String, InstalledApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val results = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            for (result in results) {
                val activity = result.activityInfo ?: continue
                val pkg = activity.packageName
                if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled ||
                    pkg in found || !visible(pkg)) continue
                // Some Amazon launcher entries are exported but signature protected.
                if (activity.permission != null && context.checkSelfPermission(activity.permission) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) continue
                found[pkg] = InstalledApp(
                    pkg, if (pkg == YOUTUBE) "YouTube" else runCatching {
                        result.loadLabel(pm).toString().trim().ifEmpty { pkg }
                    }.getOrDefault(pkg),
                    ComponentName(pkg, activity.name).flattenToString(),
                    category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                    activity.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or
                        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                )
            }
        }
        // Settings has no conventional launcher entry on Fire OS. This is the exported wrapper,
        // NOT v2's CTS dummy handler. The ordinary launcher package remains hidden from the dock.
        val settings = ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")
        runCatching { pm.getActivityInfo(settings, 0) }.getOrNull()?.takeIf {
            it.enabled && it.exported && it.applicationInfo.enabled
        }?.let {
            found[SETTINGS_PACKAGE] = InstalledApp(SETTINGS_PACKAGE, "Settings", settings.flattenToString(), true, true)
        }
        return found.values.sortedWith(compareBy<InstalledApp> {
            PREFERRED.indexOf(it.pkg).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE
        }.thenBy { it.label.lowercase() })
    }

    /** One bounded bitmap; callers own caching. Never retain original package Drawables. */
    @Suppress("DEPRECATION")
    fun artwork(app: InstalledApp, banner: Boolean): ImageBitmap? = runCatching {
        val component = ComponentName.unflattenFromString(app.component) ?: return null
        val info = pm.getActivityInfo(component, 0)
        val drawable = if (banner) info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm) ?: info.loadIcon(pm)
            else info.loadIcon(pm)
        val maxW = if (banner) 320 else 192
        val maxH = if (banner) 180 else 192
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxW
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxH
        val scale = minOf(1f, maxW.toFloat() / w, maxH.toFloat() / h)
        drawable.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()

    private fun visible(pkg: String): Boolean {
        if (pkg == context.packageName || pkg in HIDDEN || pkg.contains(".overlay") || pkg.contains(".stub")) return false
        // Amazon services/stubs advertise MAIN but do not represent useful household apps.
        return !pkg.startsWith("com.amazon.") || pkg in AMAZON_APPS
    }

    companion object {
        const val YOUTUBE = "io.gh.reisxd.tizentube.cobalt"
        const val SETTINGS_PACKAGE = "com.amazon.tv.settings.v2"
        val PREFERRED = listOf(YOUTUBE, "com.nuvio.tv", "com.amazon.firebat", "app.aksports.live", "com.spotify.tv.android")
        private val AMAZON_APPS = setOf("com.amazon.firebat", "com.amazon.venezia", "com.amazon.tv.livetv",
            "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        private val HIDDEN = setOf("com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.dummy.settings",
            "com.android.settings", "com.android.systemui", "io.github.toolicious.homeonfire")
    }
}
