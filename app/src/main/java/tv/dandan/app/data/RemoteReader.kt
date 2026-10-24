package tv.dandan.app.data

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import tv.dandan.core.*

internal abstract class RemoteReader : Closeable {
    abstract val size: Long
    abstract fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun asInputStream(): InputStream = object : InputStream() {
        override fun read(): Int { val data = ByteArray(1); return if (this@RemoteReader.read(data, 0, 1) < 0) -1 else data[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int = this@RemoteReader.read(b, off, len)
    }
    companion object {
        fun open(source: MediaSource, uri: String, position: Long): RemoteReader {
            SourceRules.requireWithin(source, uri)
            require(position >= 0) { "读取位置无效" }
            return when (source.kind) {
                SourceKind.SMB -> smb(source, uri, position)
                SourceKind.FTP -> ftp(source, uri, position)
                else -> throw IOException("来源不支持远程读取")
            }
        }
        private fun smb(source: MediaSource, uri: String, position: Long): RemoteReader {
            val context = RemoteConnections.smbContext(source)
            var file: SmbFile? = null
            var reader: SmbRandomAccessFile? = null
            try {
                file = SmbFile(uri, context)
                reader = SmbRandomAccessFile(file, "r")
                val size = reader.length()
                ReadWindows.resolve(position, -1, size)
                reader.seek(position)
                val handle = reader
                val resource = file
                return object : RemoteReader() {
                    override val size = size
                    private var closed = false
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (closed) throw IOException("文件已经关闭")
                        return try { handle.read(buffer, offset, length) } catch (_: IOException) { throw IOException("SMB 文件读取失败，请检查连接") }
                    }
                    override fun close() {
                        if (closed) return
                        closed = true
                        try { handle.close() } finally { try { resource.close() } finally { context.close() } }
                    }
                }
            } catch (error: Exception) {
                runCatching { reader?.close() }; runCatching { file?.close() }; runCatching { context.close() }
                if (error is PositionOutsideMediaException) throw error
                throw IOException("SMB 文件无法打开，请检查账号、共享目录和连接")
            }
        }
        private fun ftp(source: MediaSource, uri: String, position: Long): RemoteReader {
            val client = RemoteConnections.ftp(source, uri)
            var stream: InputStream? = null
            try {
                val path = URI(uri).path
                val size = client.getSize(path)?.toLongOrNull() ?: -1L
                ReadWindows.resolve(position, -1, size)
                if (size >= 0 && position == size) return emptyReader(size) { try { client.logout() } finally { client.disconnect() } }
                client.setRestartOffset(position)
                stream = client.retrieveFileStream(path) ?: throw IOException("FTP 服务拒绝读取文件或随机定位")
                val input = stream
                return object : RemoteReader() {
                    override val size = size
                    private var closed = false
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (closed) throw IOException("文件已经关闭")
                        return try { input.read(buffer, offset, length) } catch (_: IOException) { throw IOException("FTP 文件读取失败，请检查连接") }
                    }
                    override fun close() {
                        if (closed) return
                        closed = true
                        try { input.close() } finally {
                            try { client.completePendingCommand() } finally { try { client.logout() } finally { client.disconnect() } }
                        }
                    }
                }
            } catch (error: Exception) {
                runCatching { stream?.close() }; runCatching { client.disconnect() }
                if (error is PositionOutsideMediaException) throw error
                throw IOException("FTP 文件无法打开，请检查账号、目录和服务器定位支持")
            }
        }
        private fun emptyReader(size: Long, close: () -> Unit): RemoteReader = object : RemoteReader() {
            override val size = size
            private var closed = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (closed) throw IOException("文件已经关闭")
                return if (length == 0) 0 else -1
            }
            override fun close() { if (!closed) { closed = true; close.invoke() } }
        }
    }
}

internal object RemoteConnections {
    fun smbContext(source: MediaSource): CIFSContext {
        val config = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
            setProperty("jcifs.smb.client.minVersion", "SMB202")
        }
        val context = BaseContext(PropertyConfiguration(config))
        if (source.username.isBlank()) return context.withAnonymousCredentials()
        val domain = if ('\\' in source.username) source.username.substringBefore('\\') else ""
        val user = source.username.substringAfter('\\')
        return context.withCredentials(NtlmPasswordAuthenticator(domain, user, source.password))
    }
    fun ftp(source: MediaSource, uri: String): FTPClient {
        val address = URI(uri)
        val client = FTPClient().apply {
            controlEncoding = "UTF-8"
            connectTimeout = 10000
            defaultTimeout = 10000
            setDataTimeout(Duration.ofSeconds(15))
        }
        try {
            client.connect(address.host, if (address.port > 0) address.port else 21)
            client.soTimeout = 10000
            if (!FTPReply.isPositiveCompletion(client.replyCode) || !client.login(source.username.ifBlank { "anonymous" }, source.password.ifBlank { if (source.username.isBlank()) "guest@" else "" })) throw IOException("FTP 来源认证失败")
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("FTP 服务不支持二进制读取")
            client.enterLocalPassiveMode()
            return client
        } catch (_: Exception) { runCatching { client.disconnect() }; throw IOException("FTP 来源连接或认证失败，请检查地址和账号") }
    }
}
