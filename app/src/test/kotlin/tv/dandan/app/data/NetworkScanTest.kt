package tv.dandan.app.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class NetworkScanTest {
    @Test fun scannerTraversesRealWebDavResponsesAndKeepsSidecarsAndStableIds() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method != "PROPFIND" || request.getHeader("Depth") != "1") return MockResponse().setResponseCode(400)
                    val content = if (request.path == "/library/") """
                        <d:response><d:href>/library/sub/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        <d:response><d:href>/library/10.mp4</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                        <d:response><d:href>/library/10.srt</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    """ else if (request.path == "/library/sub/") """
                        <d:response><d:href>/library/sub/2.mp4</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                    """ else return MockResponse().setResponseCode(404)
                    return MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$content</d:multistatus>")
                }
            }
            server.start()
            val source = MediaSource("dav", "DAV", SourceKind.WEBDAV, server.url("/library/").toString())
            val scanner = LibraryScanner({ s, uri -> entriesForSource(s, NetworkBrowser.list(s, uri)) })
            val result = scanner.scan(source, ScanBudget(), { false }) {}
            assertEquals(ScanPhase.COMPLETE, result.phase)
            assertEquals(2, result.directories)
            assertEquals(2, result.entries.size)
            val movie = result.entries.first { it.title == "10.mp4" }
            assertEquals(server.url("/library/10.srt").toString(), movie.subtitleUri)
            assertEquals(stableId(source.id, movie.uri), movie.id)
        }
    }
}
