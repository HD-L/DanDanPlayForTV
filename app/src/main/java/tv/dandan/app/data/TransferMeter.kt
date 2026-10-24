package tv.dandan.app.data

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 播放器从网络来源（HTTP/WebDAV、SMB、FTP）读取的字节数，播放页据此显示实时网络速度。
 * 另记打开次数与预取后没用上的字节数，排查卡顿时写进日志（播放器频繁跳读会让预取白费）。
 */
object TransferMeter {
    private val total = AtomicLong()
    private val openCount = AtomicInteger()
    private val unused = AtomicLong()
    fun add(bytes: Int) { if (bytes > 0) total.addAndGet(bytes.toLong()) }
    fun opened() { openCount.incrementAndGet() }
    fun wasted(bytes: Long) { if (bytes > 0) unused.addAndGet(bytes) }
    val bytes: Long get() = total.get()
    val opens: Int get() = openCount.get()
    val wastedBytes: Long get() = unused.get()
}

/**
 * 播放页在前台时为真。后台的批量读取（片库识别读取文件特征等）会等播放结束再继续，
 * 不和正在播放的视频抢局域网带宽。
 */
object PlaybackGate {
    @Volatile var active = false
}
