package tv.aura.system

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.aura.core.NowPlaying

/** All controller callbacks and artwork work stay on a single non-UI handler thread. */
internal class AuraMediaSessions(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, AuraNotificationListener::class.java)
    private val thread = HandlerThread("AuraMediaSessions").apply { start() }
    private val handler = Handler(thread.looper)
    private val state = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = state
    private val controllers = linkedMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var selected: MediaController? = null
    private var registered = false
    @Volatile private var closed = false
    private var artSource: Bitmap? = null
    private var artCopy: androidx.compose.ui.graphics.ImageBitmap? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }
    private val connectionJob: Job
    private val ticker: Job

    init {
        connectionJob = scope.launch {
            AuraNotificationListener.connection.collect { handler.post { reconnect() } }
        }
        ticker = scope.launch {
            while (true) {
                delay(1_000)
                handler.post { if (!closed && state.value?.playing == true) publish() }
            }
        }
        handler.post { reconnect() }
    }

    private fun reconnect() {
        if (closed) return
        runCatching {
            if (!registered) {
                manager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                registered = manager != null
            }
            update(manager?.getActiveSessions(listenerComponent).orEmpty())
        }.onFailure {
            if (registered) runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            registered = false
            update(emptyList())
        }
    }

    private fun update(active: List<MediaController>) {
        if (closed) return
        val tokens = active.map { it.sessionToken }.toSet()
        controllers.keys.toList().filter { it !in tokens }.forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        }
        active.forEach { controller ->
            if (controller.sessionToken !in controllers) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                    override fun onSessionDestroyed() { reconnect() }
                }
                if (runCatching { controller.registerCallback(callback, handler) }.isSuccess)
                    controllers[controller.sessionToken] = controller to callback
            }
        }
        publish()
    }

    private fun publish() {
        if (closed) return
        val controller = controllers.values.map { it.first }.maxWithOrNull(
            compareBy<MediaController> { runCatching { priority(it.playbackState?.state) }.getOrDefault(0) }
                .thenBy { runCatching { it.playbackState?.lastPositionUpdateTime ?: 0L }.getOrDefault(0L) })
        selected = controller
        if (controller == null) {
            state.value = null
            artSource = null
            artCopy = null
            return
        }
        state.value = runCatching {
            val metadata = controller.metadata
            val playback = controller.playbackState
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bitmap !== artSource) {
                artSource = bitmap
                artCopy = bitmap?.takeUnless { it.isRecycled }?.let { original ->
                    val scale = minOf(1f, 640f / maxOf(original.width, original.height))
                    val resized = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1), true)
                    // Never recycle metadata owned by another session/framework object.
                    if (resized === original) original.copy(Bitmap.Config.RGB_565, false)?.asImageBitmap()
                    else resized.asImageBitmap()
                }
            }
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            val playing = playback?.state == PlaybackState.STATE_PLAYING
            val position = playback?.position?.takeIf { it >= 0 }?.let { position ->
                val advance = if (playing && playback.lastPositionUpdateTime > 0)
                    ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0) * playback.playbackSpeed).toLong()
                else 0L
                (position + advance).coerceAtLeast(0).let { if (duration != null) minOf(it, duration) else it }
            }
            NowPlaying(controller.packageName,
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                artCopy, playing, position, duration)
        }.getOrNull()
    }

    fun toggle() { handler.post {
        if (!closed) runCatching { selected?.let {
            val state = it.playbackState ?: return@let
            if (state.state == PlaybackState.STATE_PLAYING && state.actions and PlaybackState.ACTION_PAUSE != 0L)
                it.transportControls.pause()
            else if (state.state != PlaybackState.STATE_PLAYING && state.actions and PlaybackState.ACTION_PLAY != 0L)
                it.transportControls.play()
            else if (state.actions and PlaybackState.ACTION_PLAY_PAUSE != 0L) {
                if (state.state == PlaybackState.STATE_PLAYING) it.transportControls.pause() else it.transportControls.play()
            }
        } }
    } }

    override fun close() {
        closed = true
        connectionJob.cancel()
        ticker.cancel()
        handler.post {
            if (registered) runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            controllers.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
            controllers.clear()
            selected = null
            artSource = null
            artCopy = null
            state.value = null
            thread.quitSafely()
        }
    }

    private fun priority(state: Int?): Int = when (state) {
        PlaybackState.STATE_PLAYING -> 4
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 3
        PlaybackState.STATE_PAUSED -> 2
        PlaybackState.STATE_STOPPED -> 1
        else -> 0
    }
}
