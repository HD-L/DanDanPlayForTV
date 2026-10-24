package tv.dandan.app.player

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import tv.dandan.core.DanmakuComment
import tv.dandan.core.DanmakuMode
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

object DanmakuXml {
    private const val MAX_INPUT = 8 * 1024 * 1024
    private const val MAX_COMMENTS = 200_000
    private const val MAX_TEXT = 500

    fun parse(text: String): List<DanmakuComment> {
        require(text.length <= MAX_INPUT) { "弹幕文件过大，最多支持 8 MiB 文本" }
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "弹幕 XML 不允许文档类型或外部实体" }
        val result = ArrayList<DanmakuComment>()
        val handler = object : DefaultHandler() {
            private var parameters: String? = null
            private val body = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (qName == "d") { parameters = attributes.getValue("p"); body.setLength(0) }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (parameters != null && body.length < MAX_TEXT) body.append(ch, start, minOf(length, MAX_TEXT - body.length))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (qName != "d") return
                val fields = parameters?.split(',')
                parameters = null
                if (fields == null || fields.size < 4) return
                val seconds = fields[0].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 } ?: return
                val mode = when (fields[1]) { "1", "2", "3" -> DanmakuMode.SCROLL; "5" -> DanmakuMode.TOP; "4" -> DanmakuMode.BOTTOM; else -> return }
                val value = body.toString().trim()
                if (value.isEmpty()) return
                if (result.size >= MAX_COMMENTS) throw SAXException("弹幕超过 20 万条预算")
                val color = fields[3].toLongOrNull()?.takeIf { it in 0..0xffffff }?.toInt() ?: 0xffffff
                result += DanmakuComment((seconds * 1000).toLong(), value, color or (0xff shl 24), mode)
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("禁止外部实体")
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            // 各 Android XML 实现支持的安全特性不同；实体声明同时在输入边界被拒绝。
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach {
                runCatching { factory.setFeature(it, false) }
            }
            factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
        } catch (e: Exception) { throw IllegalArgumentException("弹幕 XML 无法解析：格式错误或超过预算", e) }
        return result.sortedBy { it.timeMs }
    }
}
