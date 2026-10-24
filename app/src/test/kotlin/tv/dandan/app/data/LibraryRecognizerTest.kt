package tv.dandan.app.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class LibraryRecognizerTest {
    private class Memory : StoreStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(changes: Map<String, String?>): Boolean { changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
    }
    private val secrets = object : StoredSecrets { override fun encrypt(value: String) = "e:$value"; override fun decrypt(value: String) = value.removePrefix("e:") }
    private val nas = MediaSource("nas", "NAS", SourceKind.WEBDAV, "https://nas.example.com/video/")
    private fun entry(name: String, folder: String = "[Group] 示例动画") = MediaEntry("id:$name", name, "https://nas.example.com/video/${java.net.URLEncoder.encode(folder, "UTF-8").replace("+", "%20")}/${java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")}", sourceId = nas.id, sizeBytes = 500_000_000)

    private fun store(): EncryptedStore = EncryptedStore(Memory(), secrets).apply { saveSource(nas); saveCredentials(ApiCredentials("test-app", "test-secret")) }
    private fun detail(id: Int, title: String, count: Int) = JSONObject().put("success", true).put("bangumi", JSONObject().put("animeId", id).put("animeTitle", title).put("imageUrl", "https://img.example.com/$id.jpg").put("summary", "简介 $title").put("rating", 8.6).put("typeDescription", "TV动画")
        .put("episodes", org.json.JSONArray((1..count).map { JSONObject().put("episodeId", id * 10000L + it).put("episodeTitle", "第${it}话 标题$it").put("episodeNumber", "$it") }))).toString()

    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }; start() }

    @Test fun exactFileMatchBindsEveryLocalEpisode() {
        val requests = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        server { request ->
            requests += request.requestUrl!!.encodedPath
            if (request.path!!.startsWith("/api/v2/match")) bodies += JSONObject(request.body.readUtf8())
            when {
                request.path!! == "/api/v2/match/batch" -> MockResponse().setBody("""{"success":true,"results":[{"success":true,"fileHash":"00000000000000000000000000000000","matchResult":{"episodeId":123450001,"animeId":12345,"animeTitle":"示例动画","episodeTitle":"第1话","imageUrl":"https://img.example.com/12345.jpg"}}]}""")
                request.path!!.startsWith("/api/v2/bangumi/12345") -> MockResponse().setBody(detail(12345, "示例动画", 3))
                else -> MockResponse().setResponseCode(404)
            }
        }.use { server ->
            val store = store()
            val files = listOf(entry("[Group] 示例动画 - 01 [1080p].mkv"), entry("[Group] 示例动画 - 02 [1080p].mkv"), entry("[Group] 示例动画 - 03 [1080p].mkv"))
            store.saveIndex(nas.id, files, store.historyEpoch())
            val groups = LibraryGrouping.group(files)
            var hashed = 0
            val recognizer = LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { hashed++; "0".repeat(32) }, pauseMs = 0)
            recognizer.runPending()
            val info = store.seriesInfos().values.single()
            assertEquals(RecognitionMethod.HASH, info.method)
            assertEquals("12345", info.animeId); assertEquals("示例动画", info.title); assertEquals("https://img.example.com/12345.jpg", info.imageUrl)
            assertEquals(listOf(123450001L, 123450002L, 123450003L), files.map { store.libraryBindings()[it.id] })
            // 只读取一个样本文件，合并到批量精确匹配里（服务端拒绝只有文件名的请求）
            assertEquals(1, hashed)
            val batch = bodies.single().getJSONArray("requests").getJSONObject(0)
            assertEquals("hashAndFileName", batch.getString("matchMode"))
            assertEquals("0".repeat(32), batch.getString("fileHash"))
            assertEquals(listOf("/api/v2/match/batch", "/api/v2/bangumi/12345"), requests)
            assertEquals(1, recognizer.version)
            // 再运行一次不发请求：结果已保存且文件没有变化
            val before = requests.size
            recognizer.runPending()
            assertEquals(before, requests.size)
        }
    }

    @Test fun fallsBackToHashThenSearchAndNeverGuessesUnrelatedTitles() {
        server { request ->
            val path = request.path!!
            when {
                path.startsWith("/api/v2/match") -> {
                    val body = JSONObject(request.body.readUtf8())
                    if (body.optString("matchMode") == "hashAndFileName") MockResponse().setBody("""{"success":true,"isMatched":false,"matches":[{"episodeId":999990001,"animeId":99999,"animeTitle":"完全无关","episodeTitle":"第1话"}]}""")
                    else MockResponse().setBody("""{"success":true,"isMatched":false,"matches":[]}""")
                }
                path.startsWith("/api/v2/search/anime") -> MockResponse().setBody("""{"success":true,"animes":[{"animeId":555,"animeTitle":"示例动画 第二季"},{"animeId":444,"animeTitle":"示例动画"}]}""")
                path.startsWith("/api/v2/bangumi/444") -> MockResponse().setBody(detail(444, "示例动画", 2))
                else -> MockResponse().setResponseCode(404)
            }
        }.use { server ->
            val store = store()
            val files = listOf(entry("01.mkv", "示例动画"), entry("02.mkv", "示例动画"))
            val groups = LibraryGrouping.group(files)
            val recognizer = LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { "a".repeat(32) }, pauseMs = 0)
            recognizer.runPending()
            val info = store.seriesInfos().values.single()
            assertEquals(RecognitionMethod.SEARCH, info.method)
            assertEquals("444", info.animeId)
            assertEquals(4440002L, store.libraryBindings()[files[1].id])
        }
    }

    @Test fun romajiNamesMatchOfficialAliases() {
        server { request ->
            val path = request.path!!
            when {
                path.startsWith("/api/v2/match") -> MockResponse().setBody("""{"success":true,"isMatched":false,"matches":[{"episodeId":3330001,"animeId":333,"animeTitle":"别的动画","episodeTitle":"第1话"},{"episodeId":2220001,"animeId":222,"animeTitle":"示例动画","episodeTitle":"第1话"}]}""")
                path.startsWith("/api/v2/bangumi/333") -> MockResponse().setBody(JSONObject(detail(333, "别的动画", 2)).apply { getJSONObject("bangumi").put("titles", org.json.JSONArray().put(JSONObject().put("language", "罗马音").put("title", "Betsu no Anime"))) }.toString())
                path.startsWith("/api/v2/bangumi/222") -> MockResponse().setBody(JSONObject(detail(222, "示例动画", 2)).apply { getJSONObject("bangumi").put("titles", org.json.JSONArray().put(JSONObject().put("language", "罗马音").put("title", "Rei no Anime"))) }.toString())
                else -> MockResponse().setResponseCode(404)
            }
        }.use { server ->
            val store = store()
            val files = listOf(entry("[Group] Rei no Anime - 01 [1080p].mkv", "[Group] Rei no Anime"), entry("[Group] Rei no Anime - 02 [1080p].mkv", "[Group] Rei no Anime"))
            val groups = LibraryGrouping.group(files)
            LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { "a".repeat(32) }, pauseMs = 0).runPending()
            val info = store.seriesInfos().values.single()
            assertEquals("222", info.animeId); assertEquals("示例动画", info.title)
            assertEquals(2220002L, store.libraryBindings()[files[1].id])
        }
    }

    @Test fun unreadableFilesNeverSendFakeHashesAndFallBackToSearch() {
        val paths = mutableListOf<String>()
        server { request ->
            paths += request.requestUrl!!.encodedPath
            when {
                request.path!!.startsWith("/api/v2/search/anime") -> MockResponse().setBody("""{"success":true,"animes":[{"animeId":444,"animeTitle":"示例动画"}]}""")
                request.path!!.startsWith("/api/v2/bangumi/444") -> MockResponse().setBody(detail(444, "示例动画", 2))
                else -> MockResponse().setResponseCode(404)
            }
        }.use { server ->
            val store = store()
            val files = listOf(entry("[Group] 示例动画 - 01 [1080p].mkv"), entry("[Group] 示例动画 - 02 [1080p].mkv"))
            val groups = LibraryGrouping.group(files)
            LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { throw java.io.IOException("不支持随机读取") }, pauseMs = 0).runPending()
            assertTrue(paths.none { it.contains("match") })
            assertEquals("444", store.seriesInfos().values.single().animeId)
        }
    }

    @Test fun quotaExhaustionStopsAndKeepsWorksPending() {
        server { MockResponse().setBody("""{"success":false,"errorCode":429,"errorMessage":"已达到接口调用配额上限"}""") }.use { server ->
            val store = store()
            val files = listOf(entry("[Group] 示例动画 - 01 [1080p].mkv"))
            val groups = LibraryGrouping.group(files)
            val recognizer = LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { "a".repeat(32) }, pauseMs = 0)
            recognizer.runPending()
            assertTrue(recognizer.state.message.contains("配额"))
            assertTrue(recognizer.state.message.startsWith("识别暂停"))
            assertTrue(store.seriesInfos().isEmpty())
        }
    }

    @Test fun networkFailureStopsWithoutMarkingWorksUnrecognizable() {
        server { MockResponse().setResponseCode(500) }.use { server ->
            val store = store()
            val files = listOf(entry("[Group] 示例动画 - 01 [1080p].mkv"))
            val groups = LibraryGrouping.group(files)
            val recognizer = LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { "a".repeat(32) }, pauseMs = 0)
            recognizer.runPending()
            assertTrue(recognizer.state.message.startsWith("识别中断"))
            assertTrue(store.seriesInfos().isEmpty())
        }
    }

    @Test fun personalVideosAreNeverSentAndManualChoiceSurvivesAutomaticRuns() {
        val paths = mutableListOf<String>()
        server { request ->
            paths += request.requestUrl!!.encodedPath
            when {
                request.path!!.startsWith("/api/v2/bangumi/777") -> MockResponse().setBody(detail(777, "手动作品", 2))
                request.path!!.startsWith("/api/v2/match") -> MockResponse().setBody("""{"success":true,"isMatched":true,"matches":[{"episodeId":123450001,"animeId":12345,"animeTitle":"自动作品","episodeTitle":"第1话"}]}""")
                request.path!!.startsWith("/api/v2/bangumi/12345") -> MockResponse().setBody(detail(12345, "自动作品", 2))
                else -> MockResponse().setResponseCode(404)
            }
        }.use { server ->
            val store = store()
            val clips = listOf(entry("客厅.mp4", "家庭录像"), entry("公园.mp4", "家庭录像"))
            val show = listOf(entry("[Group] 示例动画 - 01 [1080p].mkv"), entry("[Group] 示例动画 - 02 [1080p].mkv"))
            val groups = LibraryGrouping.group(clips + show)
            val recognizer = LibraryRecognizer(store, DanDanApi({ store.credentials() }, server.url("/").toString()), { groups }, { "a".repeat(32) }, pauseMs = 0)
            val series = groups.first { it.kind == LibraryKind.SERIES }
            recognizer.assign(series, AnimeSummary("777", "手动作品"))
            recognizer.runPending()
            assertEquals("777", store.seriesInfos()[series.key]?.animeId)
            assertEquals(7770002L, store.libraryBindings()[show[1].id])
            assertTrue(store.seriesInfos().keys.none { it.startsWith("c:") })
            assertTrue(paths.none { it.contains("match") })
        }
    }
}
