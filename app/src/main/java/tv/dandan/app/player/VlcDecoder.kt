package tv.dandan.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.sourceForUri
import tv.dandan.core.AppSettings
import tv.dandan.core.MediaEntry

@UnstableApi
internal class VlcDecoder(private val context: Context, private val repository: AppRepository, settings: AppSettings, font: java.io.File?, private val changed: () -> Unit, private val failed: (String) -> Unit, private val ended: () -> Unit) : DecoderEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val lib = LibVLC(context, listOf("--no-video-title-show", "--no-sub-autodetect-file", "--network-caching=1200", "--freetype-fontsize=${(settings.subtitleSizeSp * context.resources.displayMetrics.scaledDensity).toInt().coerceIn(12, 160)}", "--freetype-color=${settings.subtitleColor and 0xffffff}", "--freetype-outline-thickness=${SubtitleOutline.vlcThickness(settings.subtitleStroke)}") + (font?.let { listOf("--ssa-fontsdir=${it.parentFile!!.absolutePath}") } ?: emptyList()))
    private val player = MediaPlayer(lib)
    override val view = VLCVideoLayout(context)
    private var relay: MediaRelay? = null
    private var closed = false
    private var desiredPlay = false
    private var targetPosition = 0L
    private var lastPosition = 0L
    private var targetSpeed = 1f
    private var targetVolume = 100
    private var restore: DecoderRestore? = null
    private var subtitleSettings = settings
    private var seekRestored = false
    private var focusHeld = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!closed) when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setVolume(targetVolume / 5)
                AudioManager.AUDIOFOCUS_GAIN -> player.setVolume(targetVolume)
            }
        }, handler).build()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if (!closed) pause() } }
    override var state = DecoderState.IDLE
        private set
    override var bufferedPercent = 0
        private set
    init {
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        // TextureView 保证弹幕和字幕覆盖层位于视频之上。
        player.attachViews(view, null, true, true)
        player.setEventListener { event ->
            if (!closed) {
                when (event.type) {
                    MediaPlayer.Event.Opening -> state = DecoderState.BUFFERING
                    MediaPlayer.Event.Buffering -> {
                        bufferedPercent = event.buffering.toInt().coerceIn(0, 100)
                        if (bufferedPercent < 100) state = DecoderState.BUFFERING
                    }
                    MediaPlayer.Event.Playing -> {
                        state = DecoderState.READY
                        if (!seekRestored) {
                            if (targetPosition > 0) player.setTime(targetPosition)
                            seekRestored = true
                            player.rate = targetSpeed
                            applyTracks()
                        }
                        player.setSpuDelay(subtitleSettings.subtitleDelayMs * 1000)
                        if (!desiredPlay) player.pause()
                        player.setVolume(targetVolume)
                    }
                    MediaPlayer.Event.Paused -> state = DecoderState.READY
                    MediaPlayer.Event.TimeChanged -> lastPosition = event.timeChanged.coerceAtLeast(0)
                    MediaPlayer.Event.ESAdded -> applyTracks()
                    MediaPlayer.Event.EndReached -> { state = DecoderState.ENDED; desiredPlay = false; ended() }
                    MediaPlayer.Event.EncounteredError -> { state = DecoderState.FAILED; failed("VLC 无法解码或读取当前媒体，可重试或切换 Media3 内核") }
                }
                if (!closed) changed()
            }
        }
    }
    override val position get() = if (state == DecoderState.ENDED && duration > 0) duration else if (state == DecoderState.FAILED) lastPosition else if (!seekRestored) targetPosition else player.time.takeIf { it >= 0 } ?: lastPosition
    override val duration get() = player.length.coerceAtLeast(0)
    override val playing get() = desiredPlay && player.isPlaying
    override val requestedPlay get() = desiredPlay
    override val speed get() = targetSpeed
    override val volumePercent get() = targetVolume
    override val seekable get() = player.isSeekable
    override fun prepare(entry: MediaEntry, restore: DecoderRestore, subtitle: ExternalSubtitle?, settings: AppSettings) {
        player.stop()
        relay?.close(); relay = null
        this.restore = restore
        targetPosition = restore.position
        lastPosition = restore.position
        targetSpeed = restore.speed
        targetVolume = restore.volumePercent
        desiredPlay = restore.play
        seekRestored = false
        subtitleSettings = settings
        state = DecoderState.BUFFERING
        bufferedPercent = 0
        val parsed = Uri.parse(entry.uri)
        val source = sourceForUri(repository.sources(), entry.uri, entry.sourceId)
        // 无凭据的公开流保留原 URL，使 HLS / DASH 能解析分片相对地址。
        val publicHttp = parsed.scheme in setOf("http", "https") && parsed.userInfo == null && source?.let { it.username.isNotBlank() || it.password.isNotBlank() } != true
        val address = if (publicHttp) entry.uri else MediaRelay(context, repository, entry.uri).also { relay = it }.url
        val media = Media(lib, Uri.parse(address))
        try {
            media.setHWDecoderEnabled(true, false)
            media.addOption(":start-time=${restore.position / 1000.0}")
            media.addOption(":sub-autodetect-file=0")
            if (subtitle != null) media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, subtitle.uri))
            player.media = media
        } finally { media.release() }
        player.setVolume(0)
        if (desiredPlay && !acquireFocus()) desiredPlay = false
        // VLC 的 prepare 由 play 驱动；首个 Playing 事件按保存的状态暂停，初始化阶段静音。
        player.play()
    }
    private fun acquireFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }
    override fun play() {
        if (!acquireFocus()) throw IllegalArgumentException("无法获得音频焦点，请稍后重试")
        desiredPlay = true
        if (state == DecoderState.ENDED) { targetPosition = 0; player.setTime(0) }
        player.play()
    }
    override fun pause() {
        desiredPlay = false
        player.pause()
        if (focusHeld) { audioManager.abandonAudioFocusRequest(audioFocus); focusHeld = false }
        changed()
    }
    override fun seek(position: Long) { targetPosition = position; lastPosition = position; if (seekRestored) player.setTime(position) }
    override fun speed(value: Float) { targetSpeed = value; player.rate = value }
    override fun volume(value: Int) { targetVolume = value; if (seekRestored) player.setVolume(value) }
    override fun tracks(kind: DecoderTrackKind): List<DecoderTrack> {
        val selected = if (kind == DecoderTrackKind.AUDIO) player.audioTrack else player.spuTrack
        return (if (kind == DecoderTrackKind.AUDIO) player.audioTracks else player.spuTracks).orEmpty().filter { it.id >= 0 }.mapIndexed { index, track -> DecoderTrack(track.id.toString(), "轨道 ${index + 1} · ${track.name.orEmpty()}", track.id == selected) }
    }
    override fun select(kind: DecoderTrackKind, id: String?) {
        restore = null
        if (kind == DecoderTrackKind.AUDIO) {
            val track = id?.toIntOrNull() ?: tracks(kind).firstOrNull()?.id?.toIntOrNull() ?: return
            if (!player.setAudioTrack(track)) throw IllegalArgumentException("VLC 暂时无法选择该音轨")
        } else if (!player.setSpuTrack(id?.toIntOrNull() ?: -1)) throw IllegalArgumentException("VLC 暂时无法选择该字幕轨")
    }
    private fun applyTracks() {
        val saved = restore ?: return
        saved.audioOrdinal?.let { tracks(DecoderTrackKind.AUDIO).getOrNull(it)?.let { track -> player.setAudioTrack(track.id.toInt()) } }
        if (saved.textDisabled) player.setSpuTrack(-1)
        else saved.textOrdinal?.let { tracks(DecoderTrackKind.TEXT).getOrNull(it)?.let { track -> player.setSpuTrack(track.id.toInt()) } }
    }
    override fun fit(value: VideoFit) { player.setVideoScale(when (value) { VideoFit.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT; VideoFit.FILL -> MediaPlayer.ScaleType.SURFACE_FILL; VideoFit.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; VideoFit.WIDTH -> MediaPlayer.ScaleType.SURFACE_16_9; VideoFit.HEIGHT -> MediaPlayer.ScaleType.SURFACE_4_3 }) }
    override fun subtitleStyle(settings: AppSettings) { subtitleSettings = settings; player.setSpuDelay(settings.subtitleDelayMs * 1000) }
    override fun subtitleFont(file: java.io.File?) { /* 字体在 LibVLC 建立时提供，由会话重建并恢复播放。 */ }
    override fun close() {
        if (closed) return
        closed = true
        desiredPlay = false
        player.setEventListener(null)
        // 先关闭转发连接，解除 native stop 等待来源读取的阻塞。
        relay?.close(); relay = null
        player.stop()
        player.detachViews()
        player.release()
        lib.release()
        if (focusHeld) { audioManager.abandonAudioFocusRequest(audioFocus); focusHeld = false }
        runCatching { context.unregisterReceiver(noisy) }
    }
}
