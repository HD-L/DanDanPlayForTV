package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class LibraryStoreTest {
    private class Memory : StoreStorage {
        val values = mutableMapOf<String, String>(); var fail = false
        override fun read(key: String) = values[key]
        override fun write(changes: Map<String, String?>): Boolean { if (fail) return false; changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
    }
    private val secrets = object : StoredSecrets { override fun encrypt(value: String) = "encrypted:$value"; override fun decrypt(value: String) = value.removePrefix("encrypted:") }
    private val source = MediaSource("s", "来源", SourceKind.HTTP, "https://example.com/movie.mp4")
    private val media = MediaEntry("m", "影片", source.location, sourceId = source.id)
    @Test fun deletionRemovesFavoritesIndexedMediaAndUnplayedEpisodeBindingsAcrossStores() {
        val memory = Memory(); val first = EncryptedStore(memory, secrets); val second = EncryptedStore(memory, secrets)
        first.saveSource(source); first.toggleFavorite(media); first.saveEpisode(media.id, EpisodeBinding(42), media.sourceId)
        first.saveIndex(source.id, listOf(media), first.historyEpoch())
        second.deleteSource(source.id)
        assertTrue(first.favorites().isEmpty()); assertTrue(first.index().isEmpty()); assertNull(first.episodeFor(media.id))
    }
    @Test fun seriesRecognitionKeepsManualChoiceAndBindingsFollowSources() {
        val memory = Memory(); val store = EncryptedStore(memory, secrets)
        val nas = MediaSource("nas", "NAS", SourceKind.WEBDAV, "https://nas.example.com/video/")
        store.saveSource(nas)
        val one = MediaEntry("e1", "01.mkv", "https://nas.example.com/video/Show/01.mkv", sourceId = nas.id)
        val two = MediaEntry("e2", "02.mkv", "https://nas.example.com/video/Show/02.mkv", sourceId = nas.id)
        store.saveIndex(nas.id, listOf(one, two), store.historyEpoch())
        val auto = SeriesInfo("s:show:1", "11", "作品", episodes = listOf(AnimeEpisode(110001, "第一集", "1")), method = RecognitionMethod.NAME, signature = "a")
        assertTrue(store.saveSeries(auto, mapOf("e1" to 110001L, "e2" to 110002L), emptyList(), keepManual = true))
        assertEquals("作品", store.seriesInfos()["s:show:1"]?.title)
        assertEquals("第一集", store.seriesInfos()["s:show:1"]?.episodes?.single()?.title)
        assertEquals(110002L, store.libraryBindings()["e2"])
        // 手动指定后，自动识别不能覆盖；换绑时旧的文件绑定一并替换
        assertTrue(store.saveSeries(auto.copy(animeId = "22", method = RecognitionMethod.MANUAL), mapOf("e1" to 220001L), listOf("e1", "e2"), keepManual = false))
        assertFalse(store.saveSeries(auto.copy(animeId = "33"), mapOf("e1" to 330001L), listOf("e1", "e2"), keepManual = true))
        assertEquals("22", store.seriesInfos()["s:show:1"]?.animeId)
        assertEquals(mapOf("e1" to 220001L), store.libraryBindings())
        // 手动绑定优先于识别得到的绑定；删除来源时识别绑定一并清除
        store.setLibraryHidden("s:show:1", true); assertEquals(setOf("s:show:1"), store.hiddenLibrary())
        store.setLibraryHidden("s:show:1", false); assertTrue(store.hiddenLibrary().isEmpty())
        store.deleteSource(nas.id)
        assertTrue(store.libraryBindings().isEmpty())
    }
    @Test fun failedRestorePreservesEveryStoreKeyAndSuccessfulRestoreRejectsOldWrites() {
        val memory = Memory(); val store = EncryptedStore(memory, secrets)
        store.saveSource(source); store.toggleFavorite(media)
        val before = memory.values.toMap(); val epoch = store.historyEpoch(); val snapshot = store.backupSnapshot()
        memory.fail = true
        assertThrows(java.io.IOException::class.java) { store.restore(snapshot.copy(favorites = emptyList())) }
        assertEquals(before, memory.values); assertEquals(epoch, store.historyEpoch())
        memory.fail = false; store.restore(snapshot.copy(history = emptyList()))
        store.saveProgress(PlaybackProgress(media, 2, 10), epoch)
        assertTrue(store.history().isEmpty())
        assertFalse(store.saveIndex(source.id, listOf(media), epoch))
    }
    @Test fun fileHashesAreRememberedPerFileSizeAndRemovedWithTheirSource() {
        val memory = Memory(); val store = EncryptedStore(memory, secrets)
        store.saveSource(source); store.saveIndex(source.id, listOf(media.copy(sizeBytes = 100)), store.historyEpoch())
        val hash = "0123456789abcdef0123456789abcdef"
        store.saveFileHash(media.id, 100, hash)
        assertEquals(hash, EncryptedStore(memory, secrets).fileHash(media.id, 100))
        // 文件大小变了视为换了文件
        assertNull(store.fileHash(media.id, 101))
        assertThrows(IllegalArgumentException::class.java) { store.saveFileHash(media.id, 100, "not-a-hash") }
        store.deleteSource(source.id)
        assertNull(store.fileHash(media.id, 100))
    }
    @Test fun oldSettingsWithoutEngineUseMedia3AndNewEngineRoundTrips() {
        val memory = Memory(); memory.values["settings"] = "{}"
        val store = EncryptedStore(memory, secrets)
        assertEquals(PlaybackEngine.MEDIA3, store.settings().playbackEngine)
        store.saveSettings(store.settings().copy(playbackEngine = PlaybackEngine.VLC))
        assertEquals(PlaybackEngine.VLC, EncryptedStore(memory, secrets).settings().playbackEngine)
    }
    @Test fun favoritesAreKeyedByStableMediaIdAndHistoryClearDoesNotClearFavorites() {
        val store = EncryptedStore(Memory(), secrets); store.saveSource(source)
        assertTrue(store.toggleFavorite(media)); assertFalse(store.toggleFavorite(media.copy(title = "已改名")))
        store.toggleFavorite(media); store.clearHistory(); assertEquals(listOf(media), store.favorites())
    }
}
