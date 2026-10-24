package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class MagnetRulesTest {
    private val hash = "08ada5a7a6183aae1e09d831df6748d566095a10"

    @Test fun keywordPadsEpisodeNumber() {
        assertEquals("葬送的芙莉莲 03", MagnetRules.keyword("葬送的芙莉莲", "3"))
        assertEquals("葬送的芙莉莲 12", MagnetRules.keyword(" 葬送的芙莉莲 ", "12"))
        assertEquals("剧场版 SP", MagnetRules.keyword("剧场版", "SP"))
        assertEquals("剧场版", MagnetRules.keyword("剧场版", ""))
    }

    @Test fun infoHashFromMagnetOrBareHash() {
        assertEquals(hash, MagnetRules.infoHash("magnet:?xt=urn:btih:${hash.uppercase()}&dn=Sintel"))
        assertEquals(hash, MagnetRules.infoHash(hash))
        assertNull(MagnetRules.infoHash("magnet:?xt=urn:btih:ABCDEFGH"))
        assertNull(MagnetRules.infoHash("https://example.com/a.torrent"))
    }

    @Test fun nodeMustBeBareHttpOrigin() {
        assertEquals("http://192.168.1.2:8080", MagnetRules.normalizeNode(" http://192.168.1.2:8080/ "))
        assertThrows(IllegalArgumentException::class.java) { MagnetRules.normalizeNode("ftp://host") }
        assertThrows(IllegalArgumentException::class.java) { MagnetRules.normalizeNode("http://host/list?x=1") }
        assertThrows(IllegalArgumentException::class.java) { MagnetRules.normalizeNode("http://user:pw@host") }
    }

    @Test fun playableKeepsVideosInNaturalOrder() {
        val files = listOf(TorrentFile(2, "EP10.mkv", 1), TorrentFile(0, "EP2.mkv", 1), TorrentFile(1, "readme.txt", 1), TorrentFile(3, "EP1.MP4", 1))
        assertEquals(listOf("EP1.MP4", "EP2.mkv", "EP10.mkv"), MagnetRules.playable(files).map { it.name })
    }

    @Test fun base32HashConvertsToHex() {
        // 动漫花园节点返回的 base32 哈希
        assertEquals("8e4e8471b226e311eb7b3b77476eec7834052dbd", MagnetRules.infoHash("magnet:?xt=urn:btih:RZHII4NSE3RRD233HN3UO3XMPA2AKLN5&dn=&tr=http%3A%2F%2Fa.example%3A8000%2Fannounce"))
        assertEquals("8e4e8471b226e311eb7b3b77476eec7834052dbd", MagnetRules.infoHash("rzhii4nse3rrd233hn3uo3xmpa2akln5"))
    }

    @Test fun canonicalMagnetKeepsTrackers() {
        val magnet = "magnet:?xt=urn:btih:RZHII4NSE3RRD233HN3UO3XMPA2AKLN5&dn=&tr=http%3A%2F%2Fa.example%3A8000%2Fannounce&tr=udp%3A%2F%2Fb.example%3A80"
        assertEquals("magnet:?xt=urn:btih:8e4e8471b226e311eb7b3b77476eec7834052dbd&tr=http%3A%2F%2Fa.example%3A8000%2Fannounce&tr=udp%3A%2F%2Fb.example%3A80", MagnetRules.canonicalMagnet(magnet))
    }
}
