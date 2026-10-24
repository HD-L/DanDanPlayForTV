package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class FtpReaderTest {
    @Test fun openingAtKnownEndDoesNotRequireServerToAcceptEofRetrieval() {
        FtpFixture(rejectEof = true).use { server ->
            val source = MediaSource("ftp", "影院", SourceKind.FTP, "ftp://127.0.0.1:${server.port}/video/")
            RemoteReader.open(source, "${source.location}episode.mkv", 10).use { reader ->
                assertEquals(10L, reader.size)
                assertEquals(-1, reader.read(ByteArray(1), 0, 1))
            }
        }
    }

    @Test fun openingPastKnownEndReportsPositionOutsideMedia() {
        FtpFixture().use { server ->
            val source = MediaSource("ftp", "影院", SourceKind.FTP, "ftp://127.0.0.1:${server.port}/video/")
            assertThrows(PositionOutsideMediaException::class.java) { RemoteReader.open(source, "${source.location}episode.mkv", 11) }
        }
    }

    @Test fun seekingUsesRestBeforeStreamingRetrAndCloseEndsControlSession() {
        FtpFixture().use { server ->
            val source = MediaSource("ftp", "影院", SourceKind.FTP, "ftp://127.0.0.1:${server.port}/video/")
            RemoteReader.open(source, "${source.location}episode.mkv", 4).use { reader ->
                assertEquals(10L, reader.size)
                val buffer = ByteArray(2)
                assertEquals(2, reader.read(buffer, 0, 2))
                assertEquals("45", buffer.toString(Charsets.US_ASCII))
            }
            assertTrue(server.commands.contains("REST 4"))
            assertTrue(server.commands.contains("RETR /video/episode.mkv"))
            assertTrue(server.commands.indexOf("REST 4") < server.commands.indexOf("RETR /video/episode.mkv"))
            assertTrue(server.commands.contains("QUIT"))
        }
    }

    private class FtpFixture(private val rejectEof: Boolean = false) : Closeable {
        private val listener = ServerSocket(0)
        val port = listener.localPort
        val commands = CopyOnWriteArrayList<String>()
        private var control: Socket? = null
        private var passive: ServerSocket? = null
        private val worker = thread(isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    control = socket
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { output.write(text + "\r\n"); output.flush() }
                    reply("220 fixture")
                    var offset = 0
                    while (true) {
                        val command = input.readLine() ?: break
                        commands += command
                        when (command.substringBefore(' ')) {
                            "USER" -> reply("230 logged in")
                            "TYPE" -> reply("200 binary")
                            "SIZE" -> reply("213 10")
                            "PASV" -> {
                                passive = ServerSocket(0)
                                val p = passive!!.localPort
                                reply("227 Entering Passive Mode (127,0,0,1,${p / 256},${p % 256})")
                            }
                            "REST" -> { offset = command.substringAfter(' ').toInt(); reply("350 restart accepted") }
                            "RETR" -> {
                                if (rejectEof && offset >= 10) { reply("550 empty retrieval denied"); passive!!.close(); continue }
                                reply("150 opening stream")
                                passive!!.accept().use { it.getOutputStream().write("0123456789".substring(offset).toByteArray()) }
                                passive!!.close(); reply("226 complete")
                            }
                            "QUIT" -> { reply("221 goodbye"); break }
                            else -> reply("200 okay")
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        override fun close() { runCatching { passive?.close() }; runCatching { control?.close() }; listener.close(); worker.join(1000) }
    }
}
