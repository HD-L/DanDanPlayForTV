package tv.dandan.app.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.Response
import tv.dandan.core.MediaSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MediaDataSourceFactory(private val context: Context, private val repo: AppRepository) : DataSource.Factory {
    override fun createDataSource(): DataSource = DefaultDataSource(context, NetworkDataSource(repo))
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class NetworkDataSource(private val repo: AppRepository) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; delegate?.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        close()
        val uri = dataSpec.uri.toString()
        val source = repo.sources().filter { runCatching { SourceRules.requireWithin(it, uri) }.isSuccess }.maxByOrNull { it.location.length }
        val reader = when (dataSpec.uri.scheme?.lowercase()) {
            "smb", "ftp" -> RemoteMediaDataSource(source ?: throw IOException("请先添加此网络来源及账号"))
            "http", "https" -> HttpMediaDataSource(source)
            else -> throw IOException("媒体地址协议不支持")
        }
        delegate = reader
        TransferMeter.opened()
        listeners.forEach(reader::addTransferListener)
        return try { reader.open(dataSpec) } catch (e: Exception) { close(); throw e }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: throw IOException("媒体来源尚未打开")
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { val old = delegate; delegate = null; old?.close() }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class RemoteMediaDataSource(private val source: MediaSource) : BaseDataSource(true) {
    private var reader: RemoteReader? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var advertisedSize = false
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val remote = try { RemoteReader.open(source, dataSpec.uri.toString(), dataSpec.position) }
            catch (error: PositionOutsideMediaException) { throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        reader = remote
        val window = try { ReadWindows.resolve(dataSpec.position, dataSpec.length, remote.size) }
            catch (error: PositionOutsideMediaException) { throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        remaining = window.remaining
        advertisedSize = remote.size >= 0
        opened = true
        transferStarted(dataSpec)
        return window.openLength
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val size = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val count = reader?.read(buffer, offset, size) ?: throw IOException("远程文件尚未打开")
        if (count < 0) { if (remaining > 0 && advertisedSize) throw EOFException("远程文件被截断或连接中断"); return C.RESULT_END_OF_INPUT }
        if (remaining >= 0) remaining -= count
        TransferMeter.add(count)
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        val handle = reader; reader = null; uri = null
        try { handle?.close() } finally { if (opened) { opened = false; transferEnded() } }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class HttpMediaDataSource(private val source: MediaSource?) : BaseDataSource(true) {
    private var response: Response? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var input: InputStream? = null
    private var parallel: ParallelRangeReader? = null
    private var uri: Uri? = null
    private var target = ""
    private var remaining = C.LENGTH_UNSET.toLong()
    /** 当前读取位置、服务端声明的文件总长度（未知时为 -1）、本次打开后连续读取的字节数。 */
    private var position = 0L
    private var total = -1L
    private var sequential = 0L
    private var advertisedSize = false
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val unbounded = dataSpec.length == C.LENGTH_UNSET.toLong()
        // 允许多连接时从头读取也发范围请求，服务端返回 206 才能知道总长度、之后切换到分段下载
        val result = SecureHttp.open(dataSpec.uri.toString(), source, dataSpec.position, dataSpec.length, forceRange = ParallelReading.enabled && unbounded)
        response = result
        if (result.code == 401 || result.code == 403) throw IOException("媒体来源认证失败，请检查账号密码")
        val window = try { ReadWindows.http(result.code, result.header("Content-Range"), result.body?.contentLength() ?: 0, dataSpec.position, dataSpec.length) }
            catch (error: PositionOutsideMediaException) { throw DataSourceException(error, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        val body = result.body
        if (window.remaining != 0L) input = body?.byteStream() ?: throw IOException("媒体响应为空")
        uri = Uri.parse(result.request.url.toString())
        headers = result.headers.toMultimap()
        target = dataSpec.uri.toString()
        position = dataSpec.position
        // 只有不限长度的范围请求、服务端返回 206 且给出总长度时，才可能切换到多连接
        total = if (result.code == 206 && unbounded) ParallelReading.total(result.header("Content-Range"), dataSpec.position) ?: -1 else -1
        sequential = 0
        remaining = window.remaining
        advertisedSize = result.code == 206 || (body?.contentLength() ?: -1) >= 0
        opened = true
        transferStarted(dataSpec)
        return window.openLength
    }
    /**
     * 连续读满 [ParallelReading.SWITCH_AFTER] 后改用多连接：从当前位置起由几个连接分段并行下载。
     * 打开文件、读取索引、拖动进度时播放器只读一小段就跳走，这时用单连接，不会预取一堆用不上的数据。
     */
    private fun switchToParallel() {
        if (!ParallelReading.enabled || total < 0 || sequential < ParallelReading.SWITCH_AFTER || total - position <= 2L * ParallelReading.CHUNK) return
        val single = response
        val url = target
        parallel = ParallelRangeReader(position, total, ParallelReading.CHUNK, ParallelReading.CONNECTIONS, ParallelReading.AHEAD,
            fetch = { start, length -> rangeStream(url, start, length) })
        response = null; input = null
        runCatching { single?.close() }
    }
    private fun rangeStream(target: String, position: Long, length: Long): RangeStream {
        val result = SecureHttp.open(target, source, position, length)
        try {
            if (result.code != 206) throw IOException("分段读取失败（HTTP ${result.code}）")
            if (ParallelReading.total(result.header("Content-Range"), position) == null) throw IOException("分段读取范围无效")
            val body = result.body ?: throw IOException("分段内容为空")
            return RangeStream(body.byteStream()) { result.close() }
        } catch (error: Exception) { result.close(); throw error as? IOException ?: IOException("分段读取失败", error) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (parallel == null) switchToParallel()
        val want = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val reader = parallel
        val count = if (reader != null) reader.read(buffer, offset, want) else input?.read(buffer, offset, want) ?: throw IOException("媒体尚未打开")
        if (count < 0) { if (remaining > 0 && (reader != null || advertisedSize)) throw EOFException("媒体内容被截断或连接中断"); return C.RESULT_END_OF_INPUT }
        // 多连接的下载线程自己计数
        if (reader == null) TransferMeter.add(count)
        if (remaining >= 0) remaining -= count
        position += count; sequential += count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = headers
    override fun close() {
        val handle = response; response = null; input = null; uri = null; headers = emptyMap()
        val reader = parallel; parallel = null
        try { reader?.close(); handle?.close() } finally { if (opened) { opened = false; transferEnded() } }
    }
}
