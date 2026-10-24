package tv.dandan.core

/** 播放器界面的纯规则：拖动步长、时间格式、标题整理、连播倒计时。 */
object PlayerUiRules {
    /** 连播倒计时秒数。 */
    const val UP_NEXT_SECONDS = 5
    /** 控制栏无操作自动隐藏的毫秒数（仅播放中）。 */
    const val AUTO_HIDE_MS = 5_000L
    /** 隐藏态快进快退的单步毫秒数。 */
    const val SEEK_STEP_MS = 10_000L

    /**
     * 进度条拖动步长：随连续按键次数加速，并不小于总时长的 1/200，
     * 长片按住方向键几秒即可跨越大段，短片仍能精确到 10 秒。
     */
    fun scrubStep(durationMs: Long, repeat: Int): Long {
        val base = when {
            repeat < 3 -> 10_000L
            repeat < 8 -> 30_000L
            repeat < 16 -> 60_000L
            else -> 120_000L
        }
        val proportional = if (durationMs > 0) durationMs / 200 else 0
        return maxOf(base, proportional)
    }

    /** 拖动目标限制在 [0, 时长]；时长未知时只限制下界。 */
    fun clampTarget(target: Long, durationMs: Long): Long = if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)

    fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** 文件名去掉扩展名与首尾空白，作为标题展示。 */
    fun displayTitle(fileName: String): String {
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        val ext = if (dot > 0) trimmed.substring(dot + 1) else ""
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) trimmed.substring(0, dot).trim() else trimmed
    }

    /** 选集卡片上的序号：优先识别"第N集 / EPN / - N"，否则用队列位置。 */
    fun episodeLabel(fileName: String, position: Int): String {
        val name = displayTitle(fileName)
        val patterns = listOf(Regex("第\\s*(\\d{1,4})\\s*[集话話]"), Regex("(?i)\\b(?:EP?|S\\d{1,2}E)(\\d{1,4})\\b"), Regex("[\\[【(（\\s-]\\s*(\\d{1,3})\\s*[\\]】)）\\s]"))
        for (pattern in patterns) pattern.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return "%02d".format(it) }
        return "%02d".format(position + 1)
    }

    /** 观看进度比例；时长未知返回 -1，看完（剩余不足 5%）返回 1。 */
    fun watchedFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return -1f
        val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        return if (fraction >= .95f) 1f else fraction
    }

    /** 隐藏态连续快进快退：同方向在 [windowMs] 内累加，换方向或超时重新计数。 */
    fun accumulateSeek(previousDeltaMs: Long, previousAtMs: Long, nowMs: Long, forward: Boolean, windowMs: Long = 1_200): Long {
        val step = if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
        val sameDirection = previousDeltaMs != 0L && (previousDeltaMs > 0) == forward
        return if (sameDirection && nowMs - previousAtMs <= windowMs) previousDeltaMs + step else step
    }
}
