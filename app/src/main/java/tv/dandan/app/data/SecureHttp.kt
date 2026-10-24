package tv.dandan.app.data

import okhttp3.Response
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.dandan.core.MediaSource
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object SecureHttp {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    /** [forceRange] 为真时即使从头读取也带上 Range 请求头，让支持范围请求的服务端返回 206 与文件总长度。 */
    fun open(uri: String, source: MediaSource?, position: Long = 0, length: Long = -1, method: String = "GET", body: String? = null, forceRange: Boolean = false): Response {
        require(position >= 0 && length >= -1) { "读取位置无效" }
        var url = uri.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "不要将账号写入媒体地址" }
        repeat(6) { attempt ->
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (source != null && source.username.isNotBlank() && runCatching { SourceRules.requireWithin(source, url.toString()) }.isSuccess) {
                request.header("Authorization", Credentials.basic(source.username, source.password, Charsets.UTF_8))
            }
            if (forceRange || position > 0 || length > 0) request.header("Range", "bytes=$position-${if (length > 0) position + length - 1 else ""}")
            if (method == "PROPFIND") request.header("Depth", "1").method(method, body.orEmpty().toRequestBody("application/xml; charset=utf-8".toMediaType()))
            val response = try { client.newCall(request.build()).execute() } catch (_: IOException) { throw IOException("网络连接失败，请检查来源地址和连接") }
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let(url::resolve)
            response.close()
            if (next == null || attempt == 5) throw IOException("媒体服务重定向无效或次数过多")
            if (url.isHttps && !next.isHttps) throw IOException("拒绝不安全的降级跳转")
            if (next.username.isNotEmpty() || next.password.isNotEmpty()) throw IOException("媒体跳转地址不能包含凭证")
            if (method != "GET" && source != null && runCatching { SourceRules.requireWithin(source, next.toString()) }.isFailure) throw IOException("目录服务跳转超出来源范围")
            url = next
        }
        throw IOException("媒体服务重定向次数过多")
    }
}
