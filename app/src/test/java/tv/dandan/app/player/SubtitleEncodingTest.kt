package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleEncodingTest {
    @Test fun utf8AndUtf16BomPreserveChineseAndAssCommands() {
        val text = "[Script Info]\nDialogue: {\\pos(40,50)}中文"
        assertEquals(text, SubtitleEncoding.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, SubtitleEncoding.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE), "UTF-16LE"))
        assertEquals(text, SubtitleEncoding.decode(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE), "UTF-16BE"))
    }
    @Test fun legacyEncodingMustBeChosenRatherThanSilentlyReplacingText() {
        val bytes = "中文字幕".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        assertThrows(IllegalArgumentException::class.java) { SubtitleEncoding.decode(bytes) }
        assertEquals("中文字幕", SubtitleEncoding.decode(bytes, "GB18030"))
    }
    @Test fun malformedAndUnknownEncodingsHaveActionableErrors() {
        val error = assertThrows(IllegalArgumentException::class.java) { SubtitleEncoding.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertTrue(error.message.orEmpty().contains("编码"))
        assertThrows(IllegalArgumentException::class.java) { SubtitleEncoding.decode(byteArrayOf(1), "任意编码") }
    }
}
