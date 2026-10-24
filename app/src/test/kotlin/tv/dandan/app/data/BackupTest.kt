package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class BackupTest {
    private val media = MediaEntry("media", "影片", "https://example.com/movie.mp4", sourceId = "source")
    private val snapshot = BackupSnapshot(listOf(MediaSource("source", "来源", SourceKind.HTTP, media.uri, "alice", "private-password")), AppSettings(), listOf(PlaybackProgress(media, 5, 10, 100)), listOf(media), listOf(media), mapOf(media.id to EpisodeBinding(42)), mapOf(media.id to "source"), ApiCredentials("app", "api-secret"))
    @Test fun defaultExportOmitsCredentialsAndRoundTripsUserData() {
        val text = BackupCodec.export(snapshot)
        assertFalse(text.contains("private-password")); assertFalse(text.contains("api-secret")); assertFalse(text.contains("alice"))
        val restored = BackupCodec.import(text)
        assertEquals("", restored.sources.single().password)
        assertEquals(42L, restored.episodes.getValue(media.id).episodeId)
        assertEquals(media, restored.favorites.single())
    }
    @Test fun portableEncryptedBackupRestoresOnAnotherDeviceAndRejectsWrongPasswordOrTampering() {
        val text = BackupCodec.export(snapshot, "跨设备密码12345")
        assertFalse(text.contains("private-password"))
        assertEquals(snapshot.sources, BackupCodec.import(text, "跨设备密码12345").sources)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(text, "错误密码12345678") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(text.replace("AES-256-GCM", "AES-128-GCM"), "跨设备密码12345") }
    }
    @Test fun inconsistentMediaIdentityAndKnownEpisodeOwnershipAreRejected() {
        val wrongUri = snapshot.copy(favorites = listOf(media.copy(uri = media.uri + "/other")))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.export(wrongUri) }
        val second = MediaSource("another", "另一来源", SourceKind.HTTP, "https://example.com/other.mp4")
        val wrongOwner = snapshot.copy(sources = snapshot.sources + second, episodeSources = mapOf(media.id to second.id))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.export(wrongOwner) }
    }
    @Test fun publicExportRejectsNamedAccessSecretsInUrlsAndAllowsPublicQueryParameters() {
        val tokenUri = media.uri + "?access_token=should-never-export"
        val privateMedia = media.copy(uri = tokenUri)
        val privateData = snapshot.copy(sources = listOf(snapshot.sources.single().copy(location = tokenUri)), history = listOf(snapshot.history.single().copy(media = privateMedia)), favorites = listOf(privateMedia), index = listOf(privateMedia))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.export(privateData) }
        assertFalse(BackupCodec.export(privateData, "跨设备密码12345").contains("should-never-export"))
        val publicUri = media.uri + "?quality=720"
        val publicMedia = media.copy(uri = publicUri)
        val publicData = snapshot.copy(sources = listOf(snapshot.sources.single().copy(location = publicUri)), history = listOf(snapshot.history.single().copy(media = publicMedia)), favorites = listOf(publicMedia), index = listOf(publicMedia))
        assertTrue(BackupCodec.export(publicData).contains("quality=720"))
    }
    @Test fun firstVersionBackupWithoutNewDisplayFieldsUsesDefaults() {
        val public = org.json.JSONObject(BackupCodec.export(snapshot))
        val settings = public.getJSONObject("data").getJSONObject("settings")
        listOf("subtitleStroke", "cloudDanmakuBlock", "danmakuLanguage").forEach(settings::remove)
        val restored = BackupCodec.import(public.toString()).settings
        assertEquals(2f, restored.subtitleStroke)
        assertTrue(restored.cloudDanmakuBlock)
        assertEquals(DanmakuLanguage.ORIGINAL, restored.danmakuLanguage)
    }
    @Test fun encryptedMetadataTamperingAndSettingsOverflowAreRejected() {
        val public = org.json.JSONObject(BackupCodec.export(snapshot))
        public.getJSONObject("data").getJSONObject("settings").put("subtitleColor", 4294967295L)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(public.toString()) }
        val encrypted = org.json.JSONObject(BackupCodec.export(snapshot, "跨设备密码12345"))
        encrypted.put("createdAt", encrypted.getLong("createdAt") + 1)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(encrypted.toString(), "跨设备密码12345") }
    }
    @Test fun unknownVersionUnknownFieldsAndBadReferencesAreRejectedBeforeRestore() {
        val text = BackupCodec.export(snapshot)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(text.replace("\"version\":1", "\"version\":99")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(text.replace("\"createdAt\":", "\"unknown\":0,\"createdAt\":")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.import(text.replace("https://example.com/movie.mp4", "https://alice:secret@example.com/movie.mp4")) }
    }
}
