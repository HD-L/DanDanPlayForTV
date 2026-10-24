package tv.dandan.app.data

import tv.dandan.core.*
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

object SourceRules {
    fun validateMediaUri(uri: String) {
        require(uri.isNotBlank() && uri.length <= 8192 && !uri.any { it.isISOControl() }) { "媒体地址无效或超过长度限制" }
        val value = runCatching { URI(uri) }.getOrElse { throw IllegalArgumentException("媒体地址格式不正确") }
        require(value.rawUserInfo == null) { "媒体地址不能内嵌账号密码，请配置媒体来源" }
        require(value.fragment == null) { "媒体地址不能包含片段标记" }
        when (value.scheme?.lowercase()) {
            "content" -> require(!value.authority.isNullOrBlank() && !value.path.isNullOrBlank()) { "文档地址无效" }
            // 磁链播放记录的稳定地址：magnet-play://<40 位 info hash>/<文件索引>，播放前由引擎换成本机地址。
            "magnet-play" -> require(Regex("^magnet-play://[0-9a-f]{40}/\\d{1,6}$").matches(uri)) { "磁链播放记录地址无效" }
            "file" -> require(value.path.orEmpty().startsWith('/') && !value.path.orEmpty().any { it.isISOControl() }) { "文件地址无效" }
            "http", "https", "smb", "ftp" -> {
                require(!value.host.isNullOrBlank() && (value.port == -1 || value.port in 1..65535)) { "媒体主机或端口无效" }
                segments(value)
                if (value.scheme.lowercase() in listOf("smb", "ftp")) require(value.query == null) { "网络文件地址不能包含查询参数" }
            }
            else -> throw IllegalArgumentException("不支持此媒体地址协议")
        }
    }
    fun validate(source: MediaSource): MediaSource {
        require(source.id.isNotBlank() && source.name.isNotBlank()) { "请输入来源名称" }
        require(source.name.length <= 200 && source.location.length <= 8192) { "来源名称或地址过长" }
        require(source.username.length <= 200 && source.password.length <= 4096 && !(source.username + source.password).any { it.isISOControl() }) { "账号密码包含无效控制字符或超过长度限制" }
        val uri = runCatching { URI(source.location.trim()) }.getOrElse { throw IllegalArgumentException("来源地址格式不正确") }
        require(uri.rawUserInfo == null) { "请在账号字段填写凭证，不要将账号写入地址" }
        require(uri.fragment == null) { "来源地址不能包含片段标记" }
        val scheme = uri.scheme?.lowercase()
        val valid = when (source.kind) {
            SourceKind.DOCUMENT_TREE -> scheme == "content" && !uri.authority.isNullOrBlank()
            SourceKind.HTTP, SourceKind.WEBDAV -> scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
            SourceKind.SMB -> scheme == "smb" && !uri.host.isNullOrBlank() && uri.path.orEmpty().trim('/').isNotEmpty()
            SourceKind.FTP -> scheme == "ftp" && !uri.host.isNullOrBlank()
        }
        require(valid) { "地址协议与来源类型不一致，SMB 地址必须包含共享名" }
        if (source.kind !in listOf(SourceKind.HTTP, SourceKind.DOCUMENT_TREE)) require(uri.query == null) { "目录地址不能包含查询参数" }
        require(source.password.isEmpty() || source.username.isNotBlank()) { "填写密码时还需要填写账号" }
        if (source.kind != SourceKind.DOCUMENT_TREE) segments(uri)
        return source.copy(name = source.name.trim(), location = uri.toASCIIString(), username = source.username.trim())
    }
    fun requireWithin(source: MediaSource, uri: String) {
        val root = URI(source.location)
        val target = runCatching { URI(uri) }.getOrElse { throw IllegalArgumentException("媒体路径无效") }
        require(target.rawUserInfo == null && target.fragment == null) { "媒体路径不能包含凭证或片段" }
        require(root.scheme.equals(target.scheme, true) && root.authority.equals(target.authority, true)) { "目录超出来源范围" }
        if (source.kind == SourceKind.DOCUMENT_TREE) {
            val rootTree = root.rawPath.substringBefore("/document/")
            require(target.rawPath == rootTree || target.rawPath.startsWith("$rootTree/document/")) { "目录超出授权范围" }
            // 文档编号由提供方定义，实际访问权限交给系统树授权验证。
            require(rootTree.startsWith("/tree/") && rootTree.substringAfter("/tree/").isNotEmpty()) { "目录树地址无效" }
        } else {
            val rootParts = segments(root)
            val targetParts = segments(target)
            require(targetParts.size >= rootParts.size && targetParts.take(rootParts.size) == rootParts) { "目录超出来源范围" }
        }
    }
    private fun segments(uri: URI): List<String> = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map {
        decode(it).also { part -> require(part != "." && part != ".." && '/' !in part && '\\' !in part && !part.any { ch -> ch.isISOControl() }) { "路径包含不安全的目录片段" } }
    }
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    fun childUri(parent: String, name: String, directory: Boolean): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && !name.any { it.isISOControl() }) { "文件名称无效" }
        val root = URI(parent)
        val segment = URI(null, null, "/$name", null).toASCIIString().removePrefix("/")
        return "${root.scheme}://${root.rawAuthority}${root.rawPath.orEmpty().trimEnd('/')}/$segment${if (directory) "/" else ""}"
    }
}
object ApiSigning {
    fun signature(credentials: ApiCredentials, timestamp: Long, path: String): String {
        val apiPath = path.substringBefore('?')
        require(apiPath.startsWith('/')) { "API 路径无效" }
        val bytes = "${credentials.appId}$timestamp$apiPath${credentials.appSecret}".toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
internal fun readLimited(input: InputStream, limit: Int): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "内容超过允许大小，请选择较小文件" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
internal object HistoryRules {
    fun merge(history: List<PlaybackProgress>, progress: PlaybackProgress): List<PlaybackProgress> =
        (listOf(progress) + history.filterNot { it.media.id == progress.media.id }).sortedByDescending { it.updatedAtMs }.take(100)
}
internal object SettingsRules {
    fun validate(s: AppSettings) {
        require(s.subtitleStroke.isFinite() && s.subtitleStroke in 0f..6f) { "字幕描边超出允许范围" }
        require(s.danmakuSizeSp.isFinite() && s.danmakuSizeSp in 10f..80f && s.danmakuAlpha.isFinite() && s.danmakuAlpha in 0f..1f && s.danmakuDurationMs in 1000..30000 && s.danmakuStroke.isFinite() && s.danmakuStroke in 0f..10f && s.danmakuMaxLines in 0..30 && s.danmakuMaxCount in 0..300 && s.subtitleSizeSp.isFinite() && s.subtitleSizeSp in 10f..80f && s.subtitleDelayMs in -3600000..3600000 && s.playbackSpeed.isFinite() && s.playbackSpeed in .25f..3f) { "设置超出允许范围" }
        require(s.blockedKeywords.size <= 200 && s.blockedPatterns.size <= 100 && s.blockedKeywords.all { it.length <= 200 }) { "屏蔽规则数量或长度超过限制" }
        BlockRules.validate(s.blockedPatterns)?.let { throw IllegalArgumentException(it) }
        if (s.magnetNode.isNotBlank()) tv.dandan.core.MagnetRules.normalizeNode(s.magnetNode)
    }
}
