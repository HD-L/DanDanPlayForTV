package tv.dandan.app.data

import tv.dandan.core.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务端明确拒绝了这一个请求（4xx 或业务失败）；与断网、超时、服务故障不同，换一个请求可能成功。 */
class ServiceRejected(message: String) : IOException(message)
/** 开发者凭证的接口调用配额已用尽（弹弹play 2026 年 6 月起按应用分配额度）；需要等待额度恢复或申请更高额度。 */
class QuotaExceeded(message: String) : IOException(message)

class DanDanApi(private val credentials: () -> ApiCredentials, private val baseUrl: String = "https://api.dandanplay.net") {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun weekly(): List<AnimeSummary> = request("bangumi/shin", mapOf("filterAdultContent" to "true")).optJSONArray("bangumiList").objects().map(::summary)
    fun seasons(): List<AnimeSeason> = request("bangumi/season/anime").optJSONArray("seasons").objects().map {
        AnimeSeason(it.getInt("year"), it.getInt("month"), it.optString("seasonName"))
    }.filter { it.year in 1900..2200 && it.month in listOf(1, 4, 7, 10) }.sortedWith(compareByDescending<AnimeSeason> { it.year }.thenByDescending { it.month })
    fun season(season: AnimeSeason): List<AnimeSummary> {
        require(season.year in 1900..2200 && season.month in listOf(1, 4, 7, 10)) { "季度无效，请从服务提供的季度中选择" }
        return request("bangumi/season/anime/${season.year}/${season.month}", mapOf("filterAdultContent" to "true")).optJSONArray("bangumiList").objects().map(::summary)
    }
    fun search(keyword: String): List<AnimeSummary> {
        require(keyword.trim().length in 2..200) { "搜索关键词需要 2 至 200 个字符" }
        return request("search/anime", mapOf("keyword" to keyword.trim(), "v2" to "true")).optJSONArray("animes").objects().map(::summary)
    }
    fun detail(id: String): AnimeDetail {
        require(id.isNotBlank() && id.length <= 200) { "番剧编号无效" }
        val item = request("bangumi/" + java.net.URLEncoder.encode(id, "UTF-8")).optJSONObject("bangumi") ?: throw IOException("服务未返回番剧详情")
        return AnimeDetail(
            summary(item),
            item.optJSONArray("episodes").objects().map { AnimeEpisode(it.getLong("episodeId"), it.optString("episodeTitle"), it.optString("episodeNumber")) },
            item.optJSONArray("relateds").objects().map(::summary).filter { it.title.isNotBlank() },
            item.optJSONArray("similars").objects().map(::summary).filter { it.title.isNotBlank() },
            item.optJSONArray("tags").objects().mapNotNull { tag -> tag.optString("name").takeIf { it.isNotBlank() }?.let { AnimeTag(tag.optInt("id"), it.take(50)) } }.take(60),
            item.optString("typeDescription").takeIf { it != "null" }.orEmpty().take(50),
            item.optJSONArray("titles").objects().mapNotNull { it.optString("title").trim().takeIf { title -> title.isNotBlank() && title != "null" }?.take(200) }.distinct().take(30),
        )
    }
    /** 按标签搜索番剧；标签名以逗号拼接。 */
    fun searchByTag(tags: List<String>): List<AnimeSummary> {
        val names = tags.map { it.trim() }.filter { it.isNotBlank() && it.length <= 50 }.take(10)
        require(names.isNotEmpty()) { "请选择至少一个标签" }
        return request("search/tag", mapOf("tags" to names.joinToString(","))).optJSONArray("animes").objects().map(::summary)
    }
    fun searchEpisodes(anime: String): List<EpisodeMatch> {
        require(anime.trim().length in 2..200) { "作品名称需要 2 至 200 个字符" }
        return request("search/episodes", mapOf("anime" to anime.trim(), "v2" to "true")).optJSONArray("animes").objects().flatMap { item ->
            item.optJSONArray("episodes").objects().map { EpisodeMatch(it.getLong("episodeId"), item.optString("animeTitle"), it.optString("episodeTitle"), animeId = item.optLong("animeId")) }
        }
    }
    fun searchEpisodes(anime: AnimeSummary): List<AnimeEpisode> = detail(anime.id).episodes
    /**
     * 按文件匹配弹幕库。有文件前 16MB 的 MD5 时用"哈希 + 文件名"（服务端先精确比对哈希，再模糊匹配文件名），
     * 拿不到哈希时退回只按文件名。
     */
    fun matchByName(fileName: String, sizeBytes: Long = 0, fileHash: String? = null, durationSec: Int = 0): List<EpisodeMatch> {
        require(fileName.isNotBlank() && fileName.length <= 1000 && sizeBytes >= 0) { "文件信息无效" }
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', fileName)
        val hash = fileHash?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        val body = JSONObject().put("fileName", name).put("fileSize", sizeBytes).put("matchMode", if (hash != null) "hashAndFileName" else "fileNameOnly")
        if (hash != null) body.put("fileHash", hash)
        if (durationSec > 0) body.put("videoDuration", durationSec)
        val result = request("match", body = body)
        return result.optJSONArray("matches").objects().map {
            EpisodeMatch(it.getLong("episodeId"), it.optString("animeTitle"), it.optString("episodeTitle"), (it.optDouble("shift", 0.0) * 1000).toLong(), result.optBoolean("isMatched"),
                it.optLong("animeId"), it.optString("imageUrl").takeIf { url -> url.startsWith("https://") || url.startsWith("http://") }.orEmpty())
        }
    }
    /** 批量匹配的一个文件：文件名（不含扩展名也可）、大小与前 16MB 的 MD5。 */
    data class BatchFile(val fileName: String, val sizeBytes: Long, val fileHash: String)

    /**
     * 批量精确匹配：一次最多 32 个文件、不能重复，只返回“精确关联”的结果，与请求一一对应，未命中为 null。
     * 片库识别用它把几十部作品的请求合并成一次，节省接口配额。
     */
    fun matchBatch(files: List<BatchFile>): List<EpisodeMatch?> {
        require(files.size in 1..32 && files.map { it.fileHash.lowercase() }.distinct().size == files.size) { "批量匹配需要 1 至 32 个不重复的文件" }
        require(files.all { it.fileHash.matches(Regex("[0-9a-fA-F]{32}")) && it.sizeBytes >= 0 && it.fileName.isNotBlank() }) { "文件信息无效" }
        val requests = JSONArray(files.map { file ->
            val name = file.fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', file.fileName)
            JSONObject().put("fileName", name).put("fileHash", file.fileHash.lowercase()).put("fileSize", file.sizeBytes).put("matchMode", "hashAndFileName")
        })
        val items = request("match/batch", body = JSONObject().put("requests", requests)).optJSONArray("results").objects()
        return files.indices.map { index ->
            items.getOrNull(index)?.takeIf { it.optBoolean("success") }?.optJSONObject("matchResult")?.takeIf { it.optLong("episodeId") > 0 }?.let {
                EpisodeMatch(it.getLong("episodeId"), it.optString("animeTitle"), it.optString("episodeTitle"), (it.optDouble("shift", 0.0) * 1000).toLong(), true,
                    it.optLong("animeId"), it.optString("imageUrl").takeIf { url -> url.startsWith("https://") || url.startsWith("http://") }.orEmpty())
            }
        }
    }
    fun comments(episodeId: Long): List<DanmakuComment> {
        require(episodeId > 0) { "弹幕库编号无效" }
        return request("comment/$episodeId", mapOf("withRelated" to "true")).optJSONArray("comments").objects().mapNotNull { item ->
            val values = item.optString("p").split(',')
            if (values.size < 3) return@mapNotNull null
            val seconds = values[0].toDoubleOrNull() ?: return@mapNotNull null
            if (!seconds.isFinite() || seconds < 0) return@mapNotNull null
            val mode = when (values[1]) { "1" -> DanmakuMode.SCROLL; "4" -> DanmakuMode.BOTTOM; "5" -> DanmakuMode.TOP; else -> return@mapNotNull null }
            val color = values[2].toLongOrNull()?.takeIf { it in 0..0xffffff } ?: return@mapNotNull null
            val text = item.optString("m").take(2000)
            if (text.isBlank()) return@mapNotNull null
            DanmakuComment((seconds * 1000).toLong(), text, 0xff000000.toInt() or color.toInt(), mode)
        }
    }
    private fun summary(item: JSONObject): AnimeSummary = AnimeSummary(
        item.optString("bangumiId").takeIf { it.isNotBlank() && it != "null" } ?: item.optLong("animeId").toString(),
        item.optString("animeTitle"), item.optString("imageUrl"), item.optString("summary", item.optString("intro")), item.optInt("airDay", -1), item.optString("rating", "").let { r -> r.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "" })
    private fun request(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        val auth = credentials()
        if (!auth.configured) throw IOException("尚未配置弹弹play开发者凭证，请在设置中填写 AppId 和 AppSecret")
        val origin = baseUrl.toHttpUrl()
        var url = origin.newBuilder().encodedPath("/api/v2/$path").apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        var signed = true
        var requestBody = body
        repeat(6) { attempt ->
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (signed) {
                val timestamp = System.currentTimeMillis() / 1000
                builder.header("X-AppId", auth.appId).header("X-Timestamp", timestamp.toString()).header("X-Signature", ApiSigning.signature(auth, timestamp, url.encodedPath))
            }
            requestBody?.let { builder.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        if (attempt == 5) throw IOException("服务重定向次数过多")
                        val next = response.header("Location")?.let(url::resolve) ?: throw IOException("服务重定向地址无效")
                        if (url.isHttps && !next.isHttps) throw IOException("服务拒绝不安全的降级跳转")
                        if (next.username.isNotEmpty() || next.password.isNotEmpty()) throw IOException("服务跳转地址包含不安全凭证")
                        signed = next.scheme == origin.scheme && next.host == origin.host && next.port == origin.port && next.encodedPath.startsWith("/api/v2/")
                        url = next
                        if (response.code in listOf(301, 302, 303)) requestBody = null
                        return@use
                    }
                    if (response.code == 401 || response.code == 403) throw IOException("服务认证失败，请检查开发者凭证、设备时间和应用权限")
                    if (!response.isSuccessful) {
                        // 服务端在参数校验失败等情况下会返回 errorMessage / errorDetail，直接展示比只给状态码更容易定位
                        val reason = runCatching { response.body?.let { JSONObject(readLimited(it.byteStream(), 64 * 1024).toString(Charsets.UTF_8)) } }.getOrNull()?.let { serverReason(it, auth) }
                        val message = "服务暂时不可用（HTTP ${response.code}${reason?.let { "：$it" }.orEmpty()}）"
                        throw when { response.code == 429 || reason.orEmpty().contains("配额") -> QuotaExceeded(message); response.code in 400..499 -> ServiceRejected(message); else -> IOException(message) }
                    }
                    val responseBody = response.body ?: throw IOException("服务返回空内容")
                    require(responseBody.contentLength() <= MAX_RESPONSE_BYTES) { "服务响应超过允许大小" }
                    val json = JSONObject(readLimited(responseBody.byteStream(), MAX_RESPONSE_BYTES).toString(Charsets.UTF_8))
                    if ((json.has("success") && !json.optBoolean("success")) || json.optInt("errorCode") != 0) {
                        val reason = serverReason(json, auth)
                        val message = reason?.let { "服务未完成请求：$it" } ?: "服务未完成请求（错误代码 ${json.optInt("errorCode")}），请检查输入或稍后重试"
                        throw if (reason.orEmpty().contains("配额")) QuotaExceeded(message) else ServiceRejected(message)
                    }
                    return json
                }
            } catch (e: IOException) { throw e } catch (_: Exception) { throw IOException("服务响应格式错误或内容超过限制") }
        }
        throw IOException("服务重定向次数过多")
    }
    /** 服务端的错误说明；去掉其中可能回显的 AppId / AppSecret，避免凭证出现在提示和日志里。 */
    private fun serverReason(json: JSONObject, auth: ApiCredentials): String? {
        var text = listOf("errorMessage", "errorDetail").map { json.optString(it).trim() }.filter { it.isNotBlank() && it != "null" }.joinToString("；")
        listOf(auth.appId, auth.appSecret).filter { it.length >= 4 }.forEach { text = text.replace(it, "***") }
        return text.take(200).ifBlank { null }
    }
    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        if (length() > 100000) throw IOException("服务返回条目过多")
        return (0 until length()).map { getJSONObject(it) }
    }
    private companion object { const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024 }
}
