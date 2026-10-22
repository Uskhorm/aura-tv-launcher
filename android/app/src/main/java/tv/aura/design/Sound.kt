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

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The soft tvOS-style "tick" on every focus move, synthesised at first launch (no audio files). */
class Clicks(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val tick = pool.load(synth(ctx, "tick.wav", 3100.0, 9.0, .25), 1)
    private val select = pool.load(synth(ctx, "select.wav", 1500.0, 16.0, .12), 1)
    private val bonk = pool.load(synth(ctx, "bonk.wav", 420.0, 22.0, .05), 1)

    var enabled = true

    fun tick() = play(tick, .32f)
    fun select() = play(select, .45f)
    fun bonk() = play(bonk, .5f)

    private fun play(id: Int, v: Float) { if (enabled) pool.play(id, v, v, 0, 0, 1f) }

    private fun synth(ctx: Context, name: String, freq: Double, ms: Double, noise: Double): String {
        val f = File(ctx.cacheDir, name)
        if (f.exists()) return f.path
        val rate = 44100
        val n = (rate * ms * 4 / 1000).toInt()
        val rnd = java.util.Random(1)
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var lp = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = exp(-t * 1000 / ms) * (1 - exp(-t * 20000))
            lp += ((rnd.nextDouble() * 2 - 1) - lp) * .35
            val s = (sin(2 * PI * freq * t) * (1 - noise) + lp * noise) * env * .8
            pcm.putShort((s * Short.MAX_VALUE).toInt().toShort())
        }
        val data = pcm.array()
        FileOutputStream(f).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(data.size)
            out.write(h.array()); out.write(data)
        }
        return f.path
    }

    fun release() = pool.release()
}
