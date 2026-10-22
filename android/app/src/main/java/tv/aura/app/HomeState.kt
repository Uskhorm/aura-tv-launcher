package tv.aura.app

import tv.aura.home.R
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class Mode { Home, Shelf, Jiggle, Sheet, Control, Settings, CityInput, Saver }
enum class Dir { Left, Right, Up, Down }

class MenuItem(val label: String, val danger: Boolean = false, val action: () -> Unit)
class SettingItem(
    val label: String,
    val value: String? = null,
    val note: String = "",
    val danger: Boolean = false,
    val change: ((Int) -> Unit)? = null,
    val action: (() -> Unit)? = null,
)
class ControlItem(val label: String, val glyph: String, val wide: Boolean = false, val detail: String? = null, val action: () -> Unit)

const val COLS = 5
private const val LONG_PRESS_MS = 450L
private val SAVER_STEPS = listOf(2, 5, 10, 20, 0)

class HomeState(private val activity: MainActivity) {
    private val repo = AppRepository(activity)
    private val prefs = Prefs(activity)
    private val scope: CoroutineScope = MainScope()
    private val artLimit = Semaphore(3)
    val clicks = Clicks(activity).also { it.enabled = prefs.clicks }

    var apps by mutableStateOf(listOf<AppEntry>())
    val visuals = mutableStateMapOf<String, AppVisual>()
    var loaded by mutableStateOf(false)

    var focus by mutableIntStateOf(0)
    var lastTop by mutableIntStateOf(0)
    var mode by mutableStateOf(Mode.Home)
    var lastDir by mutableStateOf(Dir.Right)
    var moveTick by mutableIntStateOf(0)
    var bumpDir by mutableStateOf(Dir.Right)
    var bumpTick by mutableIntStateOf(0)

    // Top Shelf carousel
    var slide by mutableIntStateOf(0)
    var slideTick by mutableIntStateOf(0)
    var shelfButton by mutableIntStateOf(0)

    // overlays
    var sheetApp by mutableStateOf<AppEntry?>(null)
    var sheet by mutableStateOf(listOf<MenuItem>())
    var sheetIndex by mutableIntStateOf(0)
    var controlRow by mutableIntStateOf(0)
    var controlCol by mutableIntStateOf(0)
    var settingsIndex by mutableIntStateOf(0)
    var resetArmed by mutableStateOf(false)

    // preferences mirrored as state
    var photoShelf by mutableStateOf(prefs.photoShelf)
    var showClock by mutableStateOf(prefs.showClock)
    var clock24 by mutableStateOf(prefs.clock24)
    var calm by mutableStateOf(prefs.calm)
    var clickSounds by mutableStateOf(prefs.clicks)
    var saverMinutes by mutableIntStateOf(prefs.saverMinutes)
    var place by mutableStateOf(prefs.place)
    var cityDraft by mutableStateOf(prefs.city)
    var weather by mutableStateOf<Weather.Now?>(null)

    var toast by mutableStateOf<String?>(null)
    var toastTick by mutableIntStateOf(0)
    var lastInput by mutableLongStateOf(SystemClock.uptimeMillis())

    /** Tile rectangles in window pixels, for the system scale-up launch animation. */
    val tileBounds = HashMap<String, Rect>()

    private var centerDown = false
    private var centerDownAt = 0L
    private var longFired = false
    private var swallowUntilUp = false
    private var weatherJob: Job? = null
    private var reloadJob: Job? = null

    val focused: AppEntry? get() = apps.getOrNull(focus)
    /** The app whose Top Shelf is showing: the focused top-row app, or the last one. */
    val shelfApp: AppEntry? get() = apps.getOrNull(if (focus < COLS) focus else lastTop) ?: apps.firstOrNull()
    val slides: List<Slide> get() = shelfApp?.let { Catalog.slides(it) } ?: emptyList()
    val currentSlide: Slide? get() = slides.let { if (it.isEmpty()) null else it[slide.mod(it.size)] }
    val gridMode get() = focus >= COLS && (mode == Mode.Home || mode == Mode.Jiggle || mode == Mode.Sheet)
    val isFireTv: Boolean = activity.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    // ------------------------------------------------------------------ apps
    fun reload(dropArtFor: String? = null) {
        if (dropArtFor != null) visuals.remove(dropArtFor)
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val list = withContext(Dispatchers.IO) { repo.arrange(repo.installed(), prefs.order, prefs.hidden) }
            val keepKey = focused?.key
            apps = list
            focus = list.indexOfFirst { it.key == keepKey }.takeIf { it >= 0 } ?: focus.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            lastTop = lastTop.coerceAtMost((list.size - 1).coerceAtLeast(0))
            loaded = true
            // load missing art; retry art that failed earlier (e.g. the app was mid-update)
            list.filter { e -> visuals[e.key].let { it == null || (!e.isBuiltin && it.banner == null && it.icon == null) } }.forEach { e ->
                launch {
                    val v = artLimit.withPermit { withContext(Dispatchers.IO) { repo.loadVisual(e) } }
                    visuals[e.key] = v
                }
            }
            prefetchShelf()
        }
    }

    /** Decode the photos for the top row ahead of time so the shelf never flashes. */
    private fun prefetchShelf() {
        val photos = apps.take(COLS).flatMap { Catalog.slides(it).map { s -> s.photo } }.distinct()
        scope.launch(Dispatchers.IO) { photos.take(2).forEach { runCatching { Photos.load(activity, it) } } }
    }

    fun colorPalette(): List<Color> {
        val v = shelfApp?.let { visuals[it.key] }
        val a = v?.a ?: Color(0xFF5E5CE6)
        val b = v?.b ?: Color(0xFF2C2A99)
        return listOf(lerp(b, Color.Black, .82f), lerp(b, Color.Black, .25f), a, lerp(a, Color(0xFF7B2FBF), .45f))
    }

    // ------------------------------------------------------------------ input
    fun onKey(e: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode

        if (swallowUntilUp) { if (!down) swallowUntilUp = false; return true }
        if (mode == Mode.Saver) {
            if (down) { mode = Mode.Home; swallowUntilUp = true }
            return true
        }
        if (mode == Mode.CityInput) {
            if (isBack(code)) { if (!down) closeCity(save = false); return true }
            return false
        }

        val dir = dirOf(code)
        val center = isCenter(code)
        val first = down && e.repeatCount == 0
        if (!down && !center) return true

        when (mode) {
            Mode.Home -> {
                if (dir != null) { moveFocus(dir); return true }
                if (center) {
                    if (first) { centerDown = true; centerDownAt = e.eventTime; longFired = false }
                    else if (down && centerDown && !longFired && (e.isLongPress || e.eventTime - centerDownAt >= LONG_PRESS_MS)) { longFired = true; startJiggle() }
                    else if (!down) { if (centerDown && !longFired) activate(); centerDown = false }
                    return true
                }
                if (first) when {
                    isOptions(code) -> openSheet()
                    isControl(code) -> openControl()
                    code == KeyEvent.KEYCODE_PROG_GREEN -> startSaver()
                    isBack(code) -> if (focus != 0) { lastDir = Dir.Up; focus = 0; clicks.tick() }
                }
                return true
            }
            Mode.Shelf -> {
                if (center) { if (first) shelfPress(); else if (!down) centerDown = false; return true }
                if (!first && dir == null) return true
                val n = slides.size
                when {
                    dir == Dir.Left -> if (shelfButton > 0) { shelfButton--; clicks.tick() } else if (n > 1) { slide = (slide - 1).mod(n); slideTick++; clicks.tick() } else bump(Dir.Left)
                    dir == Dir.Right -> if (shelfButton < 1) { shelfButton++; clicks.tick() } else if (n > 1) { slide = (slide + 1).mod(n); slideTick++; clicks.tick() } else bump(Dir.Right)
                    dir == Dir.Down || isBack(code) -> { mode = Mode.Home; lastDir = Dir.Down; clicks.tick() }
                    dir == Dir.Up || isControl(code) -> openControl()
                    isOptions(code) -> openSheet(focus)
                }
                return true
            }
            Mode.Jiggle -> {
                if (dir != null) { moveTile(dir); return true }
                if (center) { if (first && !centerDown) endJiggle() else if (!down) centerDown = false; return true }
                if (first && (isBack(code))) endJiggle()
                if (first && isOptions(code)) { endJiggle(); openSheet() }
                return true
            }
            Mode.Sheet -> {
                if (center) {
                    if (first && !centerDown) { clicks.select(); sheet.getOrNull(sheetIndex)?.action?.invoke() }
                    if (!down) centerDown = false
                    return true
                }
                when {
                    dir == Dir.Up -> step { sheetIndex = (sheetIndex - 1).coerceAtLeast(0) }
                    dir == Dir.Down -> step { sheetIndex = (sheetIndex + 1).coerceAtMost(sheet.size - 1) }
                    first && (isBack(code) || isOptions(code)) -> mode = Mode.Home
                }
                return true
            }
            Mode.Control -> {
                if (center) { if (first) { clicks.select(); controlRows().getOrNull(controlRow)?.getOrNull(controlCol)?.action?.invoke() }; return true }
                val rows = controlRows()
                when (dir) {
                    Dir.Left -> step { controlCol = (controlCol - 1).coerceAtLeast(0) }
                    Dir.Right -> step { controlCol = (controlCol + 1).coerceAtMost(rows[controlRow].size - 1) }
                    Dir.Up -> step { remapCol(rows, (controlRow - 1).coerceAtLeast(0)) }
                    Dir.Down -> step { remapCol(rows, (controlRow + 1).coerceAtMost(rows.size - 1)) }
                    null -> if (first && (isBack(code) || isControl(code))) mode = Mode.Home
                }
                return true
            }
            Mode.Settings -> {
                if (center) { if (first) { clicks.select(); settingsItems().getOrNull(settingsIndex)?.let { it.action?.invoke() ?: it.change?.invoke(1) } }; return true }
                val items = settingsItems()
                when {
                    dir == Dir.Up -> step { settingsIndex = (settingsIndex - 1).coerceAtLeast(0); resetArmed = false }
                    dir == Dir.Down -> step { settingsIndex = (settingsIndex + 1).coerceAtMost(items.size - 1); resetArmed = false }
                    dir == Dir.Left || dir == Dir.Right -> items.getOrNull(settingsIndex)?.change?.let { it(if (dir == Dir.Left) -1 else 1); clicks.tick() }
                    first && isBack(code) -> mode = Mode.Home
                }
                return true
            }
            else -> return false
        }
    }

    /** Runs a focus change; ticks if it moved, bonks if it hit an edge. */
    private inline fun step(change: () -> Unit) {
        val before = marker()
        change()
        if (marker() != before) clicks.tick() else clicks.bonk()
    }
    private fun marker() = listOf(sheetIndex, settingsIndex, controlRow, controlCol).hashCode()

    private fun remapCol(rows: List<List<ControlItem>>, r: Int) {
        if (r == controlRow) return
        val from = rows[controlRow]
        val to = rows[r]
        // keep roughly the same horizontal position between rows of different widths
        val frac = (controlCol + .5f) / from.size
        controlRow = r
        controlCol = (frac * to.size).toInt().coerceIn(0, to.size - 1)
    }

    private fun dirOf(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> Dir.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.Right
        KeyEvent.KEYCODE_DPAD_UP -> Dir.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> Dir.Down
        else -> null
    }
    private fun isCenter(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
    private fun isBack(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B
    private fun isOptions(code: Int) = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_PROG_RED || code == KeyEvent.KEYCODE_INFO
    private fun isControl(code: Int) = code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_PROG_BLUE || code == KeyEvent.KEYCODE_GUIDE

    fun moveFocus(dir: Dir) {
        val n = apps.size
        val i = focus
        val col = i % COLS
        if (dir == Dir.Up && i < COLS) { openShelf(); return }
        val j = when (dir) {
            Dir.Left -> if (col > 0) i - 1 else i
            Dir.Right -> if (col < COLS - 1 && i + 1 < n) i + 1 else i
            Dir.Up -> i - COLS
            Dir.Down -> if (i + COLS < n) i + COLS else if (i / COLS < (n - 1) / COLS) n - 1 else i
        }
        lastDir = dir
        if (j == i) { bump(dir); return }
        focusOn(j)
        clicks.tick()
    }

    private fun focusOn(j: Int) {
        val oldTop = if (focus < COLS) focus else lastTop
        focus = j
        if (j < COLS) lastTop = j
        if ((if (j < COLS) j else lastTop) != oldTop) { slide = 0; slideTick++ }
        moveTick++
    }

    private fun bump(dir: Dir) { bumpDir = dir; bumpTick++; clicks.bonk() }

    fun setFocusFromPointer(i: Int) { if (mode == Mode.Home && i in apps.indices && i != focus) focusOn(i) }

    // ------------------------------------------------------------------ Top Shelf
    private fun openShelf() {
        if (slides.isEmpty()) return bump(Dir.Up)
        shelfButton = 0
        mode = Mode.Shelf
        clicks.tick()
    }

    private fun shelfPress() {
        clicks.select()
        val app = shelfApp ?: return
        if (shelfButton == 0) { val i = apps.indexOf(app); if (i >= 0) activate(i) } else openSheet(apps.indexOf(app))
    }

    fun nextSlide() { val n = slides.size; if (n > 1) slide = (slide + 1).mod(n) }

    // ------------------------------------------------------------------ actions
    fun activate(i: Int = focus) {
        val e = apps.getOrNull(i) ?: return
        clicks.select()
        if (e.key == SETTINGS_KEY) return openSettings()
        val intent = repo.launchIntent(e) ?: return
        val r = tileBounds[e.key]
        val view = activity.window.decorView
        val opts = if (r != null && mode == Mode.Home) ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt()).toBundle() else null
        runCatching { activity.startActivity(intent, opts) }.onFailure { show("Couldn’t open ${e.label}") }
    }

    fun openSheet(i: Int = focus) {
        val e = apps.getOrNull(i) ?: return
        val items = mutableListOf(
            MenuItem("Open") { mode = Mode.Home; activate(i) },
            MenuItem("Move") { focus = i; startJiggle() },
        )
        if (!e.isBuiltin) {
            items += MenuItem("Hide from Home") { hide(e) }
            items += MenuItem("App Info") { mode = Mode.Home; appInfo(e) }
            if (!e.system) items += MenuItem("Delete App", danger = true) { mode = Mode.Home; uninstall(e) }
        }
        items += MenuItem("Cancel") { mode = Mode.Home }
        sheetApp = e
        sheet = items
        sheetIndex = 0
        centerDown = false
        mode = Mode.Sheet
    }

    private fun hide(e: AppEntry) {
        prefs.order = apps.map { it.key }
        prefs.hidden = prefs.hidden + e.key
        mode = Mode.Home
        apps = apps.filter { it.key != e.key }
        focus = focus.coerceAtMost(apps.size - 1)
        show("${e.label} is hidden. Show it again in Settings.")
    }

    private fun appInfo(e: AppEntry) = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", e.packageName, null)))

    @Suppress("DEPRECATION")
    private fun uninstall(e: AppEntry) = start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName, null)))

    private fun start(i: Intent): Boolean = runCatching {
        activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse { show("Not available on this TV"); false }

    // ------------------------------------------------------------------ edit (jiggle) mode
    private fun startJiggle() {
        mode = Mode.Jiggle
        clicks.select()
        show("Use the arrows to move · OK when done · ≡ for options")
    }

    private fun moveTile(dir: Dir) {
        val i = focus
        val n = apps.size
        val col = i % COLS
        val j = when (dir) {
            Dir.Left -> if (col > 0) i - 1 else i
            Dir.Right -> if (col < COLS - 1 && i + 1 < n) i + 1 else i
            Dir.Up -> if (i - COLS >= 0) i - COLS else i
            Dir.Down -> if (i + COLS < n) i + COLS else i
        }
        lastDir = dir
        if (j == i) { bump(dir); return }
        val list = apps.toMutableList()
        val item = list.removeAt(i)
        list.add(j, item)
        apps = list
        focusOn(j)
        clicks.tick()
    }

    private fun endJiggle() {
        mode = Mode.Home
        prefs.order = apps.map { it.key }
        clicks.select()
    }

    // ------------------------------------------------------------------ Control Center
    fun openControl() {
        controlRow = 0; controlCol = 0
        mode = Mode.Control
        clicks.select()
    }

    fun controlRows(): List<List<ControlItem>> = listOf(
        listOf(
            ControlItem("Aerials", "moon") { startSaver() },
            ControlItem("Wi-Fi", "wifi") { mode = Mode.Home; startAny(Settings.ACTION_WIFI_SETTINGS) },
            ControlItem("Display", "display") { mode = Mode.Home; startAny(Settings.ACTION_DISPLAY_SETTINGS) },
            ControlItem("Sound", "sound") { mode = Mode.Home; startAny(Settings.ACTION_SOUND_SETTINGS) },
            ControlItem("Bluetooth", "bluetooth") { mode = Mode.Home; startAny(Settings.ACTION_BLUETOOTH_SETTINGS) },
        ),
        listOf(
            ControlItem(place.ifEmpty { "Weather" }, "weather", wide = true, detail = weather?.let { "${it.temp}°  ${Weather.describe(it.code)}" } ?: "Set a city") {
                if (place.isEmpty()) { cityDraft = prefs.city; mode = Mode.CityInput } else refreshWeather()
            },
            ControlItem("Home Settings", "gear", wide = true, detail = "Top Shelf & sounds") { openSettings() },
        ),
        listOf(
            ControlItem("TV Settings", "sliders", wide = true, detail = "Network & accounts") { mode = Mode.Home; startAny(Settings.ACTION_SETTINGS) },
            ControlItem("Get Apps", "search", wide = true, detail = "Open the app store") { mode = Mode.Home; openStore() },
        ),
    )

    private fun startAny(action: String) { if (!runCatching { activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) start(Intent(Settings.ACTION_SETTINGS)) }

    private fun openStore() {
        val store = listOf("com.android.vending", "com.amazon.venezia").firstNotNullOfOrNull { activity.packageManager.getLeanbackLaunchIntentForPackage(it) ?: activity.packageManager.getLaunchIntentForPackage(it) }
        if (store != null) start(store) else show("No app store found")
    }

    // ------------------------------------------------------------------ settings
    fun openSettings() { settingsIndex = 0; resetArmed = false; mode = Mode.Settings }

    fun settingsItems(): List<SettingItem> {
        val hiddenCount = prefs.hidden.size
        fun <T> cycle(list: List<T>, cur: T, d: Int) = list[(list.indexOf(cur) + d + list.size) % list.size]
        return listOf(
            SettingItem("Top Shelf", if (photoShelf) "Photos" else "Colours", "Show landscape photography behind the top row, or soft colours taken from each app.",
                change = { photoShelf = !photoShelf; prefs.photoShelf = photoShelf }),
            SettingItem("Screensaver", if (saverMinutes == 0) "Never" else "After $saverMinutes min", "Aerial photography starts after Home has been idle this long.",
                change = { d -> saverMinutes = cycle(SAVER_STEPS, saverMinutes, d); prefs.saverMinutes = saverMinutes }),
            SettingItem("Show Clock", if (showClock) "On" else "Off", "Show the time in the top corner of the Home screen.",
                change = { showClock = !showClock; prefs.showClock = showClock }),
            SettingItem("Time Format", if (clock24) "24-Hour" else "12-Hour", "Choose how the time is written.",
                change = { clock24 = !clock24; prefs.clock24 = clock24 }),
            SettingItem("Weather", place.ifEmpty { "Off" }, "Show the current temperature next to the clock. Press OK to choose a city.",
                action = { cityDraft = prefs.city; mode = Mode.CityInput }),
            SettingItem("Click Sounds", if (clickSounds) "On" else "Off", "Play a soft tick as focus moves, like Apple TV.",
                change = { clickSounds = !clickSounds; clicks.enabled = clickSounds; prefs.clicks = clickSounds }),
            SettingItem("Reduce Motion", if (calm) "On" else "Off", "Stop the slow drift of photos and the shine on tiles.",
                change = { calm = !calm; prefs.calm = calm }),
            SettingItem("Hidden Apps", if (hiddenCount == 0) "None" else "$hiddenCount", "Press OK to put every hidden app back on the Home screen.",
                action = { if (hiddenCount > 0) { prefs.hidden = emptySet(); reload(); show("All apps are back on Home") } }),
            SettingItem("Make Home the Default", null, if (isFireTv) "On Fire TV, use the free Home on Fire app to send the Home button here." else "Open the system setting that chooses your home screen.",
                action = { makeDefault() }),
            SettingItem("Reset Home Screen", if (resetArmed) "Press OK Again" else null, "Put every app back in its original place.", danger = true, action = {
                if (resetArmed) {
                    prefs.reset()
                    resetArmed = false
                    mode = Mode.Home
                    focus = 0
                    lastTop = 0
                    reload()
                    show("Home Screen reset")
                } else resetArmed = true
            }),
        )
    }

    private fun makeDefault() {
        if (isFireTv) { show("On Fire TV, install “Home on Fire” and choose Home"); return }
        mode = Mode.Home
        if (!runCatching { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess)
            show("Use the ADB steps in the guide to make Home the default")
    }

    // ------------------------------------------------------------------ weather
    fun closeCity(save: Boolean) {
        mode = Mode.Settings
        if (!save) return
        val city = cityDraft.trim()
        prefs.city = city
        if (city.isEmpty()) { place = ""; prefs.place = ""; prefs.lat = Double.NaN; weather = null; return }
        scope.launch {
            val g = withContext(Dispatchers.IO) { Weather.geocode(city) }
            if (g == null) { show("Couldn’t find “$city”"); return@launch }
            prefs.lat = g.lat; prefs.lon = g.lon; prefs.place = g.name; place = g.name
            refreshWeather()
        }
    }

    fun refreshWeather() {
        weatherJob?.cancel()
        val lat = prefs.lat
        val lon = prefs.lon
        if (lat.isNaN() || lon.isNaN()) { weather = null; return }
        weatherJob = scope.launch {
            while (true) {
                withContext(Dispatchers.IO) { Weather.current(lat, lon) }?.let { weather = it }
                delay(30 * 60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ screensaver & misc
    fun startSaver() { if (mode != Mode.CityInput) { if (mode == Mode.Jiggle) endJiggle(); mode = Mode.Saver } }

    fun goHome() {
        if (mode == Mode.Jiggle) endJiggle()
        mode = Mode.Home
        lastDir = Dir.Up
        if (focus != 0) focusOn(0)
    }

    fun show(msg: String) { toast = msg; toastTick++ }

    fun dispose() { scope.cancel(); clicks.release() }
}
