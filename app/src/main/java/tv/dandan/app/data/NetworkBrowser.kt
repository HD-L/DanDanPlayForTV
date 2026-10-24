package tv.dandan.app.data

import tv.dandan.core.*
import jcifs.smb.SmbFile
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.SAXParserFactory

internal object NetworkBrowser {
    fun list(source: MediaSource, directoryUri: String): List<MediaEntry> {
        SourceRules.requireWithin(source, directoryUri)
        return try {
            when (source.kind) {
                SourceKind.WEBDAV -> dav(source, directoryUri)
                SourceKind.SMB -> smb(source, directoryUri)
                SourceKind.FTP -> ftp(source, directoryUri)
                else -> throw IOException("此来源不支持网络目录浏览")
            }
        } catch (e: IOException) { throw IOException(if (e.message?.startsWith("目录") == true || e.message?.startsWith("来源") == true) e.message else "读取网络目录失败，请检查账号、地址和连接") }
        catch (_: Exception) { throw IOException("读取网络目录失败，响应格式无效或超出限制") }
    }
    private fun dav(source: MediaSource, directory: String): List<MediaEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>"""
        SecureHttp.open(directory, source, method = "PROPFIND", body = body).use { response ->
            if (response.code == 401 || response.code == 403) throw IOException("来源认证失败，请检查账号密码")
            if (response.code != 207) throw IOException("目录服务不支持 PROPFIND（HTTP ${response.code}）")
            val bytes = readLimited(response.body?.byteStream() ?: throw IOException("目录响应为空"), 2 * 1024 * 1024)
            val xml = bytes.toString(Charsets.UTF_8)
            if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) throw IOException("目录响应包含禁止的 XML 实体声明")
            val entries = mutableListOf<MediaEntry>()
            val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            runCatching { parser.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            parser.newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
                var depth = 0
                var tag = ""
                var href = ""
                var length = 0L
                var collection = false
                var hasStatus = false
                var hasSuccessStatus = false
                val value = StringBuilder()
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (++depth > 64) throw SAXException("目录 XML 层级过深")
                    tag = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfter(':') }
                    value.setLength(0)
                    if (tag == "response") { href = ""; length = 0; collection = false; hasStatus = false; hasSuccessStatus = false }
                    if (tag == "collection") collection = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (value.length + length > 8192) throw SAXException("目录字段过长"); value.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val name = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfter(':') }
                    when (name) {
                        "href" -> href = value.toString().trim()
                        "getcontentlength" -> length = value.toString().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
                        "status" -> { hasStatus = true; if (value.toString().contains(" 200 ")) hasSuccessStatus = true }
                        "response" -> {
                            if (entries.size >= 10000) throw SAXException("目录条目过多")
                            if (href.isNotEmpty() && (!hasStatus || hasSuccessStatus)) {
                                val target = runCatching { URI(directory).resolve(href).toASCIIString() }.getOrNull()
                                if (target != null && runCatching { SourceRules.requireWithin(source, target) }.isSuccess && URI(target).rawPath.trimEnd('/') != URI(directory).rawPath.trimEnd('/')) {
                                    val title = URLDecoder.decode(URI(target).rawPath.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                                    entries += MediaEntry(stableId(source.id, target), title, target, collection, source.id, length)
                                }
                            }
                        }
                    }
                    value.setLength(0); depth--
                }
                override fun resolveEntity(publicId: String?, systemId: String?) = throw SAXException("禁止外部 XML 实体")
            })
            return entries.distinctBy { it.uri }
        }
    }
    private fun smb(source: MediaSource, directory: String): List<MediaEntry> {
        val context = RemoteConnections.smbContext(source)
        try {
            SmbFile(directory.trimEnd('/') + "/", context).use { folder ->
                val result = mutableListOf<MediaEntry>()
                folder.children().use { children ->
                    while (children.hasNext()) children.next().use { file ->
                        if (result.size >= 10000) throw IOException("目录条目超过允许数量")
                        val title = file.name.trimEnd('/')
                        if (title !in listOf(".", "..")) {
                            val target = SourceRules.childUri(directory, title, file.isDirectory)
                            SourceRules.requireWithin(source, target)
                            result += MediaEntry(stableId(source.id, target), title, target, file.isDirectory, source.id, if (file.isDirectory) 0 else file.length())
                        }
                    }
                }
                return result
            }
        } finally { context.close() }
    }
    private fun ftp(source: MediaSource, directory: String): List<MediaEntry> {
        val client = RemoteConnections.ftp(source, directory)
        try {
            val files = client.mlistDir(URI(directory).path).let { if (client.replyCode >= 400) client.listFiles(URI(directory).path) else it }
            if (files.size > 10000) throw IOException("目录条目超过允许数量")
            return files.filter { it.name !in listOf(".", "..") && (it.isFile || it.isDirectory) }.map { file ->
                val target = SourceRules.childUri(directory, file.name, file.isDirectory)
                SourceRules.requireWithin(source, target)
                MediaEntry(stableId(source.id, target), file.name, target, file.isDirectory, source.id, file.size.coerceAtLeast(0))
            }
        } finally { runCatching { client.logout() }; runCatching { client.disconnect() } }
    }
}
