package tv.dandan.app.player

import java.io.Closeable

/** 先关闭旧解码器，构造失败后也不会留下播放声音。 */
class DecoderOwner<T : Closeable> : Closeable {
    var current: T? = null
        private set
    fun replace(factory: () -> T): T {
        val previous = current
        current = null
        previous?.close()
        return factory().also { current = it }
    }
    override fun close() {
        val previous = current
        current = null
        previous?.close()
    }
}

data class DecoderRestore(
    val position: Long = 0,
    val play: Boolean = true,
    val speed: Float = 1f,
    val volumePercent: Int = 100,
    val textDisabled: Boolean = false,
    val audioOrdinal: Int? = null,
    val textOrdinal: Int? = null,
    val duration: Long = 0,
) {
    fun forForeground(active: Boolean) = copy(
        position = position.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it },
        duration = duration.coerceAtLeast(0),
        play = play && active,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(.25f, 3f) ?: 1f,
        volumePercent = volumePercent.coerceIn(0, 100),
    )
}
