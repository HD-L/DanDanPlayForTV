package tv.dandan.app.player

import kotlin.math.roundToInt

object SubtitleOutline {
    fun enabled(weight: Float) = weight.isFinite() && weight > 0f
    /** VLC 的描边选项采用字体大小的百分比，映射细／标准／粗为 2／4／6。 */
    fun vlcThickness(weight: Float) = if (enabled(weight)) (weight * 2).roundToInt().coerceIn(1, 50) else 0
}
