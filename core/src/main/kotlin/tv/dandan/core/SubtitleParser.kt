package tv.dandan.core

object SubtitleParser {
    private val timing = Regex("(\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}")
    fun parse(text: String): List<SubtitleCue> {
        require(text.length <= 4 * 1024 * 1024) { "字幕文件过大，请选择小于 4 MB 的文件" }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val result = if (normalized.contains("[Events]", true)) parseAss(normalized) else parseTimed(normalized)
        return result.filter { it.startMs >= 0 && it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs }.take(50000)
    }
    fun at(cues: List<SubtitleCue>, positionMs: Long, delayMs: Long): String = SubtitleTimeline(cues).at(positionMs, delayMs)
    private fun parseTimed(text: String): List<SubtitleCue> = text.split(Regex("\n[ \\t]*\n")).mapNotNull { block ->
        val lines = block.lines()
        val index = lines.indexOfFirst { it.contains("-->") }
        if (index < 0) return@mapNotNull null
        val times = timing.findAll(lines[index]).mapNotNull { runCatching { parseTime(it.value) }.getOrNull() }.toList()
        if (times.size < 2) return@mapNotNull null
        SubtitleCue(times[0], times[1], clean(lines.drop(index + 1).joinToString("\n")))
    }
    private fun parseAss(text: String): List<SubtitleCue> {
        var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        var inEvents = false
        val result = mutableListOf<SubtitleCue>()
        for (line in text.lines()) {
            if (line.startsWith("[")) inEvents = line.trim().equals("[Events]", true)
            if (!inEvents) continue
            if (line.startsWith("Format:", true)) fields = line.substringAfter(':').split(',').map { it.trim() }
            if (!line.startsWith("Dialogue:", true)) continue
            val parts = line.substringAfter(':').trimStart().split(',', limit = fields.size)
            if (parts.size != fields.size) continue
            fun field(name: String): String = parts.getOrNull(fields.indexOfFirst { it.equals(name, true) }).orEmpty()
            val start = runCatching { parseTime(field("Start")) }.getOrNull() ?: continue
            val end = runCatching { parseTime(field("End")) }.getOrNull() ?: continue
            result.add(SubtitleCue(start, end, clean(field("Text").replace(Regex("\\{[^}]*\\}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))))
        }
        return result
    }
    private fun clean(text: String): String = text.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
    private fun parseTime(text: String): Long {
        val numbers = text.trim().replace(',', '.').split(':')
        require(numbers.size in 2..3)
        val seconds = numbers.last().toDouble()
        val minutes = numbers[numbers.lastIndex - 1].toLong()
        val hours = if (numbers.size == 3) numbers.first().toLong() else 0
        require(seconds.isFinite() && seconds in 0.0..<60.0 && minutes in 0..59 && hours >= 0)
        return hours * 3600000 + minutes * 60000 + (seconds * 1000).toLong()
    }
}

/** 以结束时间前缀索引处理重叠字幕，跳转时不依赖上一帧状态。 */
class SubtitleTimeline(cues: List<SubtitleCue>) {
    private val sorted = cues.sortedBy { it.startMs }
    private val maxEnd = LongArray(sorted.size)
    init { var end = 0L; sorted.forEachIndexed { index, cue -> end = maxOf(end, cue.endMs); maxEnd[index] = end } }
    fun at(positionMs: Long, delayMs: Long = 0): String {
        val target = positionMs - delayMs.coerceIn(-3600000, 3600000)
        var low = 0; var high = sorted.size
        while (low < high) { val mid = (low + high) ushr 1; if (maxEnd[mid] <= target) low = mid + 1 else high = mid }
        val active = mutableListOf<String>()
        for (index in low until sorted.size) {
            val cue = sorted[index]
            if (cue.startMs > target) break
            if (cue.endMs > target) active.add(cue.text)
            if (active.size == 8) break
        }
        return active.joinToString("\n")
    }
}
