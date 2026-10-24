package tv.dandan.app.player

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.MediaDataSourceFactory
import tv.dandan.core.AppSettings
import tv.dandan.core.MediaEntry
import tv.dandan.core.StreamingRules
import kotlin.math.roundToInt

@UnstableApi
internal class Media3Decoder(context: Context, repository: AppRepository, private val changed: () -> Unit, private val failed: (String) -> Unit, private val ended: () -> Unit) : DecoderEngine {
    // 局域网大码率文件：缓冲上限放宽到两分钟（受内存约束），卡顿后多攒一点再播，减少反复停顿
    private val bufferBytes = StreamingRules.targetBufferBytes(Runtime.getRuntime().maxMemory())
    private val loadControl = AdaptiveLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(StreamingRules.MIN_BUFFER_MS, StreamingRules.MAX_BUFFER_MS, StreamingRules.START_BUFFER_MS, StreamingRules.REBUFFER_MS)
        .setTargetBufferBytes(bufferBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build(), bufferBytes)
    private val player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(MediaDataSourceFactory(context, repository))).setLoadControl(loadControl).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    override val view = PlayerView(context).apply {
        useController = false
        // 缓冲提示由 Compose 叠加层统一绘制，避免出现两个转圈。
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        this.player = this@Media3Decoder.player
    }
    private var restore: DecoderRestore? = null
    private var external: ExternalSubtitle? = null
    private var subtitleTypeface: android.graphics.Typeface? = null
    private var closed = false
    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!closed) changed() }
            override fun onPlayerError(error: PlaybackException) { if (!closed) failed("Media3 播放失败（${error.errorCodeName}），可重试或切换 VLC 内核") }
            override fun onPlaybackStateChanged(playbackState: Int) { if (!closed && playbackState == Player.STATE_ENDED) ended() }
            override fun onTracksChanged(tracks: Tracks) { applyTrackRestore() }
        })
    }
    override val state get() = if (player.playerError != null) DecoderState.FAILED else when (player.playbackState) {
        Player.STATE_BUFFERING -> DecoderState.BUFFERING
        Player.STATE_READY -> DecoderState.READY
        Player.STATE_ENDED -> DecoderState.ENDED
        else -> DecoderState.IDLE
    }
    override val position get() = player.currentPosition.coerceAtLeast(0)
    override val duration get() = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0
    override val playing get() = player.isPlaying
    override val requestedPlay get() = player.playWhenReady
    override val bufferedPercent get() = player.bufferedPercentage.coerceIn(0, 100)
    override val speed get() = player.playbackParameters.speed
    override val volumePercent get() = (player.volume * 100).roundToInt()
    override val seekable get() = player.isCurrentMediaItemSeekable
    override val loading get() = player.isLoading
    override val bufferedAheadMs get() = player.totalBufferedDuration
    override fun prepare(entry: MediaEntry, restore: DecoderRestore, subtitle: ExternalSubtitle?, settings: AppSettings) {
        this.restore = restore
        external = subtitle
        val item = MediaItem.Builder().setMediaId(entry.id).setUri(entry.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
        if (subtitle?.mimeType == MimeTypes.APPLICATION_TTML) item.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri)).setMimeType(subtitle.mimeType).setLabel(subtitle.label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, restore.textDisabled || (subtitle != null && subtitle.mimeType != MimeTypes.APPLICATION_TTML)).build()
        player.setMediaItem(item.build(), restore.position)
        player.setPlaybackSpeed(restore.speed)
        player.volume = restore.volumePercent / 100f
        subtitleStyle(settings)
        player.prepare()
        player.playWhenReady = restore.play
    }
    override fun play() { if (state == DecoderState.ENDED) player.seekTo(0); player.play() }
    override fun pause() = player.pause()
    override fun seek(position: Long) = player.seekTo(position)
    override fun speed(value: Float) = player.setPlaybackSpeed(value)
    override fun volume(value: Int) { player.volume = value / 100f }
    private fun type(kind: DecoderTrackKind) = if (kind == DecoderTrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    private fun candidates(kind: DecoderTrackKind) = player.currentTracks.groups.filter { it.type == type(kind) }.flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
    override fun tracks(kind: DecoderTrackKind) = candidates(kind).mapIndexed { ordinal, (group, index) ->
        val format = group.getTrackFormat(index)
        DecoderTrack(ordinal.toString(), listOfNotNull("轨道 ${ordinal + 1}", format.label, format.language, format.sampleMimeType).joinToString(" · "), group.isTrackSelected(index))
    }
    override fun select(kind: DecoderTrackKind, id: String?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type(kind)).setTrackTypeDisabled(type(kind), kind == DecoderTrackKind.TEXT && id == null)
        id?.toIntOrNull()?.let { candidates(kind).getOrNull(it) }?.let { (group, index) -> builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) }
        player.trackSelectionParameters = builder.build()
    }
    private fun applyTrackRestore() {
        val saved = restore ?: return
        if (player.currentTracks.groups.isEmpty()) return
        restore = null
        if (saved.audioOrdinal != null && candidates(DecoderTrackKind.AUDIO).isNotEmpty()) select(DecoderTrackKind.AUDIO, saved.audioOrdinal.toString())
        if (!saved.textDisabled && external == null && saved.textOrdinal != null && candidates(DecoderTrackKind.TEXT).isNotEmpty()) select(DecoderTrackKind.TEXT, saved.textOrdinal.toString())
    }
    override fun fit(value: VideoFit) { view.resizeMode = when (value) { VideoFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT; VideoFit.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL; VideoFit.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; VideoFit.WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH; VideoFit.HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT } }
    override fun subtitleStyle(settings: AppSettings) {
        view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp)
        view.subtitleView?.setStyle(CaptionStyleCompat(settings.subtitleColor, Color.TRANSPARENT, Color.TRANSPARENT, if (SubtitleOutline.enabled(settings.subtitleStroke)) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, subtitleTypeface))
        view.subtitleView?.setBottomPaddingFraction(.08f)
    }
    override fun subtitleFont(file: java.io.File?) { subtitleTypeface = file?.let(android.graphics.Typeface::createFromFile) }
    override fun close() {
        if (closed) return
        closed = true
        view.player = null
        player.release()
    }
}
