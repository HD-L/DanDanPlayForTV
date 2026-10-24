package tv.dandan.core

object PlaybackRules {
    fun seek(positionMs: Long, offsetMs: Long, durationMs: Long): Long {
        val position = positionMs.coerceAtLeast(0)
        val target = try { Math.addExact(position, offsetMs) }
        catch (_: ArithmeticException) { if (offsetMs > 0) Long.MAX_VALUE else 0 }
        return target.coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }
    fun resume(progress: PlaybackProgress?): Long {
        if (progress == null) return 0
        val position = progress.positionMs.coerceAtLeast(0)
        if (progress.durationMs > 0 && (position >= progress.durationMs * 0.95 || progress.durationMs - position <= 5000)) return 0
        return seek(position, 0, progress.durationMs)
    }
}

enum class RemoteKey { CENTER, LEFT, RIGHT, UP, DOWN, BACK, PLAY_PAUSE }
enum class PlaybackAction { SHOW_CONTROLS, HIDE_CONTROLS, TOGGLE, SEEK_BACK, SEEK_FORWARD, EXIT, FOCUS }
object PlaybackKeys {
    fun action(key: RemoteKey, controlsVisible: Boolean): PlaybackAction = when (key) {
        RemoteKey.BACK -> if (controlsVisible) PlaybackAction.HIDE_CONTROLS else PlaybackAction.EXIT
        RemoteKey.PLAY_PAUSE -> PlaybackAction.TOGGLE
        RemoteKey.CENTER -> if (controlsVisible) PlaybackAction.FOCUS else PlaybackAction.TOGGLE
        RemoteKey.LEFT -> if (controlsVisible) PlaybackAction.FOCUS else PlaybackAction.SEEK_BACK
        RemoteKey.RIGHT -> if (controlsVisible) PlaybackAction.FOCUS else PlaybackAction.SEEK_FORWARD
        RemoteKey.UP, RemoteKey.DOWN -> if (controlsVisible) PlaybackAction.FOCUS else PlaybackAction.SHOW_CONTROLS
    }
}
