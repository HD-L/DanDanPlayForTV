package tv.dandan.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class SubtitleAccount(val apiKey: String = "", val username: String = "", val password: String = "", val userAgent: String = "DanDanTV v0.2.0") {
    val configured get() = apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    fun validate() {
        require(listOf(apiKey, username, password, userAgent).all { it.length <= 4096 && it.none(Char::isISOControl) }) { "字幕服务凭证格式无效" }
        require(configured && userAgent.isNotBlank()) { "请填写 OpenSubtitles API Key、账号、密码和应用名称" }
    }
}
data class OnlineSubtitle(val fileId: Long, val title: String, val release: String, val language: String, val hashMatched: Boolean)

class OpenSubtitlesApi(private val account: SubtitleAccount, baseUrl: String = "https://api.opensubtitles.com/api/v1/") {
    private val origin = baseUrl.toHttpUrl()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var endpoint = origin
    private var token = ""

    fun search(query: String, language: String, movieHash: String? = null): List<OnlineSubtitle> {
        account.validate()
        require(query.trim().length in 2..200) { "字幕搜索名称需要 2 至 200 个字符" }
        require(language.matches(Regex("[a-z]{2}(?:-[a-z]{2})?"))) { "字幕语言无效" }
        require(movieHash == null || movieHash.matches(Regex("[0-9a-f]{16}"))) { "视频指纹无效" }
        val url = origin.newBuilder().addPathSegment("subtitles").addQueryParameter("query", query.trim())
            .addQueryParameter("languages", language).apply { movieHash?.let { addQueryParameter("moviehash", it) } }.build()
        val json = json(url)
        val rows = json.optJSONArray("data") ?: return emptyList()
        require(rows.length() <= 1000) { "字幕候选过多，请缩小搜索范围" }
        return buildList {
            for (i in 0 until rows.length()) {
                val attrs = rows.getJSONObject(i).getJSONObject("attributes")
                val files = attrs.optJSONArray("files") ?: continue
                require(files.length() <= 100) { "字幕文件数量异常" }
                for (j in 0 until files.length()) {
                    val file = files.getJSONObject(j)
                    val id = file.optLong("file_id")
                    if (id > 0) add(OnlineSubtitle(id, attrs.optJSONObject("feature_details")?.optString("title").orEmpty().take(500),
                        attrs.optString("release").take(1000), attrs.optString("language").take(20), attrs.optBoolean("moviehash_match")))
                }
            }
        }.distinctBy { it.fileId }
    }

    fun download(fileId: Long): ByteArray {
        require(fileId > 0) { "字幕编号无效" }
        account.validate()
        if (token.isEmpty()) login()
        val json = json(endpoint.newBuilder().addPathSegment("download").build(), JSONObject().put("file_id", fileId).put("sub_format", "srt"), true)
        val url = runCatching { json.getString("link").toHttpUrl() }.getOrElse { throw IOException("字幕服务没有返回下载地址") }
        validateDownload(url)
        // 下载域名不继承服务凭证，包括同域下载和跨域跳转。
        var next = url
        repeat(6) { attempt ->
            client.newCall(Request.Builder().url(next).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    if (attempt == 5) throw IOException("字幕下载跳转过多")
                    next = response.header("Location")?.let(next::resolve) ?: throw IOException("字幕下载跳转无效")
                    validateDownload(next); return@use
                }
                if (!response.isSuccessful) throw IOException("字幕下载失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("字幕下载内容为空")
                require(body.contentLength() <= MAX_BYTES) { "字幕文件超过 2 MiB" }
                return unpack(readLimited(body.byteStream(), MAX_BYTES))
            }
        }
        throw IOException("字幕下载跳转过多")
    }
    private fun login() {
        val response = json(origin.newBuilder().addPathSegment("login").build(), JSONObject().put("username", account.username).put("password", account.password))
        val value = response.optString("token")
        require(value.isNotBlank() && value.length <= 8192 && value.none(Char::isISOControl)) { "字幕服务登录没有返回有效令牌" }
        val regional = response.optString("base_url").takeIf { it.isNotBlank() }
        if (regional != null) {
            val raw = if (regional.contains("://")) regional else "https://$regional"
            val parsed = runCatching { raw.toHttpUrl() }.getOrElse { throw IOException("字幕服务区域地址无效") }
            require(parsed.isHttps && parsed.host in setOf("api.opensubtitles.com", "vip-api.opensubtitles.com") && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty()) { "字幕服务区域地址不受信任" }
            endpoint = parsed.newBuilder().encodedPath("/api/v1/").query(null).fragment(null).build()
        }
        token = value
    }
    private fun json(url: HttpUrl, body: JSONObject? = null, authenticated: Boolean = false): JSONObject {
        account.validate()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Api-Key", account.apiKey).header("User-Agent", account.userAgent)
        if (authenticated) request.header("Authorization", "Bearer $token")
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        try {
            client.newCall(request.build()).execute().use { response ->
                if (response.code in listOf(401, 403)) throw IOException("字幕服务认证失败，请检查专属 API Key、账号和密码")
                if (response.code in listOf(406, 429)) throw IOException("字幕下载配额已用完，请稍后或更换服务账户再试")
                if (!response.isSuccessful) throw IOException("字幕服务请求失败（HTTP ${response.code}）")
                val result = response.body ?: throw IOException("字幕服务返回空内容")
                require(result.contentLength() <= MAX_BYTES) { "字幕服务响应过大" }
                return JSONObject(readLimited(result.byteStream(), MAX_BYTES).toString(Charsets.UTF_8))
            }
        } catch (error: IOException) { throw error }
        catch (_: Exception) { throw IOException("字幕服务响应格式无效或超过限制") }
    }
    private fun validateDownload(url: HttpUrl) {
        val localFixture = !origin.isHttps && origin.host in setOf("localhost", "127.0.0.1") && url.host in setOf("localhost", "127.0.0.1")
        require((url.isHttps || localFixture) && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "字幕下载地址不安全" }
    }
    internal fun unpack(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) return GZIPInputStream(ByteArrayInputStream(bytes)).use { readLimited(it, MAX_BYTES) }
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            var result: ByteArray? = null
            var entries = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 16 && !entry.name.startsWith('/') && entry.name.split('/', '\\').none { it == ".." }) { "字幕压缩包内容无效" }
                    if (!entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in listOf("srt", "vtt", "ass", "ssa")) {
                        require(result == null) { "字幕压缩包包含多个字幕，请下载单个文件" }
                        result = readLimited(zip, MAX_BYTES)
                    } else readLimited(zip, MAX_BYTES)
                    zip.closeEntry()
                }
            }
            return result ?: throw IOException("字幕压缩包没有可用文件")
        }
        return bytes
    }
    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
