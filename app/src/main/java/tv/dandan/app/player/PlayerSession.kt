package tv.dandan.app.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import tv.dandan.app.data.AppRepository
import tv.dandan.core.*

@UnstableApi
class PlayerSession(
    private val context: Context,
    private val repository: AppRepository,
    val queue: List<MediaEntry>,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onExit: () -> Unit,
    private val onConfigure: (AppSettings) -> Unit,
) : PlaybackController {
    private val owner = DecoderOwner<DecoderEngine>()
    private val decoder get() = owner.current
    private var host: FrameLayout? = null
    private var released = false
    private var replacing = false
    private var foreground = true
    private var configuration = AppSettings()
    private var subtitle: ExternalSubtitle? = null
    private var textDisabled = false
    private var audioOrdinal: Int? = null
    private var textOrdinal: Int? = null
    private var fit = VideoFit.FIT
    private var font: java.io.File? = null
    private var lastRestore = DecoderRestore()
    var backend = PlaybackEngine.MEDIA3
        private set
    var current: MediaEntry? = null
        private set
    var index = 0
        private set
    var error: String? = null
        private set
    val state get() = decoder?.state ?: if (error != null) DecoderState.FAILED else DecoderState.IDLE
    val position get() = decoder?.position ?: lastRestore.position
    val requestedPlay get() = decoder?.requestedPlay ?: lastRestore.play
    val loading get() = decoder?.loading
    val bufferedAheadMs get() = decoder?.bufferedAheadMs ?: -1
    private val duration get() = decoder?.let { if (it.state == DecoderState.FAILED) it.duration.takeIf { value -> value > 0 } ?: lastRestore.duration else it.duration } ?: lastRestore.duration
    val overlaySubtitle get() = backend == PlaybackEngine.MEDIA3 && subtitle != null && subtitle?.mimeType != MimeTypes.APPLICATION_TTML && !textDisabled
    private val mediaSession = MediaSession(context, "弹弹 TV 播放会话").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { runCatching { command("play", emptyMap()) } }
            override fun onPause() { runCatching { command("pause", emptyMap()) } }
            override fun onStop() { runCatching { command("exit", emptyMap()) } }
            override fun onSeekTo(pos: Long) { runCatching { command("seek", mapOf("position" to pos.toString())) } }
            override fun onSetPlaybackSpeed(speed: Float) { runCatching { command("speed", mapOf("speed" to speed.toString())) } }
        })
        isActive = true
    }

    fun attach(container: FrameLayout) {
        ensureOpen()
        host = container
        attachDecoder()
    }
    private fun attachDecoder() {
        host?.let { container ->
            container.removeAllViews()
            decoder?.view?.let { view ->
                view.isFocusable = false
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }
    fun prepare(entry: MediaEntry, progress: PlaybackProgress?, speed: Float, position: Long? = null, play: Boolean = true, settings: AppSettings = configuration) {
        ensureOpen()
        val newMedia = current?.id != entry.id
        if (newMedia) { subtitle = null; textDisabled = false; audioOrdinal = null; textOrdinal = null }
        current = entry
        configuration = settings
        index = queue.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        val restore = DecoderRestore(position ?: PlaybackRules.resume(progress), play, speed, decoder?.volumePercent ?: lastRestore.volumePercent, textDisabled, audioOrdinal, textOrdinal, progress?.durationMs ?: 0).forForeground(foreground)
        loadDecoder(settings.playbackEngine, restore)
    }
    private fun createDecoder(engine: PlaybackEngine): DecoderEngine = when (engine) {
        PlaybackEngine.MEDIA3 -> Media3Decoder(context, repository, ::changed, ::failed, ::ended)
        PlaybackEngine.VLC -> VlcDecoder(context, repository, configuration, font, ::changed, ::failed, ::ended)
    }
    private fun loadDecoder(engine: PlaybackEngine, restore: DecoderRestore) {
        val media = current ?: return
        replacing = true
        error = null
        lastRestore = restore
        backend = engine
        try {
            val next = owner.replace { createDecoder(engine) }
            attachDecoder()
            next.fit(fit)
            next.subtitleFont(font)
            next.prepare(media, restore.forForeground(foreground), subtitle, configuration)
        } catch (_: Exception) {
            owner.close()
            failed("${if (engine == PlaybackEngine.VLC) "VLC" else "Media3"} 内核无法初始化，请重试或更换内核")
        } finally { replacing = false }
        changed()
    }
    /** 外部字幕、弹幕、原始媒体和 PhoneInputBroker 都留在同一会话中。 */
    fun switchBackend(engine: PlaybackEngine) {
        ensureOpen()
        if (engine == backend && error == null) return
        val saved = capture()
        configuration = configuration.copy(playbackEngine = engine)
        loadDecoder(engine, saved)
    }
    fun retry() { ensureOpen(); loadDecoder(backend, capture().copy(play = foreground)) }
    private fun capture(): DecoderRestore {
        val active = decoder
        return DecoderRestore(active?.position ?: lastRestore.position, active?.requestedPlay ?: lastRestore.play, active?.speed ?: lastRestore.speed, active?.volumePercent ?: lastRestore.volumePercent, textDisabled, audioOrdinal, textOrdinal, active?.duration?.takeIf { it > 0 } ?: lastRestore.duration)
    }
    fun setForeground(active: Boolean) { ensureOpen(); foreground = active; if (!active) pause() }
    fun pause() { ensureOpen(); lastRestore = lastRestore.copy(play = false); decoder?.pause() }
    fun tracks(kind: DecoderTrackKind) = decoder?.tracks(kind).orEmpty()
    fun selectTrack(kind: DecoderTrackKind, track: DecoderTrack?) {
        ensureOpen()
        val available = tracks(kind)
        if (kind == DecoderTrackKind.TEXT) {
            if (subtitle != null) { subtitle = null; textDisabled = track == null; textOrdinal = track?.let { available.indexOfFirst { candidate -> candidate.id == it.id }.coerceAtLeast(0) }; loadDecoder(backend, capture()); return }
            textDisabled = track == null
            textOrdinal = track?.let { available.indexOfFirst { candidate -> candidate.id == it.id }.coerceAtLeast(0) }
        } else audioOrdinal = track?.let { available.indexOfFirst { candidate -> candidate.id == it.id }.coerceAtLeast(0) }
        decoder?.select(kind, track?.id)
        changed()
    }
    fun externalSubtitle(value: ExternalSubtitle?) {
        ensureOpen()
        subtitle = value
        textDisabled = false
        textOrdinal = null
        loadDecoder(backend, capture())
    }
    fun subtitleStyle(settings: AppSettings) {
        ensureOpen()
        val rebuild = backend == PlaybackEngine.VLC && (configuration.subtitleSizeSp != settings.subtitleSizeSp || configuration.subtitleColor != settings.subtitleColor || configuration.subtitleStroke != settings.subtitleStroke)
        configuration = settings
        if (rebuild && current != null) loadDecoder(backend, capture()) else decoder?.subtitleStyle(settings)
    }
    fun subtitleFont(file: java.io.File?) {
        ensureOpen()
        font = file
        if (backend == PlaybackEngine.VLC && current != null) loadDecoder(backend, capture()) else { decoder?.subtitleFont(file); decoder?.subtitleStyle(configuration) }
    }
    fun setFit(value: VideoFit) { ensureOpen(); fit = value; decoder?.fit(value) }
    fun snapshot() = current?.let { PlaybackProgress(it, position, duration) }
    override fun configure(settings: AppSettings) {
        ensureOpen()
        require(settings.playbackSpeed.isFinite() && settings.playbackSpeed in .25f..3f) { "倍速须在 0.25 至 3 之间" }
        BlockRules.validate(settings.blockedPatterns)?.let { throw IllegalArgumentException(it) }
        val previous = configuration
        configuration = settings
        if (settings.playbackEngine != backend) switchBackend(settings.playbackEngine)
        else if (backend == PlaybackEngine.VLC && (previous.subtitleSizeSp != settings.subtitleSizeSp || previous.subtitleColor != settings.subtitleColor || previous.subtitleStroke != settings.subtitleStroke)) loadDecoder(backend, capture())
        decoder?.speed(settings.playbackSpeed)
        decoder?.subtitleStyle(settings)
        onConfigure(settings)
    }
    override fun status(): PlaybackStatus {
        ensureOpen()
        decoder?.let { active -> lastRestore = capture().copy(position = active.position) }
        return PlaybackStatus(current?.title.orEmpty(), decoder?.playing == true, position, duration, decoder?.bufferedPercent ?: 0, decoder?.speed ?: lastRestore.speed, decoder?.volumePercent ?: lastRestore.volumePercent)
    }
    override fun command(action: String, params: Map<String, String>): PlaybackStatus {
        ensureOpen()
        val command = PlayerCommands.parse(action, params)
        if (!foreground && (command is PlayerCommand.Play || command is PlayerCommand.Toggle)) throw IllegalArgumentException("播放器在后台，请先在电视上返回播放器")
        if (current == null && command !is PlayerCommand.Exit && command !is PlayerCommand.Pause && command !is PlayerCommand.Volume) throw IllegalArgumentException("媒体正在准备，请稍后操作")
        if (error != null && command !is PlayerCommand.Exit && command !is PlayerCommand.Pause && command !is PlayerCommand.Volume) throw IllegalArgumentException("播放器处于失败状态，请先在电视上重试或换内核")
        when (command) {
            PlayerCommand.Play -> decoder?.play()
            PlayerCommand.Pause -> pause()
            PlayerCommand.Toggle -> if (state == DecoderState.ENDED || !requestedPlay) decoder?.play() else decoder?.pause()
            is PlayerCommand.Seek -> seek(command.position)
            is PlayerCommand.SeekBy -> seek(PlaybackRules.seek(position, command.offset, decoder?.duration ?: 0))
            is PlayerCommand.Speed -> decoder?.speed(command.speed)
            is PlayerCommand.Volume -> { lastRestore = lastRestore.copy(volumePercent = command.percent); decoder?.volume(command.percent) }
            PlayerCommand.Exit -> { decoder?.pause(); val stopped = status(); onExit(); return stopped }
        }
        changed()
        return status()
    }
    private fun seek(position: Long) {
        if (state == DecoderState.READY && decoder?.seekable != true) throw IllegalArgumentException("当前媒体不支持定位")
        decoder?.seek(PlaybackRules.seek(0, position, decoder?.duration ?: 0))
    }
    private fun changed() {
        if (released || replacing) return
        val status = status()
        mediaSession.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, status.title).putLong(MediaMetadata.METADATA_KEY_DURATION, status.duration).build())
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SET_PLAYBACK_SPEED).setState(when { error != null -> PlaybackState.STATE_ERROR; state == DecoderState.BUFFERING -> PlaybackState.STATE_BUFFERING; status.playing -> PlaybackState.STATE_PLAYING; state == DecoderState.ENDED -> PlaybackState.STATE_STOPPED; else -> PlaybackState.STATE_PAUSED }, status.position, status.speed).build())
        onChanged()
    }
    private fun failed(message: String) { if (!released) { error = message; onError(message) } }
    private fun ended() { if (!released && !replacing) onEnd() }
    private fun ensureOpen() { require(!released && Looper.myLooper() == Looper.getMainLooper()) { "播放会话已关闭或调用线程不正确" } }
    fun release() {
        if (released) return
        released = true
        if (PlaybackBridge.controller === this) PlaybackBridge.controller = null
        mediaSession.isActive = false
        mediaSession.release()
        owner.close()
        host?.removeAllViews(); host = null
    }
}
