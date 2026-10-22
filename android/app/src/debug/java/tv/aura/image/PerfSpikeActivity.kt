package tv.aura.image

import com.kyant.capsule.ContinuousRoundedRectangle
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** DEBUG manifest only. No production UI wiring; deterministic, network-free renderer comparison. */
class PerfSpikeActivity : ComponentActivity() {
    private val selected = mutableIntStateOf(0)
    private val cards = mutableListOf<View>()
    private var viewBackdrop: ImageView? = null
    private var scene = "compose"
    private var previousViewFocus = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        scene = intent.getStringExtra("scene") ?: "compose"
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                val decoded = assets.open("photos/p00.webp").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565; inScaled = false
                    })!!
                }
                val full = Bitmap.createScaledBitmap(decoded, 1280, 720, true)
                if (full !== decoded) decoded.recycle()
                val tiny = Bitmap.createScaledBitmap(full, 320, 180, true)
                var blur: Bitmap? = null
                for (level in 1..3) repeat(3) { run ->
                    val started = android.os.Debug.threadCpuTimeNanos()
                    val sample = BackdropBlur.apply(tiny, level)
                    Log.i("AuraPerfSpike", "BLUR level=$level run=$run size=320x180 cpuMs=${(android.os.Debug.threadCpuTimeNanos() - started) / 1_000_000.0}")
                    if (level == 1 && run == 2) blur = sample else sample.recycle()
                }
                if (tiny !== full) tiny.recycle()
                full to checkNotNull(blur)
            }
            if (scene == "views") renderViews(images.first, images.second)
            else setContent { ComposeScene(images.first, images.second, scene != "compose-static") }
            Log.i("AuraPerfSpike", "READY scene=$scene art=${images.first.width}x${images.first.height} config=${images.first.config}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            selected.intValue = (selected.intValue + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, 5)
            if (scene == "views") updateViewFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Composable private fun ComposeScene(full: Bitmap, blur: Bitmap, moving: Boolean) {
        val art = androidx.compose.runtime.remember(full) { full.asImageBitmap() }
        val glass = androidx.compose.runtime.remember(blur) { blur.asImageBitmap() }
        val motion = if (moving) rememberInfiniteTransition(label = "probe").animateFloat(
            0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "kenburns") else null
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(art, null, Modifier.fillMaxSize().graphicsLayer {
                val t = motion?.value ?: 0f
                scaleX = 1.02f + .06f * t; scaleY = scaleX
                translationX = (-8f + 16f * t).dp.toPx()
            }, contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).fillMaxWidth().height(140.dp)) {
                Image(glass, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0x990e1622)))
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (index in 0..5) {
                    val focused = selected.intValue == index
                    val scale by animateFloatAsState(if (focused) 1.12f else 1f, spring(.8f, 500f), label = "card$index")
                    val lift by animateFloatAsState(if (focused) -6f else 0f, spring(.8f, 500f), label = "lift$index")
                    Box(Modifier.size(120.dp, 68.dp).graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationY = lift.dp.toPx()
                    }.clip(if (scene == "compose-capsule") ContinuousRoundedRectangle(8.dp) else RoundedCornerShape(8.dp)).background(Color(COLORS[index])), contentAlignment = Alignment.Center) {
                        // BasicText avoids Material theme and extra state in this diagnostic scene.
                        androidx.compose.foundation.text.BasicText("App ${index + 1}", style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp))
                    }
                }
            }
        }
    }

    private fun renderViews(full: Bitmap, blur: Bitmap) {
        val root = FrameLayout(this).apply { setBackgroundColor(AndroidColor.BLACK) }
        val background = ImageView(this).apply { setImageBitmap(full); scaleType = ImageView.ScaleType.CENTER_CROP }
        viewBackdrop = background
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        val strip = FrameLayout(this)
        strip.addView(ImageView(this).apply { setImageBitmap(blur); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        strip.addView(View(this).apply { setBackgroundColor(0x990e1622.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(strip, FrameLayout.LayoutParams(-1, dp(140f), Gravity.BOTTOM).apply { bottomMargin = dp(28f) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        for (index in 0..5) {
            val card = TextView(this).apply {
                text = "App ${index + 1}"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(AndroidColor.WHITE)
                this.background = GradientDrawable().apply { setColor(COLORS[index]); cornerRadius = dp(8f).toFloat() }
            }
            cards += card
            row.addView(card, LinearLayout.LayoutParams(dp(120f), dp(68f)).apply { if (index < 5) rightMargin = dp(18f) })
        }
        root.addView(row, FrameLayout.LayoutParams(-2, dp(68f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(64f) })
        setContentView(root)
        background.scaleX = 1.02f; background.scaleY = 1.02f; background.translationX = -dp(8f).toFloat()
        // A single 60s property animation covers the entire test without per-frame application callbacks.
        background.animate().scaleX(1.08f).scaleY(1.08f).translationX(dp(8f).toFloat()).setDuration(60_000)
            .setInterpolator(LinearInterpolator()).start()
        updateViewFocus()
    }

    private fun updateViewFocus() {
        cards.forEachIndexed { index, card ->
            if (index != previousViewFocus && index != selected.intValue) return@forEachIndexed
            val focused = selected.intValue == index
            card.animate().scaleX(if (focused) 1.12f else 1f).scaleY(if (focused) 1.12f else 1f)
                .translationY(if (focused) -dp(6f).toFloat() else 0f).setDuration(280)
                .setInterpolator(OvershootInterpolator(.6f)).start()
        }
        previousViewFocus = selected.intValue
    }

    override fun onDestroy() { viewBackdrop?.animate()?.cancel(); cards.forEach { it.animate().cancel() }; super.onDestroy() }
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private val COLORS = intArrayOf(0xffb62b35.toInt(), 0xff365ed9.toInt(), 0xff188aba.toInt(),
            0xff247a57.toInt(), 0xff7941af.toInt(), 0xffbd711c.toInt())
    }
}

