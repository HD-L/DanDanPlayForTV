package tv.dandan.app.player

import android.view.View
import tv.dandan.core.AppSettings
import tv.dandan.core.MediaEntry
import java.io.Closeable

enum class DecoderState { IDLE, BUFFERING, READY, ENDED, FAILED }
enum class DecoderTrackKind { AUDIO, TEXT }
data class DecoderTrack(val id: String, val label: String, val selected: Boolean)
enum class VideoFit { FIT, FILL, ZOOM, WIDTH, HEIGHT }
data class ExternalSubtitle(val uri: String, val mimeType: String, val label: String, val styled: Boolean = false)

/** 页面与远程命令只访问会话；具体内核持有自己的渲染面和解码资源。 */
interface DecoderEngine : Closeable {
    val view: View
    val state: DecoderState
    val position: Long
    val duration: Long
    val playing: Boolean
    val requestedPlay: Boolean
    val bufferedPercent: Int
    val speed: Float
    val volumePercent: Int
    val seekable: Boolean
    /** 播放器是否正在下载媒体数据；内核不报告时为 null。 */
    val loading: Boolean? get() = null
    /** 已缓冲、尚未播放的时长（毫秒）；内核不报告时为 -1。 */
    val bufferedAheadMs: Long get() = -1
    fun prepare(entry: MediaEntry, restore: DecoderRestore, subtitle: ExternalSubtitle?, settings: AppSettings)
    fun play()
    fun pause()
    fun seek(position: Long)
    fun speed(value: Float)
    fun volume(value: Int)
    fun tracks(kind: DecoderTrackKind): List<DecoderTrack>
    fun select(kind: DecoderTrackKind, id: String?)
    fun fit(value: VideoFit)
    fun subtitleStyle(settings: AppSettings)
    fun subtitleFont(file: java.io.File?)
}
