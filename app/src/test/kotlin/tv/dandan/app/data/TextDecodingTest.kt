package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test

class TextDecodingTest {
    @Test fun decodesUtf8AndUtf16BomWithoutReplacementCharacters() {
        val text = "1\n00:00:00,000 --> 00:00:01,000\n中文测试字幕\n"
        assertEquals(text, TextDecoding.decode(text.toByteArray()))
        assertEquals(text, TextDecoding.decode(byteArrayOf(-1, -2) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, TextDecoding.decode(byteArrayOf(-2, -1) + text.toByteArray(Charsets.UTF_16BE)))
    }
    @Test fun detectsChineseLegacyEncoding() {
        val text = "简体中文字幕，自动识别文件编码。字幕内容是中文对话。".repeat(20)
        assertEquals(text, TextDecoding.decode(text.toByteArray(java.nio.charset.Charset.forName("GB18030"))))
    }
    @Test fun declaredChineseLanguageResolvesShortLegacySubtitleAmbiguity() {
        val text = "1\n00:00:00,000 --> 00:00:01,000\n中文\n"
        assertEquals(text, TextDecoding.decode(text.toByteArray(java.nio.charset.Charset.forName("GB18030")), "zh-cn"))
    }
    @Test fun rejectsBinaryInsteadOfReplacingCharacters() {
        assertThrows(IllegalArgumentException::class.java) { TextDecoding.decode(byteArrayOf(0, 1, 2, 0)) }
    }
}
