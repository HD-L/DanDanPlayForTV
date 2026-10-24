package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*
import java.io.ByteArrayInputStream

class DataRulesTest {
    private val source = MediaSource("dav", "家庭影院", SourceKind.WEBDAV, "https://nas.example/video/", "alice", "secret")

    @Test fun embeddedCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceRules.validate(source.copy(location = "https://alice:secret@nas.example/video/"))
        }
    }

    @Test fun ftpCommandsCannotReceiveDecodedControlCharacters() {
        val ftp = MediaSource("ftp", "影院", SourceKind.FTP, "ftp://nas.example/video/")
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(ftp, "ftp://nas.example/video/movie%0D%0ADELE%20file.mkv") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validate(ftp.copy(username = "alice\r\nDELE file")) }
    }

    @Test fun directoryCannotEscapeConfiguredRoot() {
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(source, "https://nas.example/video/../private/") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(source, "https://other.example/video/") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(source, "https://nas.example/video-copy/") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(source, "https://nas.example/video/%2e%2e/private/") }
    }

    @Test fun childNamesRemainOneEncodedPathSegment() {
        val uri = SourceRules.childUri("https://nas.example/video/", "第 1 集 + #?.mkv", false)
        assertEquals("https://nas.example/video/%E7%AC%AC%201%20%E9%9B%86%20+%20%23%3F.mkv", uri)
        SourceRules.requireWithin(source, uri)
        assertThrows(IllegalArgumentException::class.java) { SourceRules.childUri(source.location, "../evil", false) }
    }

    @Test fun signatureUsesDigestBytesAndExcludesQuery() {
        assertEquals("+zIrTiFIrMP4ChXEox5y+T6WJSNPlRYCFZxFXRQc53I=", ApiSigning.signature(ApiCredentials("test-app", "test-secret"), 1735660800L, "/api/v2/comment/123450001?withRelated=true"))
    }

    @Test fun safRootAndOpaqueIdsUseTreeIdentityInsteadOfFilesystemPrefixes() {
        val disk = MediaSource("local", "本地", SourceKind.DOCUMENT_TREE, "content://provider/tree/primary%3A")
        SourceRules.requireWithin(disk, "content://provider/tree/primary%3A/document/primary%3AMovies")
        val opaque = disk.copy(location = "content://provider/tree/root-42")
        SourceRules.requireWithin(opaque, "content://provider/tree/root-42/document/opaque-child-99")
        assertThrows(IllegalArgumentException::class.java) { SourceRules.requireWithin(opaque, "content://provider/tree/another-root/document/opaque-child-99") }
    }

    @Test fun unknownLengthBodyStillHasAnEnforcedLimit() {
        assertThrows(IllegalArgumentException::class.java) { readLimited(ByteArrayInputStream(ByteArray(9)), 8) }
        assertArrayEquals(byteArrayOf(1, 2, 3), readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 8))
    }

    @Test fun historyKeepsLatestHundredWithoutDuplicates() {
        val input = (0..105).map { PlaybackProgress(MediaEntry("m$it", "影片$it", "https://example.com/$it.mp4"), 1, 10, it.toLong()) }
        val output = HistoryRules.merge(input, input.first().copy(positionMs = 8, updatedAtMs = 999))
        assertEquals(100, output.size)
        assertEquals("m0", output.first().media.id)
        assertEquals(8, output.first().positionMs)
        assertEquals(100, output.map { it.media.id }.distinct().size)
    }

    @Test fun httpSourceDisplayNameDoesNotHideItsPlayableEntry() {
        val source = MediaSource("http", "测试来源", SourceKind.HTTP, "https://example.com/movie.mp4")
        val entry = MediaEntry("movie", source.name, source.location, sourceId = source.id)
        assertEquals(listOf(entry), entriesForSource(source, listOf(entry)))
    }

    @Test fun attachmentCredentialCandidateMustMatchTargetBeforeBeingSelected() {
        val other = source.copy(id = "other", location = "https://other.example/subtitles/")
        assertNull(sourceForUri(listOf(source, other), "content://provider/document/user-selected-subtitle", source.id))
        assertEquals("other", sourceForUri(listOf(source, other), "https://other.example/subtitles/a.srt", source.id)?.id)
        assertNull(sourceForUri(listOf(source), "https://public.example/a.srt", source.id))
        assertNull(sourceForUri(listOf(source), "ftp://other.example/a.srt", source.id))
    }

    @Test fun mediaUriAndHistoryRejectInlineCredentialsAndUnsupportedProtocols() {
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validateMediaUri("https://alice:secret@example.com/a.mp4") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validateMediaUri("javascript:alert(1)") }
        SourceRules.validateMediaUri("content://provider/document/opaque")
    }

    @Test fun settingsUseTheSameRe2AndBudgetContractAsCore() {
        SettingsRules.validate(AppSettings(danmakuMaxCount = 0, danmakuDurationMs = 30000, blockedPatterns = listOf("a".repeat(256)), subtitleDelayMs = 3600000, playbackSpeed = 3f))
        assertThrows(IllegalArgumentException::class.java) { SettingsRules.validate(AppSettings(blockedPatterns = listOf("(?=a)"))) }
        assertThrows(IllegalArgumentException::class.java) { SettingsRules.validate(AppSettings(subtitleDelayMs = 3600001)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsRules.validate(AppSettings(danmakuDurationMs = 30001)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsRules.validate(AppSettings(playbackSpeed = 3.1f)) }
    }
}
