package tv.dandan.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenSubtitlesApiTest {
    private val account = SubtitleAccount("test-key", "test-user", "test-password", "TestTV v1.0")
    @Test fun searchRetainsOnlyProviderHashEvidenceAndEncodesNames() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"attributes":{"language":"zh-cn","release":"发布版本","moviehash_match":true,"feature_details":{"title":"作品"},"files":[{"file_id":42}]}}]}"""))
            val match = OpenSubtitlesApi(account, server.url("/api/v1/").toString()).search("作品 + 第1集", "zh-cn", "0123456789abcdef").single()
            assertEquals(42, match.fileId); assertTrue(match.hashMatched)
            val request = server.takeRequest()
            assertEquals("作品 + 第1集", request.requestUrl!!.queryParameter("query"))
            assertEquals("0123456789abcdef", request.requestUrl!!.queryParameter("moviehash"))
            assertEquals("test-key", request.getHeader("Api-Key")); assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun loginDownloadUsesBearerAndCdnGetsNoCredentialsEvenAfterRedirect() {
        MockWebServer().use { server -> MockWebServer().use { cdn ->
            server.start(); cdn.start()
            server.enqueue(MockResponse().setBody("""{"token":"test-token"}"""))
            server.enqueue(MockResponse().setBody("""{"link":"${cdn.url("/signed-subtitle")}"}"""))
            cdn.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/final")))
            cdn.enqueue(MockResponse().setBody("1\n00:00:00,000 --> 00:00:01,000\n字幕"))
            assertTrue(OpenSubtitlesApi(account, server.url("/api/v1/").toString()).download(42).toString(Charsets.UTF_8).contains("字幕"))
            val login = server.takeRequest(); assertEquals("/api/v1/login", login.path)
            assertEquals("test-password", org.json.JSONObject(login.body.readUtf8()).getString("password"))
            val download = server.takeRequest(); assertEquals("Bearer test-token", download.getHeader("Authorization"))
            assertEquals("srt", org.json.JSONObject(download.body.readUtf8()).getString("sub_format"))
            repeat(2) { val req = cdn.takeRequest(); assertNull(req.getHeader("Api-Key")); assertNull(req.getHeader("Authorization")) }
        } }
    }
    @Test fun untrustedLoginRegionalHostIsRejectedBeforeCredentialTransmission() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"token":"test-token","base_url":"https://evil.example/api/v1/"}"""))
            assertThrows(IllegalArgumentException::class.java) { OpenSubtitlesApi(account, server.url("/api/v1/").toString()).download(42) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun quotaErrorContainsNoProviderSecretMessage() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(429).setBody("test-password test-key"))
            val error = assertThrows(IOException::class.java) { OpenSubtitlesApi(account, server.url("/api/v1/").toString()).search("作品", "zh-cn") }
            assertTrue(error.message.orEmpty().contains("配额")); assertFalse(error.message.orEmpty().contains("test-password"))
        }
    }
    @Test fun compressedFilesHavePostExpansionBudgetAndRejectAmbiguousArchive() {
        val api = OpenSubtitlesApi(account)
        val normal = "1\n00:00:00,000 --> 00:00:01,000\n字幕".toByteArray()
        fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
        assertArrayEquals(normal, api.unpack(gzip(normal)))
        assertThrows(Exception::class.java) { api.unpack(gzip(ByteArray(OpenSubtitlesApi.MAX_BYTES + 1))) }
        val zip = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive ->
            listOf("a.srt", "b.srt").forEach { archive.putNextEntry(ZipEntry(it)); archive.write(normal); archive.closeEntry() }
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { api.unpack(zip) }
    }
    @Test fun missingConfigurationAndInvalidLanguageFailBeforeAnyNetwork() {
        MockWebServer().use { server ->
            server.start()
            assertThrows(IllegalArgumentException::class.java) { OpenSubtitlesApi(SubtitleAccount(), server.url("/").toString()).search("作品", "zh-cn") }
            assertThrows(IllegalArgumentException::class.java) { OpenSubtitlesApi(account, server.url("/").toString()).search("作品", "../x") }
            assertEquals(0, server.requestCount)
        }
    }
}
