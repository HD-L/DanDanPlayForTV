package tv.dandan.app.data

import org.json.JSONArray
import org.json.JSONObject
import tv.dandan.core.*

/** 本机存储和备份使用相同字段名，备份先进行严格模式校验。 */
internal object LibraryJson {
    val mediaFields = setOf("id", "title", "uri", "directory", "sourceId", "sizeBytes", "subtitleUri", "danmakuUri")
    val settingsFields = setOf("danmakuEnabled", "danmakuSizeSp", "danmakuAlpha", "danmakuDurationMs", "danmakuStroke", "danmakuMaxLines", "danmakuMaxCount", "showScrolling", "showTop", "showBottom", "blockedKeywords", "blockedPatterns", "subtitleSizeSp", "subtitleColor", "subtitleDelayMs", "playbackSpeed", "playbackEngine", "subtitleStroke", "cloudDanmakuBlock", "danmakuLanguage", "magnetNode", "multiConnection")
    fun media(m: MediaEntry) = JSONObject().put("id", m.id).put("title", m.title).put("uri", m.uri).put("directory", m.directory).put("sourceId", m.sourceId).put("sizeBytes", m.sizeBytes).put("subtitleUri", m.subtitleUri).put("danmakuUri", m.danmakuUri)
    fun media(j: JSONObject, strict: Boolean = false): MediaEntry {
        if (strict) {
            fields(j, mediaFields)
            listOf("id", "title", "uri", "sourceId", "subtitleUri", "danmakuUri").forEach { string(j, it) }
            boolean(j, "directory"); integer(j, "sizeBytes")
        }
        return MediaEntry(j.getString("id"), j.getString("title"), j.getString("uri"), j.optBoolean("directory"), j.optString("sourceId"), j.optLong("sizeBytes"), j.optString("subtitleUri"), j.optString("danmakuUri"))
    }
    fun settings(s: AppSettings) = JSONObject().put("danmakuEnabled", s.danmakuEnabled).put("danmakuSizeSp", s.danmakuSizeSp).put("danmakuAlpha", s.danmakuAlpha).put("danmakuDurationMs", s.danmakuDurationMs).put("danmakuStroke", s.danmakuStroke).put("danmakuMaxLines", s.danmakuMaxLines).put("danmakuMaxCount", s.danmakuMaxCount).put("showScrolling", s.showScrolling).put("showTop", s.showTop).put("showBottom", s.showBottom).put("blockedKeywords", JSONArray(s.blockedKeywords)).put("blockedPatterns", JSONArray(s.blockedPatterns)).put("subtitleSizeSp", s.subtitleSizeSp).put("subtitleColor", s.subtitleColor).put("subtitleDelayMs", s.subtitleDelayMs).put("playbackSpeed", s.playbackSpeed).put("playbackEngine", s.playbackEngine.name).put("subtitleStroke", s.subtitleStroke).put("cloudDanmakuBlock", s.cloudDanmakuBlock).put("danmakuLanguage", s.danmakuLanguage.name).put("magnetNode", s.magnetNode).put("multiConnection", s.multiConnection)
    fun settings(j: JSONObject, strict: Boolean = false): AppSettings {
        if (strict) {
            val optional = setOf("subtitleStroke", "cloudDanmakuBlock", "danmakuLanguage", "magnetNode", "multiConnection")
            val present = j.keys().asSequence().toSet()
            require(present.containsAll(settingsFields - optional) && present.all { it in settingsFields }) { "备份设置字段缺失或包含未知字段" }
            if (j.has("subtitleStroke")) number(j, "subtitleStroke")
            if (j.has("cloudDanmakuBlock")) boolean(j, "cloudDanmakuBlock")
            if (j.has("danmakuLanguage")) string(j, "danmakuLanguage")
            if (j.has("magnetNode")) string(j, "magnetNode")
            if (j.has("multiConnection")) boolean(j, "multiConnection")
            listOf("danmakuEnabled", "showScrolling", "showTop", "showBottom").forEach { boolean(j, it) }
            listOf("danmakuDurationMs", "danmakuMaxLines", "danmakuMaxCount", "subtitleColor", "subtitleDelayMs").forEach { integer(j, it) }
            listOf("danmakuMaxLines", "danmakuMaxCount", "subtitleColor").forEach { require(integer(j, it) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "备份整数超出范围" } }
            listOf("danmakuSizeSp", "danmakuAlpha", "danmakuStroke", "subtitleSizeSp", "playbackSpeed").forEach { number(j, it) }
            listOf("blockedKeywords", "blockedPatterns").forEach { strings(j.getJSONArray(it)) }
            string(j, "playbackEngine")
        }
        val result = AppSettings(j.optBoolean("danmakuEnabled", true), j.optDouble("danmakuSizeSp", 24.0).toFloat(), j.optDouble("danmakuAlpha", .85).toFloat(), j.optLong("danmakuDurationMs", 8000), j.optDouble("danmakuStroke", 2.0).toFloat(), j.optInt("danmakuMaxLines", 8), j.optInt("danmakuMaxCount", 60), j.optBoolean("showScrolling", true), j.optBoolean("showTop", true), j.optBoolean("showBottom", true), j.optJSONArray("blockedKeywords")?.let(::strings).orEmpty(), j.optJSONArray("blockedPatterns")?.let(::strings).orEmpty(), j.optDouble("subtitleSizeSp", 24.0).toFloat(), j.optInt("subtitleColor", -1), j.optLong("subtitleDelayMs"), j.optDouble("playbackSpeed", 1.0).toFloat(), enumValue<PlaybackEngine>(j.optString("playbackEngine", PlaybackEngine.MEDIA3.name)), subtitleStroke = j.optDouble("subtitleStroke", 2.0).toFloat(), cloudDanmakuBlock = j.optBoolean("cloudDanmakuBlock", true), danmakuLanguage = enumValue<DanmakuLanguage>(j.optString("danmakuLanguage", DanmakuLanguage.ORIGINAL.name)), magnetNode = j.optString("magnetNode", ""), multiConnection = j.optBoolean("multiConnection", true))
        SettingsRules.validate(result)
        return result
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("备份设置含不支持的选项")
    fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
    fun strings(a: JSONArray): List<String> = (0 until a.length()).map { require(a.get(it) is String) { "文本列表字段类型无效" }; a.getString(it) }
    fun fields(j: JSONObject, expected: Set<String>) { require(j.keys().asSequence().toSet() == expected) { "备份字段缺失或包含未知字段" } }
    fun string(j: JSONObject, key: String): String { require(j.get(key) is String) { "备份字段类型无效" }; return j.getString(key) }
    fun boolean(j: JSONObject, key: String): Boolean { require(j.get(key) is Boolean) { "备份字段类型无效" }; return j.getBoolean(key) }
    fun number(j: JSONObject, key: String): Number { val n = j.get(key); require(n is Number && n.toDouble().isFinite()) { "备份数值字段无效" }; return n }
    fun integer(j: JSONObject, key: String): Long {
        val n = number(j, key)
        require(n is Int || n is Long) { "备份整数字段无效" }
        return n.toLong()
    }
    fun series(info: SeriesInfo): JSONObject = JSONObject().put("anime", info.animeId).put("title", info.title.take(500)).put("image", info.imageUrl.take(2000))
        .put("desc", info.description.take(2000)).put("rating", info.rating).put("type", info.typeDescription.take(50)).put("method", info.method.name)
        .put("sig", info.signature).put("updated", info.updatedAtMs).put("v", info.version)
        .put("eps", JSONArray(info.episodes.take(3000).map { JSONArray().put(it.id).put(it.number.take(20)).put(it.title.take(300)) }))
    fun series(key: String, j: JSONObject): SeriesInfo {
        val eps = j.optJSONArray("eps") ?: JSONArray()
        return SeriesInfo(
            key, j.optString("anime"), j.optString("title"), j.optString("image"), j.optString("desc"), j.optString("rating"), j.optString("type"),
            (0 until eps.length()).mapNotNull { i -> eps.optJSONArray(i)?.let { AnimeEpisode(it.optLong(0), it.optString(2), it.optString(1)) }?.takeIf { it.id > 0 } },
            RecognitionMethod.values().firstOrNull { it.name == j.optString("method") } ?: RecognitionMethod.NONE,
            j.optString("sig"), j.optLong("updated"), j.optInt("v", 1),
        )
    }
    fun anime(a: AnimeSummary): JSONObject = JSONObject().put("id", a.id).put("title", a.title).put("imageUrl", a.imageUrl).put("description", a.description.take(2000)).put("airDay", a.airDay).put("rating", a.rating)
    fun anime(j: JSONObject): AnimeSummary = AnimeSummary(j.optString("id"), j.optString("title"), j.optString("imageUrl"), j.optString("description"), j.optInt("airDay", -1), j.optString("rating"))
}
