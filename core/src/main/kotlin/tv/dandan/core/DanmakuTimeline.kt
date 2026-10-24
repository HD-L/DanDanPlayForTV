package tv.dandan.core
import com.google.re2j.Pattern

class DanmakuTimeline(comments: List<DanmakuComment>, cloudPatterns: List<String> = emptyList()) {
    private val sorted = comments.filter { it.timeMs >= 0 && it.text.isNotBlank() }.sortedBy { it.timeMs }
    private val cloudFilter = cloudPatterns.take(2000).chunked(100).mapNotNull { rules -> runCatching { Pattern.compile(rules.joinToString("|") { "(?:$it)" }) }.getOrNull() }
    private var lastPatterns: List<String>? = null
    private var compiled: List<Pattern> = emptyList()
    private var cachedFrom = -1
    private var cachedUntil = -1
    private var cachedSettings: AppSettings? = null
    private var cachedComments: List<DanmakuComment> = emptyList()
    fun window(positionMs: Long, settings: AppSettings): List<DanmakuComment> {
        if (!settings.danmakuEnabled || settings.danmakuMaxLines == 0 || positionMs < 0) return emptyList()
        if (lastPatterns != settings.blockedPatterns) {
            compiled = settings.blockedPatterns.take(100).mapNotNull { runCatching { Pattern.compile(it.take(256)) }.getOrNull() }
            lastPatterns = settings.blockedPatterns.toList()
        }
        val until = upperBound(positionMs)
        val from = maxOf(lowerBound((positionMs - settings.danmakuDurationMs.coerceIn(1000, 30000)).coerceAtLeast(0)), until - MAX_WINDOW_CANDIDATES)
        if (cachedFrom == from && cachedUntil == until && cachedSettings == settings) return cachedComments
        val limit = if (settings.danmakuMaxCount <= 0) 300 else settings.danmakuMaxCount.coerceAtMost(300)
        val result = ArrayList<DanmakuComment>()
        for (index in from until until) {
            val comment = sorted[index]
            val visible = when (comment.mode) {
                DanmakuMode.SCROLL -> settings.showScrolling
                DanmakuMode.TOP -> settings.showTop
                DanmakuMode.BOTTOM -> settings.showBottom
            }
            if (!visible || settings.blockedKeywords.any { it.isNotBlank() && comment.text.contains(it, ignoreCase = true) }) continue
            if (compiled.any { it.matcher(comment.text.take(2000)).find() }) continue
            if (settings.cloudDanmakuBlock && cloudFilter.any { it.matcher(comment.text.take(2000)).find() }) continue
            result.add(comment)
            if (result.size >= limit) break
        }
        cachedFrom = from; cachedUntil = until; cachedSettings = settings; cachedComments = result.toList()
        return cachedComments
    }
    private fun lowerBound(time: Long): Int {
        var low = 0; var high = sorted.size
        while (low < high) { val mid = (low + high) ushr 1; if (sorted[mid].timeMs < time) low = mid + 1 else high = mid }
        return low
    }
    private fun upperBound(time: Long): Int {
        var low = 0; var high = sorted.size
        while (low < high) { val mid = (low + high) ushr 1; if (sorted[mid].timeMs <= time) low = mid + 1 else high = mid }
        return low
    }
    companion object { const val MAX_WINDOW_CANDIDATES = 2000 }
}
object BlockRules {
    fun validate(patterns: List<String>): String? {
        if (patterns.size > 100) return "屏蔽正则最多 100 条"
        for (pattern in patterns) {
            if (pattern.length > 256) return "屏蔽正则最多 256 个字符"
            try { Pattern.compile(pattern) } catch (_: com.google.re2j.PatternSyntaxException) { return "无效屏蔽正则：$pattern（不支持反向引用与环视）" }
        }
        return null
    }
}
