package tv.dandan.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*
import java.io.IOException

class NetworkBrowserTest {
    @Test fun davListingRejectsForeignAndParentLinksAndAssociatesSidecars() {
        MockWebServer().use { server ->
            server.start()
            val source = MediaSource("dav", "影院", SourceKind.WEBDAV, server.url("/video/").toString(), "alice", "secret")
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/video/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/video/%E5%8A%A8%E7%94%BB%20%23.mkv</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                <d:response><d:href>/video/%E5%8A%A8%E7%94%BB%20%23.srt</d:href><d:propstat><d:prop/></d:propstat></d:response>
                <d:response><d:href>https://other.example/private.mkv</d:href><d:propstat><d:prop/></d:propstat></d:response>
                <d:response><d:href>/private/leak.mkv</d:href><d:propstat><d:prop/></d:propstat></d:response>
                </d:multistatus>"""))
            val result = associateSidecars(NetworkBrowser.list(source, source.location))
            assertEquals(1, result.size)
            assertEquals("动画 #.mkv", result.single().title)
            assertTrue(result.single().subtitleUri.endsWith("%E5%8A%A8%E7%94%BB%20%23.srt"))
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }

    @Test fun davExternalEntitiesAreRejected() {
        MockWebServer().use { server ->
            server.start()
            val source = MediaSource("dav", "影院", SourceKind.WEBDAV, server.url("/video/").toString())
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]><x>&leak;</x>"""))
            assertThrows(IOException::class.java) { NetworkBrowser.list(source, source.location) }
        }
    }

    @Test fun basicCredentialsAreRemovedOnCrossOriginRedirect() {
        MockWebServer().use { sourceServer -> MockWebServer().use { target ->
            sourceServer.start(); target.start()
            val source = MediaSource("dav", "影院", SourceKind.WEBDAV, sourceServer.url("/video/").toString(), "alice", "secret")
            sourceServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/public")))
            target.enqueue(MockResponse().setBody("subtitle"))
            SecureHttp.open(source.location, source).close()
            assertNotNull(sourceServer.takeRequest().getHeader("Authorization"))
            assertNull(target.takeRequest().getHeader("Authorization"))
        } }
    }
}
