package tv.dandan.app.player

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object SubtitleEncoding {
    val choices = listOf("自动", "UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5", "Shift_JIS", "windows-1252")
    fun decode(bytes: ByteArray, encoding: String = "UTF-8"): String {
        require(encoding in choices) { "不支持此字幕编码" }
        val charset = if (encoding != "自动") Charset.forName(encoding) else when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("字幕编码无法识别，请在字幕菜单选择文件编码后重新导入") }
    }
}
