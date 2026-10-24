package tv.dandan.app.player

/** 所有调用由 TV 与远程入口统一投递到主线程。 */
object PlaybackBridge {
    var controller: PlaybackController? = null
}

interface PlaybackController {
    fun configure(settings: tv.dandan.core.AppSettings)
    fun status(): PlaybackStatus
    fun command(action: String, params: Map<String, String>): PlaybackStatus
}

data class PlaybackStatus(
    val title: String,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val bufferedPercent: Int,
    val speed: Float,
    val volumePercent: Int,
)
