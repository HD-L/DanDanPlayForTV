package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class LibraryGroupingTest {
    private fun entry(path: String, size: Long = 1_000_000_000): MediaEntry {
        val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return MediaEntry("id:$path", path.substringAfterLast('/'), "http://nas:5005/$encoded", sourceId = "nas", sizeBytes = size)
    }

    private val reZero = "Download/[DBD-Raws][Re：从零开始的异世界生活 第一季][01-25TV全集+SP][1080P][BDRip][HEVC-10bit][简繁外挂][FLAC][MKV]"
    private val kamigami = "Download/[Kamigami] Kaze ga Tsuyoku Fuiteiru [BD 1080p x265 Ma10p AAC]"
    private val library = listOf(
        entry("$reZero/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][02][1080P][BDRip][HEVC-10bit][FLAC].mkv"),
        entry("$reZero/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][01][1080P][BDRip][HEVC-10bit][FLACx2].mkv"),
        entry("$reZero/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][03][1080P][BDRip][HEVC-10bit][FLAC].mkv"),
        entry("$reZero/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][25][Commentary][1080P][BDRip][HEVC-10bit][FLAC].mkv"),
        entry("$reZero/menu/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][menu][01][1080P][BDRip][HEVC-10bit][FLAC].mkv", 50_000_000),
        entry("$reZero/menu/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][menu][02][1080P][BDRip][HEVC-10bit][FLAC].mkv", 50_000_000),
        entry("$reZero/NCOP&NCED/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][NCOP1][1080P][BDRip][HEVC-10bit][FLAC].mkv", 80_000_000),
        entry("$reZero/NCOP&NCED/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][NCED1][1080P][BDRip][HEVC-10bit][FLAC].mkv", 80_000_000),
        entry("$reZero/PV/[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][PV][01][1080P][BDRip][HEVC-10bit][FLAC].mkv", 30_000_000),
        entry("$reZero/迷你动画/[DBD-Raws][Re Puchi Kara Hajimeru Isekai Seikatsu][01][1080P][BDRip][HEVC-10bit][FLAC].mkv", 60_000_000),
        entry("$reZero/迷你动画/[DBD-Raws][Re Puchi Kara Hajimeru Isekai Seikatsu][02][1080P][BDRip][HEVC-10bit][FLAC].mkv", 60_000_000),
        entry("$reZero/迷你动画/[DBD-Raws][Re Zero Kara Hajimeru Break Time][11][1080P][BDRip][HEVC-10bit][FLAC].mkv", 60_000_000),
        entry("$kamigami/[Kamigami] Kaze ga Tsuyoku Fuiteiru - 01 [BD 1080p x265 Ma10p AAC].mkv"),
        entry("$kamigami/[Kamigami] Kaze ga Tsuyoku Fuiteiru - 02 [BD 1080p x265 Ma10p AAC].mkv"),
        entry("$kamigami/Bonus/[Kamigami] Kaze ga Tsuyoku Fuiteiru - Menu01 [BD 1080p x265 Ma10p AAC].mp4", 40_000_000),
        entry("$kamigami/Bonus/[Kamigami] Kaze ga Tsuyoku Fuiteiru - NCOP2 [BD 1080p x265 Ma10p AAC].mp4", 40_000_000),
        entry("Download/[DMG] 東京喰種トーキョーグール √A [BDRip][AVC_AAC][1080P][CHT][MP4]/[DMG] 東京喰種トーキョーグール √A 第01话「新洸」 [BDRip][AVC_AAC][1080P][CHT](A86494F9).mp4"),
        entry("Download/[DMG] 東京喰種トーキョーグール √A [BDRip][AVC_AAC][1080P][CHT][MP4]/[DMG] 東京喰種トーキョーグール √A 第02话「舞花」 [BDRip][AVC_AAC][1080P][CHT](C8B2E109).mp4"),
        entry("Download/Gintama.Yoshiwara.in.Flames.2026.1080p.日语中字.mp4"),
        entry("Download/Spider-Man No Way Home (2021) [1080p] [BluRay] [5.1] [YTS.MX]/Spider-Man.No.Way.Home.2021.1080p.BluRay.x264.AAC5.1-[YTS.MX].mp4"),
        entry("video/【高清影视之家发布 www.HDBTHD.com】青蛇[国语配音+中文字幕].Green.Snake.1993.BluRay.REMUX.1080p.AVC.DTS-HD.MA5.1.2Audio-DreamHD/Green Snake 1993 BluRay REMUX 1080p AVC DTS-HD MA5.1 2Audio-DreamHD.mkv", 30_000_000_000),
        entry("video/【高清影视之家发布 www.HDBTHD.com】青蛇[国语配音+中文字幕].Green.Snake.1993.BluRay.REMUX.1080p.AVC.DTS-HD.MA5.1.2Audio-DreamHD/【更多无水印蓝光原盘请访问 www.HDBTHD.com】【更多无水印蓝光原盘请访问 www.HDBTHD.com】.MP4", 20_000_000),
        entry("video/【高清影视之家发布 www.HDBTHD.com】青蛇[国语配音+中文字幕].Green.Snake.1993.BluRay.REMUX.1080p.AVC.DTS-HD.MA5.1.2Audio-DreamHD/【更多无水印高清电影请访问 www.HDBTHD.com】【更多无水印高清电影请访问 www.HDBTHD.com】.MKV", 20_000_000),
        entry("个人/家庭录像/客厅.mp4", 300_000_000),
        entry("个人/家庭录像/公园.mp4", 500_000_000),
        entry("个人/家庭录像/生日.mp4", 100_000_000),
        entry("个人/手机录屏/屏幕录制 2025-03-01 10-20-30.mp4", 30_000_000),
        entry("个人/手机录屏/屏幕录制 2025-03-02 11-00-00.mp4", 40_000_000),
        entry("个人/手机录屏/屏幕录制 2025-04-11 09-15-42.mp4", 20_000_000),
        entry("个人/同名录像/旅行 2024 上午.mp4", 30_000_000),
        entry("个人/同名录像/旅行 2024 下午.mp4", 30_000_000),
        entry("个人/同名录像/旅行 2024 晚上.mp4", 30_000_000),
        entry("Movies/Inception (2010).mkv"),
        entry("Movies/Avatar (2009).mkv"),
        entry("Movies/Blade.Runner.2049.2017.1080p.BluRay.x264.mkv"),
        entry("TV/某作品/Season 2/01.mkv"),
        entry("TV/某作品/Season 2/02.mkv"),
        entry("TV/某作品/SPs/[Group][某作品][SP01].mkv", 300_000_000),
        entry("动画/另一部作品/Season 1/01.mp4"),
        entry("动画/另一部作品/Season 1/02.mp4"),
        entry("动画/另一部作品/Season 1/NCOP/NCOP.mp4", 50_000_000),
    )
    private val groups = LibraryGrouping.group(library)
    private fun group(prefix: String) = groups.firstOrNull { it.key.startsWith(prefix) } ?: error("缺少作品 $prefix：${groups.map { it.key }}")

    @Test fun animeSeriesUseDedicatedFolderTitleAndCollectExtras() {
        val re = group("s:rezerokarahajimeruisekaiseikatsu:1")
        assertEquals(LibraryKind.SERIES, re.kind)
        assertEquals("Re：从零开始的异世界生活", re.title)
        assertTrue(re.altTitles.contains("Re Zero kara Hajimeru Isekai Seikatsu"))
        assertEquals(listOf("01", "02", "03"), re.episodes.map { it.label })
        assertEquals(6, re.extras.size)
        assertEquals(listOf("Re Puchi Kara Hajimeru Isekai Seikatsu", "Re Zero Kara Hajimeru Break Time"), groups.filter { it.title.startsWith("Re Puchi") || it.title.contains("Break Time") }.map { it.title }.sorted())
        val kaze = group("s:kazegatsuyokufuiteiru:1")
        assertEquals(2, kaze.episodes.size); assertEquals(2, kaze.extras.size)
        val ghoul = groups.first { it.title.startsWith("東京喰種") }
        assertEquals(listOf("01", "02"), ghoul.episodes.map { it.label })
        assertEquals("新洸", ghoul.episodes.first().parsed.episodeTitle)
    }

    @Test fun moviesKeepYearsAndSmallAdsBecomeExtras() {
        val gintama = group("m:gintamayoshiwarainflames:2026")
        assertEquals(LibraryKind.MOVIE, gintama.kind)
        // 放在通用下载目录里的影片不能被目录名改名
        assertEquals("Gintama Yoshiwara in Flames", gintama.title)
        assertEquals(listOf("Avatar", "Blade Runner 2049", "Inception"), groups.filter { it.kind == LibraryKind.MOVIE && it.folder == "Movies" }.map { it.title }.sorted())
        val spider = group("m:spidermannowayhome:2021")
        assertEquals("Spider-Man No Way Home", spider.title)
        val snake = group("m:greensnake:1993")
        assertEquals("青蛇 Green Snake", snake.title)
        assertEquals(1, snake.episodes.size); assertEquals(2, snake.extras.size)
    }

    @Test fun personalClipsBecomeOneCollection() {
        val clips = group("c:个人/家庭录像")
        assertEquals(LibraryKind.OTHER, clips.kind)
        assertEquals("家庭录像", clips.title)
        assertEquals(3, clips.count)
        // 文件名里的日期不是影片年份；同名同年份的一批录像也不是电影
        val screen = group("c:个人/手机录屏")
        assertEquals(LibraryKind.OTHER, screen.kind); assertEquals(3, screen.count)
        assertTrue(groups.none { it.kind == LibraryKind.MOVIE && (it.title.startsWith("屏幕录制") || it.title.startsWith("旅行")) })
        assertEquals(3, group("c:个人/同名录像").count)
    }

    @Test fun seasonFoldersAndSpecials() {
        val show = group("s:某作品:2")
        assertEquals("某作品 第2季", show.displayTitle)
        assertEquals(listOf("01", "02"), show.episodes.map { it.label })
        assertEquals(1, show.specials.size)
        assertEquals(listOf("01.mkv", "02.mkv", "[Group][某作品][SP01].mkv"), show.playable.map { it.title })
    }

    @Test fun seasonFolderNeverBecomesTheTitle() {
        val show = group("s:另一部作品:1")
        assertEquals("另一部作品", show.title)
        assertEquals(listOf("01", "02"), show.episodes.map { it.label })
        assertEquals(1, show.extras.size)
    }

    @Test fun everyVideoAppearsExactlyOnce() {
        val ids = groups.flatMap { g -> g.items().map { it.entry.id } }
        assertEquals(library.size, ids.size)
        assertEquals(library.map { it.id }.toSet(), ids.toSet())
    }

    @Test fun nextUpResumesOrAdvances() {
        val re = group("s:rezerokarahajimeruisekaiseikatsu:1")
        val (first, second) = re.episodes.map { it.entry }
        assertEquals(first to false, LibraryGrouping.nextUp(re, emptyMap()))
        val half = mapOf(first.id to PlaybackProgress(first, 600_000, 1_440_000, 10))
        assertEquals(first to true, LibraryGrouping.nextUp(re, half))
        val done = mapOf(first.id to PlaybackProgress(first, 1_430_000, 1_440_000, 10))
        val next = LibraryGrouping.nextUp(re, done)
        assertNotNull(next); assertEquals(second, next!!.first)
    }
}
