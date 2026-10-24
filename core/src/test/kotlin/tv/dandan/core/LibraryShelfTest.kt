package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelfTest {
    private fun entry(path: String) = MediaEntry("id:$path", path.substringAfterLast('/'), "http://nas/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }, sourceId = "nas", sizeBytes = 1_000_000_000)
    private val files = listOf(
        entry("TV/[G] 甲作品/[G] 甲作品 - 01 [1080p].mkv"), entry("TV/[G] 甲作品/[G] 甲作品 - 02 [1080p].mkv"),
        entry("TV/[G] 乙作品/[G] 乙作品 - 01 [1080p].mkv"),
        entry("Movies/Some Film (2021).mkv"),
        entry("个人/家庭录像/客厅.mp4"), entry("个人/家庭录像/公园.mp4"),
    )
    private val groups = LibraryGrouping.group(files)
    private fun key(title: String) = groups.first { it.title == title }.key

    @Test fun filtersAndCounts() {
        val infos = mapOf(key("甲作品") to SeriesInfo(key("甲作品"), "1", "甲作品官方名", method = RecognitionMethod.NAME))
        val hidden = setOf(key("乙作品"))
        val counts = LibraryShelf.counts(groups, infos, hidden)
        assertEquals(3, counts[LibraryFilter.ALL]); assertEquals(1, counts[LibraryFilter.SERIES]); assertEquals(1, counts[LibraryFilter.MOVIE])
        assertEquals(1, counts[LibraryFilter.OTHER]); assertEquals(1, counts[LibraryFilter.UNRECOGNIZED]); assertEquals(1, counts[LibraryFilter.HIDDEN])
        assertEquals("甲作品官方名", LibraryShelf.title(groups.first { it.title == "甲作品" }, infos[key("甲作品")]))
        assertEquals("Some Film", LibraryShelf.title(groups.first { it.kind == LibraryKind.MOVIE }, null))
    }

    @Test fun recentPutsWatchedFirstAndSkipsPersonalVideos() {
        val second = groups.first { it.title == "乙作品" }.episodes.single().entry
        val history = mapOf(second.id to PlaybackProgress(second, 10_000, 1_400_000, 99))
        val recent = LibraryShelf.recent(groups, emptyMap(), emptySet(), history)
        assertEquals(listOf("乙作品", "Some Film", "甲作品"), recent.map { it.title })
    }

    @Test fun watchedUsesProgressOrOfficialMarksAndNextUpSkipsWatched() {
        val show = groups.first { it.title == "甲作品" }
        val (one, two) = show.episodes.map { it.entry }
        val bindings = mapOf(one.id to 10001L, two.id to 10002L)
        assertTrue(LibraryShelf.watched(one, emptyMap(), bindings, setOf(10001L)))
        assertFalse(LibraryShelf.watched(two, emptyMap(), bindings, setOf(10001L)))
        val next = LibraryGrouping.nextUp(show, emptyMap()) { LibraryShelf.watched(it, emptyMap(), bindings, setOf(10001L)) }
        assertEquals(two to false, next)
    }
}
