package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class StoreTransactionsTest {
    private class MemoryStorage : StoreStorage {
        private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(changes: Map<String, String?>): Boolean { Thread.yield(); changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; return true }
    }
    private fun stores(): Pair<EncryptedStore, EncryptedStore> {
        val memory = MemoryStorage()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = object : StoredSecrets {
            override fun encrypt(value: String) = SecretCodec.encrypt(value, key)
            override fun decrypt(value: String) = SecretCodec.decrypt(value, key)
        }
        return EncryptedStore(memory, cipher) to EncryptedStore(memory, cipher)
    }
    @Test fun separateStoreInstancesPreserveConcurrentSourceEpisodeAndHistoryUpdates() {
        val (first, second) = stores()
        val executor = Executors.newFixedThreadPool(8)
        val tasks = (0 until 60).map { i -> executor.submit {
            val store = if (i % 2 == 0) first else second
            val source = MediaSource("s$i", "来源$i", SourceKind.HTTP, "https://example.com/$i.mp4")
            store.saveSource(source)
            store.saveEpisode("m$i", EpisodeBinding(i + 1L))
            store.saveProgress(PlaybackProgress(MediaEntry("m$i", "影片$i", source.location, sourceId = source.id), 5, 10), store.historyEpoch())
        } }
        try { tasks.forEach { it.get(15, TimeUnit.SECONDS) } } finally { executor.shutdownNow() }
        assertEquals(60, first.sources().size)
        assertEquals(60, first.history().size)
        assertEquals(60, (0 until 60).count { second.episodeFor("m$it") != null })
    }
    @Test fun clearHistoryRejectsQueuedSnapshotsFromAnotherInstance() {
        val (first, second) = stores()
        val progress = PlaybackProgress(MediaEntry("movie", "影片", "https://example.com/movie.mp4"), 5, 10)
        val captured = first.historyEpoch()
        second.clearHistory()
        first.saveProgress(progress, captured)
        assertTrue(second.history().isEmpty())
    }
    @Test fun deletingSourceRejectsAnAlreadyQueuedProgressWrite() {
        val (first, second) = stores()
        val source = MediaSource("source", "来源", SourceKind.HTTP, "https://example.com/movie.mp4")
        first.saveSource(source)
        val progress = PlaybackProgress(MediaEntry("movie", "影片", source.location, sourceId = source.id), 5, 10)
        val captured = first.historyEpoch()
        second.deleteSource(source.id)
        first.saveProgress(progress, captured)
        assertTrue(second.history().isEmpty())
    }

    @Test fun historyNeverPersistsCredentialsEmbeddedInMediaUri() {
        val (store, _) = stores()
        val progress = PlaybackProgress(MediaEntry("movie", "影片", "https://alice:secret@example.com/movie.mp4"), 5, 10)
        assertThrows(IllegalArgumentException::class.java) { store.saveProgress(progress, store.historyEpoch()) }
        assertTrue(store.history().isEmpty())
    }
}
