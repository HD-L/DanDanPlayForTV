package tv.dandan.core

import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * 从视频文件名（或目录名）解析出的信息。
 * [title] 已去掉字幕组、分辨率、编码、语言等发布标签以及集数与季数；可能为空（例如文件名只有“01.mkv”）。
 */
data class ParsedMediaName(
    val title: String,
    val altTitles: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Double? = null,
    val episodeTitle: String = "",
    /** SP / OVA / S00 等特别篇。 */
    val special: Boolean = false,
    /** 无字幕 OP/ED、菜单、PV、CM、评论音轨等附加内容，不算正片。 */
    val extra: Boolean = false,
    val year: Int? = null,
    /** 含分辨率、片源、编码等发布标签，通常意味着是正式影片而不是随手拍的视频。 */
    val tagged: Boolean = false,
)

/**
 * 动画与影视文件名解析。覆盖常见命名：
 * - 字幕组括号式：`[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][01][1080P][BDRip]`
 * - 字幕组 + 连字符：`[Kamigami] Kaze ga Tsuyoku Fuiteiru - 01 [BD 1080p x265]`
 * - 中文集数：`[DMG] 東京喰種トーキョーグール √A 第01话「新洸」 [BDRip]`
 * - 欧美压制：`Spider-Man.No.Way.Home.2021.1080p.BluRay.x264`、`Show.S01E05.1080p.WEB-DL`
 *
 * 注意：正则需同时兼容 JVM 与 Android（ICU），花括号一律转义。
 */
object MediaNameParser {
    private val bracket = Regex("\\[([^\\[\\]]*)\\]")
    private val crc = Regex("\\[[0-9A-Fa-f]{8}\\]")
    private val episodeTitleQuote = Regex("[「『]([^」』]{1,80})[」』]")
    private val decoration = Regex("★[^★]{0,30}★|☆[^☆]{0,30}☆")
    private val seasonEpisode = Regex("(?i)(?<![a-z0-9])S(\\d{1,2})\\s?E(\\d{1,4}(?:\\.5)?)(?![0-9])")
    private val chineseEpisode = Regex("第\\s*(\\d{1,4}(?:\\.5)?)\\s*[集话話回]")
    private val dashEpisode = Regex("\\s-\\s?(\\d{1,4}(?:\\.5)?)(?:[vV]\\d)?(?=$|\\s|\\[)")
    private val prefixedEpisode = Regex("(?i)(?<![a-z0-9])EP?\\.?\\s?(\\d{1,4}(?:\\.5)?)(?![0-9a-z])")
    private val trailingNumber = Regex("(?:^|\\s)(\\d{1,3})(?:[vV]\\d)?\\s*$")
    private val numberSegment = Regex("(?i)^\\s*(\\d{1,4}(?:\\.5)?)\\s*(?:v\\d)?\\s*(?:END|FIN|完|完结)?\\s*$")
    private val rangeSegment = Regex("^\\s*\\d{1,4}\\s*[-~～—]\\s*\\d{1,4}")
    private val seasonWord = Regex("(?i)(?<![a-z0-9])(?:S(\\d{1,2})(?![0-9e])|Season\\s*(\\d{1,2})|(\\d)(?:st|nd|rd|th)\\s+Season)(?![0-9a-z])")
    private val chineseSeason = Regex("第\\s*([一二三四五六七八九十\\d]{1,3})\\s*[季部]")
    private val year = Regex("(?<![0-9a-zA-Z])(19[3-9]\\d|20[0-4]\\d)(?![0-9a-zA-Z])")
    /** 完整日期（手机录像、录屏常见），其中的年份不是影片年份。 */
    private val date = Regex("(?<![0-9])(?:19[3-9]\\d|20[0-4]\\d)(?:[-._/ ](?:0?[1-9]|1[0-2])[-._/ ](?:0?[1-9]|[12]\\d|3[01])|(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])|年(?:0?[1-9]|1[0-2])月(?:(?:0?[1-9]|[12]\\d|3[01])日)?)(?![0-9])")
    private val special = Regex("(?i)(?<![a-z0-9])(?:SP|OVA|OAD|Specials?)\\s?(\\d{1,2})?(?![a-z0-9])|特别篇|番外")
    private val extra = Regex("(?i)(?<![a-z0-9])(?:NC\\s?OP\\d*|NC\\s?ED\\d*|Menu\\d*|PV\\d*|CM\\d*|Preview|Trailer|Teaser|Commentary|Making|Interview|Bonus|Extras?)(?![a-z0-9])|映像特典|特典|菜单|预告|番宣")
    private val tech = Regex("(?i)(?<![a-z0-9])(?:\\d{3,4}[pi]|4K|8K|UHD|BluRay|Blu-ray|BDRip|BD|BDMV|WEB-?DL|WEB-?Rip|WEB|HDTV|TVRip|DVDRip|DVD|REMUX|x\\.?26[45]|h\\.?26[45]|HEVC|AVC|AV1|VP9|HDR10?|HDR|DV|10-?bit|8-?bit|Hi10p|Ma10p|Main10|AAC\\d?(?:\\.\\d)?|FLAC(?:x\\d)?|AC3|EAC3|DDP?\\d?(?:\\.\\d)?|DTS(?:-HD)?|TrueHD|Atmos|OPUS|MA\\d\\.\\d|\\d\\.\\d|2Audio|MP4|MKV|AVI|SRT|ASS|CHS|CHT|GB|BIG5|JPSC|JPTC|ViuTV|Baha|CR|NF|AMZN|Netflix|B-Global|Bilibili|Abema|TV|TVB|Fin)(?![a-z0-9])")
    private val languageTag = Regex("^(?:[+&、,， ]|简|繁|體|体|简体|繁体|简繁|繁简|简日|繁日|中日|日语|日文|国语|國語|粤语|英语|中字|中文|中文字幕|双语|雙語|内封|內封|内嵌|内挂|外挂|字幕|配音|国配|原盘|无水印|特效|生肉|熟肉|合集|全集|TV全集|附|\\d+集)+$")
    private val adTag = Regex("(?i)www\\.|\\.com|\\.net|\\.org|\\.cc|\\.xyz|发布|首发|压制组|高清影视")
    private val separators = Regex("[\\s._]+")

    fun parse(name: String): ParsedMediaName {
        var s = PlayerUiRules.displayTitle(name)
            .replace('【', '[').replace('】', ']').replace('［', '[').replace('］', ']').replace('〔', '[').replace('〕', ']')
            .replace('（', '[').replace('）', ']').replace('(', '[').replace(')', ']')
        s = crc.replace(s, " ")
        var episodeTitle = ""
        episodeTitleQuote.find(s)?.let { episodeTitle = it.groupValues[1].trim(); s = s.removeRange(it.range) }
        s = decoration.replace(s, " ")
        val isExtra = extra.containsMatchIn(s)
        val segments = segments(s)

        var season: Int? = null
        var episode: Double? = null
        var isSpecial = false
        seasonEpisode.find(s)?.let { season = it.groupValues[1].toInt(); episode = it.groupValues[2].toDouble() }
        if (episode == null) chineseEpisode.find(s)?.let { episode = it.groupValues[1].toDouble() }
        val plain = sceneSpaces(segments.filter { !it.bracket }.joinToString(" ") { it.text })
        if (episode == null) dashEpisode.find(plain)?.let { episode = it.groupValues[1].toDouble() }
        val numberBrackets = segments.filter { it.bracket && numberSegment.matches(it.text) && !isResolutionOrYear(it.text) }
        if (episode == null && numberBrackets.isNotEmpty()) episode = numberSegment.find(numberBrackets.last().text)!!.groupValues[1].toDouble()
        if (episode == null) prefixedEpisode.find(plain)?.let { episode = it.groupValues[1].toDouble() }
        if (season == null) {
            seasonWord.find(s)?.let { m -> season = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toIntOrNull() }
            if (season == null) chineseSeason.find(s)?.let { season = chineseNumber(it.groupValues[1]) }
        }
        special.find(s)?.let { m ->
            // “+SP”“全集+SP”之类的合集说明不算本文件是特别篇
            val before = s.substring(0, m.range.first)
            if (!before.trimEnd().endsWith("+")) { isSpecial = true; if (episode == null) m.groupValues.getOrNull(1)?.toDoubleOrNull()?.let { episode = it } }
        }
        if (season == 0) isSpecial = true
        // 片名本身可能带年份（《银翼杀手 2049》），发行年份通常在最后，取最后一个不属于日期的年份
        val dates = date.findAll(s).map { it.range }.toList()
        val foundYear = year.findAll(s).filter { m -> dates.none { m.range.first in it } }.map { it.groupValues[1].toInt() }
            .lastOrNull { y -> numberBrackets.none { it.text.trim() == y.toString() } }
        val isTagged = segments.any { it.bracket && isTag(it.text) && tech.containsMatchIn(it.text) } || tech.containsMatchIn(plain)

        // 标题：优先取括号外的正文，截到第一个集数/季数/年份/技术标签之前；正文为空时取第一个“像标题”的括号段。
        val bracketTitles = segments.filter { it.bracket && !isTag(it.text) && !numberSegment.matches(it.text) && !rangeSegment.containsMatchIn(it.text) && !extraOnly(it.text) }
        var plainTitle = cutTitle(plain, foundYear)
        // 正文末尾的独立数字视为集数（“作品名 01”“01”）；带年份的影片不做这个推断，避免把片名里的数字当集数
        if (episode == null && foundYear == null) trailingNumber.find(plainTitle)?.let { m ->
            episode = m.groupValues[1].toDouble(); plainTitle = plainTitle.substring(0, m.range.first).trim()
        }
        var title = when {
            plainTitle.isNotBlank() -> plainTitle
            bracketTitles.size >= 2 && segments.firstOrNull() == bracketTitles.first() -> bracketTitles[1].text   // 第一个括号是字幕组
            bracketTitles.size == 1 && segments.firstOrNull() == bracketTitles.first() && segments.count { it.bracket } > 1 && numberBrackets.size >= 2 -> numberBrackets.first().text // [86][01] 之类的数字作品名
            bracketTitles.isNotEmpty() -> bracketTitles.first().text
            numberBrackets.size >= 2 -> numberBrackets.first().text
            else -> ""
        }
        title = clean(removeSeason(title))
        val names = title.split(Regex("\\s+/\\s+|\\s*／\\s*")).map { clean(it) }.filter { it.isNotBlank() }
        return ParsedMediaName(
            title = names.firstOrNull().orEmpty(),
            altTitles = names.drop(1),
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            special = isSpecial,
            extra = isExtra,
            year = foundYear,
            tagged = isTagged,
        )
    }

    /** 用于分组与缓存的作品键：全角转半角、小写、去掉空白和标点。 */
    fun normalize(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** 媒体地址的目录层级（已解码），不含文件名。适用于 http/webdav/smb/ftp/file 与 content 文档地址。 */
    fun folderSegments(uri: String): List<String> {
        val raw = runCatching { URI(uri).rawPath }.getOrNull() ?: uri.substringAfter("://", uri).substringAfter('/', "")
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        return decoded.split('/').filter { it.isNotBlank() }.dropLast(1)
    }

    private data class Segment(val text: String, val bracket: Boolean)
    private fun segments(s: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var last = 0
        for (m in bracket.findAll(s)) {
            if (m.range.first > last) out += Segment(s.substring(last, m.range.first), false)
            out += Segment(m.groupValues[1], true)
            last = m.range.last + 1
        }
        if (last < s.length) out += Segment(s.substring(last), false)
        return out.filter { it.text.isNotBlank() }
    }

    /** 欧美压制命名用点或下划线分词：把夹在字母之间的点换成空格，保留 5.1 这类数字小数。 */
    private fun sceneSpaces(text: String): String = text
        .replace('_', ' ')
        .replace(Regex("(?<=[^\\s\\d])\\.|\\.(?=[^\\s\\d])"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** 截到第一个集数、季数、年份、技术标签或附加内容标记之前。 */
    private fun cutTitle(plain: String, foundYear: Int?): String {
        if (plain.isBlank()) return ""
        var cut = plain.length
        fun at(index: Int) { if (index in 0 until cut) cut = index }
        seasonEpisode.find(plain)?.let { at(it.range.first) }
        chineseEpisode.find(plain)?.let { at(it.range.first) }
        dashEpisode.find(plain)?.let { at(it.range.first) }
        Regex("\\s-\\s?\\d{1,4}\\s*[~～-]").find(plain)?.let { at(it.range.first) }
        prefixedEpisode.find(plain)?.let { if (it.range.first > 0) at(it.range.first) }
        Regex("(?i)\\s-\\s?(?:NC\\s?OP|NC\\s?ED|Menu|PV|CM|SP|OVA|OAD|Preview|Trailer)").find(plain)?.let { at(it.range.first) }
        special.find(plain)?.let { if (it.range.first > 0 && !plain.substring(0, it.range.first).trimEnd().endsWith("+")) at(it.range.first) }
        if (foundYear != null) year.findAll(plain).lastOrNull { it.range.first > 0 && it.groupValues[1].toInt() == foundYear }?.let { at(it.range.first) }
        date.find(plain)?.let { if (it.range.first > 0) at(it.range.first) }
        tech.find(plain)?.let { if (it.range.first > 0) at(it.range.first) }
        return plain.substring(0, cut)
    }

    private fun removeSeason(title: String): String = chineseSeason.replace(seasonWord.replace(title, " "), " ")
    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().trim('-', '_', '~', '.', '·', ':', '：', ',', '，', '+', '&', ' ', '|')
        .replace(Regex("\\s+"), " ").trim()

    private fun isResolutionOrYear(text: String): Boolean {
        val n = text.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return n in listOf(360, 480, 540, 576, 720, 1080, 1440, 2160, 4320) || (text.trim().length == 4 && n in 1930..2049)
    }

    private fun extraOnly(text: String): Boolean = extra.containsMatchIn(text) && extra.replace(text, "").let { rest -> rest.isBlank() || rest.trim().all { it.isDigit() || it in " -_.&" } }

    /** 括号段是否只是发布标签（技术参数、语言、来源、范围、网址广告、字幕组说明）。 */
    private fun isTag(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (rangeSegment.containsMatchIn(t) || adTag.containsMatchIn(t)) return true
        if (languageTag.matches(t.replace(" ", ""))) return true
        if (Regex("^[\\d.]+$").matches(t) && isResolutionOrYear(t)) return true
        if (Regex("^\\d\\.\\d$").matches(t)) return true
        val words = t.split(Regex("[\\s_+&,，/]+")).filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { word -> tech.matches(word) || languageTag.matches(word) || Regex("(?i)^(?:x?\\d{3,4}x\\d{3,4}|\\d+fps|v\\d|chs|cht|jp|sc|tc|mp4|mkv)$").matches(word) || tech.find(word)?.value?.length == word.length }
    }

    private fun chineseNumber(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val digits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        return when {
            text == "十" -> 10
            text.startsWith("十") -> 10 + (digits[text.getOrNull(1)] ?: 0)
            text.length == 2 && text[1] == '十' -> (digits[text[0]] ?: return null) * 10
            text.length == 3 && text[1] == '十' -> (digits[text[0]] ?: return null) * 10 + (digits[text[2]] ?: 0)
            text.length == 1 -> digits[text[0]]
            else -> null
        }
    }
}
