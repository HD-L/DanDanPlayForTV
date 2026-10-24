package tv.dandan.app.data

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextDecoding {
    fun decode(bytes: ByteArray, preferredLanguage: String? = null): String {
        if (bytes.isEmpty()) return ""
        val bom = when {
            starts(bytes, 0xff, 0xfe, 0, 0) -> "UTF-32LE"
            starts(bytes, 0, 0, 0xfe, 0xff) -> "UTF-32BE"
            starts(bytes, 0xff, 0xfe) -> "UTF-16LE"
            starts(bytes, 0xfe, 0xff) -> "UTF-16BE"
            starts(bytes, 0xef, 0xbb, 0xbf) -> "UTF-8"
            else -> null
        }
        val value = try {
            if (bom != null) strict(bytes, bom)
            else runCatching { strict(bytes, "UTF-8") }.getOrElse {
                if (preferredLanguage in listOf("zh-cn", "zh-tw")) {
                    val candidate = runCatching { strict(bytes, if (preferredLanguage == "zh-cn") "GB18030" else "Big5") }.getOrNull()
                    if (candidate != null && candidate.any { char -> char.code in 0x3400..0x9fff }) return@getOrElse candidate
                }
                val detector = UniversalDetector(null)
                detector.handleData(bytes, 0, bytes.size); detector.dataEnd()
                val detected = detector.detectedCharset ?: throw IllegalArgumentException("无法识别文字编码，请在字幕菜单选择文件编码")
                strict(bytes, detected)
            }
        } catch (_: Exception) { throw IllegalArgumentException("无法识别文字编码，请在字幕菜单选择文件编码") }
        require(value.none { it == '\u0000' }) { "文件包含二进制内容，不能作为字幕或弹幕读取" }
        return value.removePrefix("\uFEFF")
    }
    private fun strict(bytes: ByteArray, charset: String) = Charset.forName(charset).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun starts(bytes: ByteArray, vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 255 == prefix[it] }
}
