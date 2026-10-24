package tv.dandan.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*
import java.io.IOException

class DanDanApiTest {
    private val credentials = ApiCredentials("test-app", "test-secret")

    @Test fun seasonsUseOfficialAvailableQuarterAndFilteredList() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":true,"seasons":[{"year":2026,"month":10,"seasonName":"2026年秋季"}]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"bangumiList":[{"bangumiId":"42","animeTitle":"季度动画"}]}"""))
            val api = DanDanApi({ credentials }, server.url("/").toString())
            val season = api.seasons().single()
            assertEquals(10, season.month)
            assertEquals("2026年秋季", season.name)
            assertEquals("季度动画", api.season(season).single().title)
            assertEquals("/api/v2/bangumi/season/anime", server.takeRequest().requestUrl!!.encodedPath)
            val list = server.takeRequest().requestUrl!!
            assertEquals("/api/v2/bangumi/season/anime/2026/10", list.encodedPath)
            assertEquals("true", list.queryParameter("filterAdultContent"))
        }
    }

    @Test fun missingCredentialsFailBeforeNetworkRequest() {
        MockWebServer().use { server ->
            server.start()
            val api = DanDanApi({ ApiCredentials() }, server.url("/").toString())
            val error = assertThrows(IOException::class.java) { api.search("动画") }
            assertTrue(error.message.orEmpty().contains("凭证"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun signedSearchEncodesKeywordAndUsesOfficialResponseShape() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":true,"errorCode":0,"animes":[{"animeId":12,"bangumiId":"tmdb-tv-12","animeTitle":"动画 A","imageUrl":"https://example.com/a.jpg","rating":8.5}]}"""))
            val result = DanDanApi({ credentials }, server.url("/").toString()).search("动画 + A")
            assertEquals("tmdb-tv-12", result.single().id)
            val request = server.takeRequest()
            assertEquals("动画 + A", request.requestUrl!!.queryParameter("keyword"))
            assertEquals("test-app", request.getHeader("X-AppId"))
            assertNull(request.getHeader("X-AppSecret"))
            assertNotNull(request.getHeader("X-Signature"))
        }
    }

    @Test fun http200BusinessFailureDoesNotBecomeEmptySuccess() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":false,"errorCode":17,"errorMessage":"失败 test-secret"}"""))
            val error = assertThrows(IOException::class.java) { DanDanApi({ credentials }, server.url("/").toString()).weekly() }
            assertFalse(error.message.orEmpty().contains("test-secret"))
        }
    }

    @Test fun commentRedirectDoesNotSendSigningOrCredentialHeadersToCdn() {
        MockWebServer().use { server -> MockWebServer().use { cdn ->
            server.start(); cdn.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/comments")))
            cdn.enqueue(MockResponse().setBody("""{"count":2,"comments":[{"cid":1,"p":"12.34,4,16711680,9","m":"底部"},{"cid":2,"p":"1.50,5,65280,2","m":"顶部"}]}"""))
            val comments = DanDanApi({ credentials }, server.url("/").toString()).comments(123450001)
            assertEquals(12340L, comments[0].timeMs)
            assertEquals(DanmakuMode.BOTTOM, comments[0].mode)
            assertEquals(0xffff0000.toInt(), comments[0].color)
            assertEquals(DanmakuMode.TOP, comments[1].mode)
            val request = cdn.takeRequest()
            assertNull(request.getHeader("X-AppId"))
            assertNull(request.getHeader("X-AppSecret"))
            assertNull(request.getHeader("X-Signature"))
        } }
    }

    @Test fun nameMatchNeverFabricatesFileHashAndPreservesShift() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":true,"errorCode":0,"isMatched":true,"matches":[{"episodeId":123450001,"animeTitle":"动画","episodeTitle":"第1集","shift":-1.5}]}"""))
            val match = DanDanApi({ credentials }, server.url("/").toString()).matchByName("动画 第1集.mkv", 1000).single()
            assertEquals(-1500L, match.shiftMs)
            assertTrue(match.exact)
            val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("动画 第1集", body.getString("fileName"))
            assertEquals("fileNameOnly", body.getString("matchMode"))
            assertFalse(body.has("fileHash"))
        }
    }

    @Test fun hashMatchSendsHashDurationAndSurfacesServerReason() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":true,"errorCode":0,"isMatched":true,"matches":[{"episodeId":1,"animeTitle":"A","episodeTitle":"1","shift":0}]}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errorCode":400,"errorMessage":"参数错误","errorDetail":"fileHash 格式不正确"}"""))
            server.enqueue(MockResponse().setBody("""{"success":false,"errorCode":-1,"errorMessage":"签名过期"}"""))
            val api = DanDanApi({ credentials }, server.url("/").toString())
            api.matchByName("A 01.mkv", 2000, "0123456789ABCDEF0123456789abcdef", 1440)
            val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("hashAndFileName", body.getString("matchMode"))
            assertEquals("0123456789abcdef0123456789abcdef", body.getString("fileHash"))
            assertEquals(1440, body.getInt("videoDuration"))
            val http = runCatching { api.matchByName("A 01.mkv", 2000) }.exceptionOrNull()!!.message!!
            assertTrue(http, http.contains("HTTP 400") && http.contains("fileHash 格式不正确"))
            val failed = runCatching { api.matchByName("A 01.mkv", 2000) }.exceptionOrNull()!!.message!!
            assertTrue(failed, failed.contains("签名过期"))
        }
    }

    @Test fun invalidHashFallsBackToNameOnly() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"success":true,"errorCode":0,"isMatched":false,"matches":[]}"""))
            DanDanApi({ credentials }, server.url("/").toString()).matchByName("A.mkv", 1, "not-a-hash")
            val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("fileNameOnly", body.getString("matchMode"))
            assertFalse(body.has("fileHash"))
        }
    }
}
