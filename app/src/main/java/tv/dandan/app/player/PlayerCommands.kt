package tv.dandan.app.player

sealed interface PlayerCommand {
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object Toggle : PlayerCommand
    data object Exit : PlayerCommand
    data class Seek(val position: Long) : PlayerCommand
    data class SeekBy(val offset: Long) : PlayerCommand
    data class Speed(val speed: Float) : PlayerCommand
    data class Volume(val percent: Int) : PlayerCommand
}

object PlayerCommands {
    fun parse(action: String, params: Map<String, String>): PlayerCommand = when (action.lowercase()) {
        "play", "resume" -> PlayerCommand.Play
        "pause" -> PlayerCommand.Pause
        "toggle" -> PlayerCommand.Toggle
        "stop", "exit" -> PlayerCommand.Exit
        "seek" -> PlayerCommand.Seek(params["position"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("定位位置必须为非负毫秒数"))
        "seekby" -> PlayerCommand.SeekBy(params["offset"]?.toLongOrNull()
            ?: throw IllegalArgumentException("快进偏移必须为毫秒数"))
        "speed" -> PlayerCommand.Speed(params["speed"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0.25f..3f }
            ?: throw IllegalArgumentException("倍速须在 0.25 至 3 之间"))
        "volume" -> PlayerCommand.Volume(params["percent"]?.toIntOrNull()?.takeIf { it in 0..100 }
            ?: throw IllegalArgumentException("音量须在 0 至 100 之间"))
        "brightness" -> throw IllegalArgumentException("电视设备不支持通用亮度控制")
        else -> throw IllegalArgumentException("不支持的播放命令")
    }
}
