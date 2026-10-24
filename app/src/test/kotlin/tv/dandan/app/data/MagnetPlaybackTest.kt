package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.TorrentFile

class MagnetPlaybackTest {
    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"

    @Test fun entryIsStableAndPassesMediaValidation() {
        val entry = MagnetPlayback.entry(hash, TorrentFile(3, "Sintel.mp4", 129_241_752))
        assertEquals("magnet:$hash:3", entry.id)
        assertTrue(MagnetPlayback.isMagnet(entry))
        SourceRules.validateMediaUri(entry.uri)
        assertEquals(entry, MagnetPlayback.entry(hash, TorrentFile(3, "Sintel.mp4", 129_241_752)))
    }

    @Test fun malformedMagnetPlayUriIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validateMediaUri("magnet-play://xyz/1") }
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validateMediaUri("magnet-play://$hash/1?x") }
    }
}
