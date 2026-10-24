package tv.dandan.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.dandan.core.MagnetOption
import tv.dandan.core.MagnetResource
import tv.dandan.core.MagnetRules
import tv.dandan.core.MagnetSearchPage
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 第三方资源搜索节点（用户自填）。协议为公开约定：
 * GET /list?keyword=&type=&subgroup=&page=  → { HasMore, Resources[{Title, Magnet, TypeName, SubgroupName, FileSize, PublishDate, PageUrl}] }
 * GET /type → { Types[{Id, Name}] }，GET /subgroup → { Subgroups[{Id, Name}] }
 */
class MagnetSearchApi(private val node: () -> String) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    fun search(keyword: String, typeId: Int = -1, subgroupId: Int = -1, page: Int = 1): MagnetSearchPage {
        require(keyword.trim().length in 1..200) { "请输入 1 至 200 个字符的关键字" }
        // 节点会对关键字再做一次 URL 解码：单次编码的中日文会让节点报错（Cloudflare 1101），因此编码两次。
        val query = mutableMapOf("keyword" to encode(encode(keyword.trim())), "page" to page.coerceIn(1, 999).toString())
        if (typeId >= 0) query["type"] = typeId.toString()
        if (subgroupId >= 0) query["subgroup"] = subgroupId.toString()
        val json = request("list", query)
        val items = json.optJSONArray("Resources").objects().mapNotNull { item ->
            val magnet = item.optString("Magnet").trim()
            if (MagnetRules.infoHash(magnet) == null) return@mapNotNull null
            MagnetResource(item.optString("Title").take(300), magnet.take(2000), item.optString("TypeName").take(50), item.optString("SubgroupName").take(100), item.optString("FileSize").take(30), item.optString("PublishDate").take(40), item.optString("PageUrl").take(500))
        }
        return MagnetSearchPage(items, json.optBoolean("HasMore", false))
    }
    fun types(): List<MagnetOption> = request("type").optJSONArray("Types").objects().map { MagnetOption(it.optInt("Id", -1), it.optString("Name").take(50)) }.filter { it.name.isNotBlank() }
    fun subgroups(): List<MagnetOption> = request("subgroup").optJSONArray("Subgroups").objects().map { MagnetOption(it.optInt("Id", -1), it.optString("Name").take(100)) }.filter { it.name.isNotBlank() }

    private fun request(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val base = node().takeIf { it.isNotBlank() } ?: throw IOException("尚未配置资源搜索节点，请在设置中填写节点地址")
        val url = MagnetRules.normalizeNode(base).toHttpUrl().newBuilder().addPathSegment(path).apply { query.forEach { (k, v) -> addEncodedQueryParameter(k, if (k == "keyword") v else encode(v)) } }.build()
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("节点暂时不可用（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("节点返回空内容")
                return JSONObject(readLimited(body.byteStream(), 4 * 1024 * 1024).toString(Charsets.UTF_8))
            }
        } catch (e: IOException) { throw e } catch (_: Exception) { throw IOException("节点响应格式错误，请确认地址指向资源搜索服务") }
    }
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until minOf(length(), 2000)).mapNotNull { optJSONObject(it) }
}
