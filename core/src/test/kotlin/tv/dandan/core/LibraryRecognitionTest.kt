package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRecognitionTest {
    private fun entry(path: String, size: Long = 1_000_000_000) = MediaEntry("id:$path", path.substringAfterLast('/'), "http://nas/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }, sourceId = "nas", sizeBytes = size)
    private fun series(vararg names: String, folder: String = "TV/[Group] Show") = LibraryGrouping.group(names.map { entry("$folder/$it") }).single()
    private fun official(vararg numbers: String) = numbers.mapIndexed { index, n -> AnimeEpisode(1000L + index, "标题$n", n) }

    @Test fun officialNumbers() {
        assertEquals(LibraryRecognition.OfficialNumber(12.0, false), LibraryRecognition.officialNumber("12"))
        assertEquals(LibraryRecognition.OfficialNumber(12.5, false), LibraryRecognition.officialNumber("12.5"))
        assertEquals(LibraryRecognition.OfficialNumber(1.0, true), LibraryRecognition.officialNumber("S1"))
        assertEquals(LibraryRecognition.OfficialNumber(2.0, true), LibraryRecognition.officialNumber("SP02"))
        assertNull(LibraryRecognition.officialNumber("C1"))
    }

    @Test fun episodesMapByNumberAndSpecials() {
        val group = series("[Group] Show - 01 [1080p].mkv", "[Group] Show - 02 [1080p].mkv", "[Group] Show - 03 [1080p].mkv", "[Group] Show SP1 [1080p].mkv")
        val map = LibraryRecognition.mapEpisodes(group, official("1", "2", "3", "S1", "C1"))
        assertEquals(listOf("1", "2", "3"), group.episodes.map { map[it.entry.id]?.number })
        assertEquals("S1", map[group.specials.single().entry.id]?.number)
    }

    @Test fun continuedNumberingShiftsToFirstEpisode() {
        val group = series("[Group] Show S2 - 13 [1080p].mkv", "[Group] Show S2 - 14 [1080p].mkv", "[Group] Show S2 - 15 [1080p].mkv")
        val map = LibraryRecognition.mapEpisodes(group, official("1", "2", "3", "4"))
        assertEquals(listOf("1", "2", "3"), group.episodes.map { map[it.entry.id]?.number })
        // 有精确匹配的锚点时以锚点推算：本地 13 集对应官方第 2 集
        val anchored = LibraryRecognition.mapEpisodes(group, official("1", "2", "3", "4"), group.episodes.first().entry.id to 1001L)
        assertEquals(listOf("2", "3", "4"), group.episodes.map { anchored[it.entry.id]?.number })
    }

    @Test fun moviesMapToTheOnlyEpisode() {
        val movie = LibraryGrouping.group(listOf(entry("Movies/Some Film (2021).mkv"))).single()
        assertEquals(LibraryKind.MOVIE, movie.kind)
        assertEquals(500L, LibraryRecognition.mapEpisodes(movie, listOf(AnimeEpisode(500, "剧场版", "1")))[movie.episodes.single().entry.id]?.id)
    }

    @Test fun signatureChangesWhenEpisodesAreAdded() {
        val two = series("[Group] Show - 01 [1080p].mkv", "[Group] Show - 02 [1080p].mkv")
        val reordered = LibraryGrouping.group(listOf("[Group] Show - 02 [1080p].mkv", "[Group] Show - 01 [1080p].mkv").map { entry("TV/[Group] Show/$it") }).single()
        val three = series("[Group] Show - 01 [1080p].mkv", "[Group] Show - 02 [1080p].mkv", "[Group] Show - 03 [1080p].mkv")
        assertEquals(LibraryRecognition.signature(two), LibraryRecognition.signature(reordered))
        assertNotEquals(LibraryRecognition.signature(two), LibraryRecognition.signature(three))
    }

    @Test fun titleScoreRequiresMatchingSeason() {
        val names = listOf("葬送的芙莉莲", "Sousou no Frieren")
        assertEquals(100, LibraryRecognition.titleScore(names, 1, "葬送的芙莉莲"))
        assertTrue(LibraryRecognition.titleScore(names, 1, "葬送的芙莉莲 第二季") < 60)
        assertEquals(100, LibraryRecognition.titleScore(names, 2, "葬送的芙莉莲 第二季"))
        assertTrue(LibraryRecognition.titleScore(names, 2, "葬送的芙莉莲") < 60)
        assertEquals(0, LibraryRecognition.titleScore(names, 1, "完全无关的作品"))
        val group = series("[Group] 葬送的芙莉莲 - 01 [1080p].mkv", folder = "TV/葬送的芙莉莲")
        val picked = LibraryRecognition.pick(group, listOf(AnimeSummary("1", "葬送的芙莉莲 第二季"), AnimeSummary("2", "葬送的芙莉莲"), AnimeSummary("3", "芙莉莲 特别篇")))
        assertEquals("2", picked?.id)
        assertNull(LibraryRecognition.pick(group, listOf(AnimeSummary("9", "另一部作品"))))
    }
}
