package tv.dandan.core

import java.security.MessageDigest

/** 片库作品的识别途径。NONE 表示已经尝试过但没有找到可信的结果。 */
enum class RecognitionMethod { NAME, HASH, SEARCH, MANUAL, NONE }

/**
 * 片库作品的识别结果：弹弹play 作品资料与官方剧集表。
 * [signature] 记录识别时作品包含的文件，文件增减后需要重新绑定剧集。
 */
data class SeriesInfo(
    val key: String,
    val animeId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val rating: String = "",
    val typeDescription: String = "",
    val episodes: List<AnimeEpisode> = emptyList(),
    val method: RecognitionMethod = RecognitionMethod.NONE,
    val signature: String = "",
    val updatedAtMs: Long = 0,
    /** 产生此结果的识别规则版本；规则改进后，旧版本没能识别的作品会重新尝试。 */
    val version: Int = LibraryRecognition.VERSION,
) {
    val recognized: Boolean get() = method != RecognitionMethod.NONE && animeId.isNotBlank()
    fun summary(): AnimeSummary = AnimeSummary(animeId, title, imageUrl, description, rating = rating)
}

/** 片库识别的纯规则：作品签名、官方集号解析、本地剧集与官方剧集的对应、搜索结果的标题比对。 */
object LibraryRecognition {
    /** 识别规则版本。改进匹配流程时加一，让之前没能识别的作品重新尝试。 */
    const val VERSION = 3

    /** 官方集号："12" 是正片，"S1"/"SP1" 是特别篇；"C1"（片头片尾）之类返回 null。 */
    data class OfficialNumber(val value: Double, val special: Boolean)

    private val regularNumber = Regex("^\\d{1,4}(?:\\.\\d)?$")
    private val specialNumber = Regex("(?i)^SP?\\s*0*(\\d{1,3})$")

    fun officialNumber(number: String): OfficialNumber? {
        val text = number.trim()
        regularNumber.matchEntire(text)?.let { return OfficialNumber(text.toDouble(), false) }
        specialNumber.matchEntire(text)?.let { return OfficialNumber(it.groupValues[1].toDouble(), true) }
        return null
    }

    /** 作品内容签名：正片与特别篇的文件集合。 */
    fun signature(group: LibraryGroup): String {
        val ids = group.playable.map { it.id }.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(ids.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 把本地文件对应到官方剧集，返回 文件编号 → 官方剧集。
     * [anchor] 是服务端精确匹配到的一个文件与剧集，用它推算本地集号与官方集号的差（续季接着上一季编号时常见）；
     * 没有锚点时按集号直接对应，大部分对不上且本地从大于 1 的集号开始时，整体平移到从第 1 集开始。
     */
    fun mapEpisodes(group: LibraryGroup, official: List<AnimeEpisode>, anchor: Pair<String, Long>? = null): Map<String, AnimeEpisode> {
        val numbered = official.mapNotNull { episode -> officialNumber(episode.number)?.let { it to episode } }
        val regular = numbered.filter { !it.first.special }.associate { it.first.value to it.second }
        val specials = numbered.filter { it.first.special }.associate { it.first.value to it.second }
        val result = linkedMapOf<String, AnimeEpisode>()
        if (group.kind == LibraryKind.MOVIE) {
            // 剧场版通常只有一集；分成上下两个文件时按顺序对应
            val ordered = regular.entries.sortedBy { it.key }.map { it.value }.ifEmpty { official.take(1) }
            group.episodes.forEachIndexed { index, item -> ordered.getOrNull(index)?.let { result[item.entry.id] = it } }
            return result
        }
        val local = group.episodes.filter { it.number != null }
        val anchored = anchor?.let { (mediaId, episodeId) ->
            val item = local.firstOrNull { it.entry.id == mediaId }
            val number = official.firstOrNull { it.id == episodeId }?.let { officialNumber(it.number) }?.takeIf { !it.special }
            if (item != null && number != null) number.value - item.number!! else null
        }
        val offset = anchored ?: run {
            val direct = local.count { regular.containsKey(it.number) }
            val first = local.minOfOrNull { it.number!! } ?: 1.0
            if (direct * 2 < local.size && first > 1) {
                val shift = 1 - first
                if (local.count { regular.containsKey(it.number!! + shift) } > direct) shift else 0.0
            } else 0.0
        }
        local.forEach { item -> regular[item.number!! + offset]?.let { result[item.entry.id] = it } }
        group.specials.forEach { item -> item.number?.let { specials[it] }?.let { result[item.entry.id] = it } }
        return result
    }

    private val chineseDigits = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    private val romanDigits = listOf("", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")
    private val otherSeason = Regex("^(?:第[一二三四五六七八九十\\d]+[季期部]|\\d+(?:st|nd|rd|th)season|season\\d+|s\\d+|\\d+期|\\d+$|(?:ii|iii|iv|v|vi)$)")

    /** 某一季在作品名末尾的常见写法（已规范化）。 */
    fun seasonSuffixes(season: Int): List<String> {
        val cn = chineseDigits.getOrNull(season).orEmpty()
        val ordinal = when (season) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${season}th" }
        return listOf("第${cn}季", "第${season}季", "第${cn}期", "第${season}期", "${season}期", "${ordinal}season", "season$season", "s$season", "$season", romanDigits.getOrNull(season).orEmpty())
            .filter { it.isNotEmpty() && !it.startsWith("第季") && !it.startsWith("第期") }.map(MediaNameParser::normalize).distinct()
    }

    /** 候选作品名与本地作品名的接近程度（0–100）；续季要求候选名带有对应季数。 */
    fun titleScore(names: List<String>, season: Int, candidate: String): Int {
        val c = MediaNameParser.normalize(candidate)
        if (c.isEmpty()) return 0
        val suffixes = seasonSuffixes(season.coerceAtLeast(1))
        return names.map(MediaNameParser::normalize).filter { it.length >= 2 }.maxOfOrNull { n ->
            when {
                c == n -> if (season > 1) 45 else 100
                c.startsWith(n) -> {
                    val rest = c.substring(n.length)
                    when {
                        suffixes.any { rest == it } -> 100
                        season > 1 && suffixes.any { rest.startsWith(it) } -> 85
                        otherSeason.containsMatchIn(rest) -> 20
                        else -> (75 - rest.length * 2).coerceAtLeast(30)
                    }
                }
                n.startsWith(c) && c.length >= 4 -> (70 - (n.length - c.length) * 2).coerceAtLeast(30)
                else -> 0
            }
        } ?: 0
    }

    /** 从候选里选出标题足够接近的作品；都不够接近时返回 null，宁可不识别也不认错。 */
    fun pick(group: LibraryGroup, candidates: List<AnimeSummary>, threshold: Int = 60): AnimeSummary? {
        val names = group.searchKeywords
        return candidates.map { it to titleScore(names, group.season, it.title) }.filter { it.second >= threshold }
            .maxByOrNull { it.second }?.first
    }
}
