package tv.dandan.core

import java.util.Locale

/** 磁链与资源搜索的纯规则：关键字拼接、磁链哈希、视频文件判断。 */
object MagnetRules {
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv", "rmvb", "rm", "3gp")
    private val hashPattern = Regex("urn:btih:([0-9A-Fa-f]{40}|[A-Za-z2-7]{32})")

    /** 详情页"找资源"的默认关键字：番剧名 + 两位集数。 */
    fun keyword(animeTitle: String, episodeNumber: String): String {
        val title = animeTitle.trim().take(100)
        val number = episodeNumber.trim().toIntOrNull()?.let { String.format(Locale.ROOT, "%02d", it) } ?: episodeNumber.trim()
        return if (number.isBlank()) title else "$title $number"
    }

    /** 从磁链或裸哈希提取 40 位小写 info hash；32 位 base32 形式转换为十六进制，非法输入返回 null。 */
    fun infoHash(text: String): String? {
        val raw = text.trim()
        val value = when {
            raw.matches(Regex("[0-9A-Fa-f]{40}")) || raw.matches(Regex("[A-Za-z2-7]{32}")) -> raw
            else -> hashPattern.find(raw)?.groupValues?.get(1)
        } ?: return null
        return if (value.length == 40) value.lowercase(Locale.ROOT) else base32ToHex(value)
    }

    /** 统一为十六进制哈希的磁链，并保留原磁链中的 tracker（最多 30 个），帮助尽快拿到种子元数据。 */
    fun canonicalMagnet(text: String): String? {
        val hash = infoHash(text) ?: return null
        val trackers = Regex("[?&]tr=([^&]+)").findAll(text.trim()).map { it.groupValues[1] }
            .filter { it.length <= 300 && !it.contains(' ') }.distinct().take(30).toList()
        return buildString { append("magnet:?xt=urn:btih:").append(hash); trackers.forEach { append("&tr=").append(it) } }
    }

    private fun base32ToHex(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L; var bits = 0
        val out = StringBuilder(40)
        for (c in value.uppercase(Locale.ROOT)) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong(); bits += 5
            while (bits >= 4) { bits -= 4; out.append("0123456789abcdef"[((buffer shr bits) and 0xF).toInt()]) }
        }
        return out.toString()
    }

    fun isVideo(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions

    /** 节点地址：只接受 http/https，不含路径、查询、账号。 */
    fun normalizeNode(text: String): String {
        val value = text.trim().trimEnd('/')
        require(value.length <= 200 && Regex("^https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?$").matches(value)) { "节点地址格式应为 http(s)://域名或IP[:端口]" }
        return value
    }

    /** 种子里可播放的视频，按名称自然排序。 */
    fun playable(files: List<TorrentFile>): List<TorrentFile> = files.filter { isVideo(it.name) }.sortedWith { a, b -> LibraryRules.naturalCompare(a.name, b.name) }
}
