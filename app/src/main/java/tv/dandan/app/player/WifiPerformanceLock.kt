package tv.dandan.app.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 播放期间让 Wi-Fi 保持高性能 / 低延迟模式。电视在省电模式下会把收包攒到信标间隔再处理，
 * 局域网往返延迟从几毫秒变成上百毫秒，单个 TCP 连接的速度随之掉到每秒一两兆，大码率视频就会反复缓冲。
 */
internal class WifiPerformanceLock(context: Context) {
    private val locks: List<WifiManager.WifiLock> = run {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return@run emptyList()
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY) else emptyList()
        modes.mapNotNull { mode -> runCatching { wifi.createWifiLock(mode, "dandan-tv:playback-$mode").apply { setReferenceCounted(false) } }.getOrNull() }
    }

    fun hold(on: Boolean) {
        locks.forEach { lock -> runCatching { if (on) { if (!lock.isHeld) lock.acquire() } else if (lock.isHeld) lock.release() } }
    }
}
