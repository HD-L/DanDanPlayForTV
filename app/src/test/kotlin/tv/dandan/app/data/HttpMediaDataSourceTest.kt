package tv.dandan.app.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class HttpMediaDataSourceTest {
    private val data = ByteArray(22 * 1024 * 1024 + 77) { (it * 7 % 253).toByte() }

    private fun server(rangeSupport: Boolean = true) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (!rangeSupport || range == null) return MockResponse().setBody(Buffer().write(data))
                val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)!!
                val start = match.groupValues[1].toInt()
                val end = (match.groupValues[2].toIntOrNull() ?: (data.size - 1)).coerceAtMost(data.size - 1)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${data.size}").setBody(Buffer().write(data, start, end - start + 1))
            }
        }
        start()
    }

    private fun read(server: MockWebServer, position: Long): ByteArray {
        val source = HttpMediaDataSource(null)
        val length = source.open(DataSpec(Uri.parse(server.url("/video.mp4").toString()), position, C.LENGTH_UNSET.toLong()))
        assertEquals(data.size - position, length)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        source.close()
        return out.toByteArray()
    }

    @Test fun switchesToMultiConnectionOnlyAfterSteadyReading() {
        server().use { server ->
            ParallelReading.enabled = true
            assertArrayEquals(data, read(server, 0))
            val ranges = (0 until server.requestCount).map { server.takeRequest().getHeader("Range").orEmpty() }
            // 先用一个不限长度的请求；连续读满 16MB 后，其余部分按有界范围分段并行下载
            assertEquals("bytes=0-", ranges.first())
            val parallel = ranges.drop(1)
            assertTrue(ranges.toString(), parallel.size >= 2 && parallel.all { it.matches(Regex("bytes=\\d+-\\d+")) })
            assertTrue(ranges.toString(), parallel.minOf { it.removePrefix("bytes=").substringBefore('-').toLong() } >= ParallelReading.SWITCH_AFTER)
            assertArrayEquals(data.copyOfRange(4_000_000, data.size), read(server, 4_000_000))
        }
    }

    @Test fun probingReadsStayOnOneConnection() {
        server().use { server ->
            ParallelReading.enabled = true
            val source = HttpMediaDataSource(null)
            val url = Uri.parse(server.url("/video.mkv").toString())
            val buffer = ByteArray(64 * 1024)
            // 打开文件时读文件头、跳到文件尾读索引、再跳到播放位置，每次只读一小段
            for (position in listOf(0L, data.size - 200_000L, 3_000_000L)) {
                source.open(DataSpec(url, position, C.LENGTH_UNSET.toLong()))
                var done = 0
                while (done < 150_000) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    assertEquals(data[(position + done).toInt()], buffer[0])
                    done += count
                }
                source.close()
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun singleConnectionAndServersWithoutRangesStillWork() {
        server(rangeSupport = false).use { server ->
            ParallelReading.enabled = true
            assertArrayEquals(data, read(server, 0))
        }
        server().use { server ->
            ParallelReading.enabled = false
            try {
                assertArrayEquals(data, read(server, 0))
                assertEquals(1, server.requestCount)
            } finally { ParallelReading.enabled = true }
        }
    }
}
