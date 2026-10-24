package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNameParserTest {
    private fun check(name: String, title: String, episode: Double?, season: Int? = null) {
        val parsed = MediaNameParser.parse(name)
        assertEquals("标题：$name", title, parsed.title)
        assertEquals("集数：$name", episode, parsed.episode)
        if (season != null) assertEquals("季：$name", season, parsed.season)
    }

    @Test fun fansubBracketStyle() {
        check("[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][01][1080P][BDRip][HEVC-10bit][FLACx2].mkv", "Re Zero kara Hajimeru Isekai Seikatsu", 1.0, 1)
        check("[Nekomoe kissaten][Sousou no Frieren][01][1080p][JPSC].mp4", "Sousou no Frieren", 1.0)
        check("【喵萌奶茶屋】★01月新番★[葬送的芙莉莲 / Sousou no Frieren][29][1080p][简日双语].mp4", "葬送的芙莉莲", 29.0)
        check("[DBD-Raws][Re Puchi Kara Hajimeru Isekai Seikatsu][01][1080P][BDRip][HEVC-10bit][FLAC].mkv", "Re Puchi Kara Hajimeru Isekai Seikatsu", 1.0)
        assertEquals(listOf("Sousou no Frieren"), MediaNameParser.parse("【喵萌奶茶屋】★01月新番★[葬送的芙莉莲 / Sousou no Frieren][29][1080p][简日双语].mp4").altTitles)
    }

    @Test fun fansubDashAndChineseEpisode() {
        check("[Kamigami] Kaze ga Tsuyoku Fuiteiru - 01 [BD 1080p x265 Ma10p AAC].mkv", "Kaze ga Tsuyoku Fuiteiru", 1.0)
        check("[Skymoon-Raws] Shingeki no Kyojin S1 - 01 [ViuTV][WEB-DL][1080p][AVC AAC].mp4", "Shingeki no Kyojin", 1.0, 1)
        check("[ANi] 葬送的芙莉蓮 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4", "葬送的芙莉蓮", 1.0)
        check("[Sub] Show - 12v2 [1080p].mkv", "Show", 12.0)
        val dmg = MediaNameParser.parse("[DMG] 東京喰種トーキョーグール √A 第01话「新洸」 [BDRip][AVC_AAC][1080P][CHT](A86494F9).mp4")
        assertEquals("東京喰種トーキョーグール √A", dmg.title)
        assertEquals(1.0, dmg.episode)
        assertEquals("新洸", dmg.episodeTitle)
        check("测试番剧 第03集.mp4", "测试番剧", 3.0)
        val sp = MediaNameParser.parse("[Group] Show SP1 [1080p].mkv")
        assertEquals("Show", sp.title); assertTrue(sp.special); assertEquals(1.0, sp.episode)
        check("[Group] SPY×FAMILY - 03 [1080p].mkv", "SPY×FAMILY", 3.0)
    }

    @Test fun sceneStyleMoviesAndSeries() {
        val spider = MediaNameParser.parse("Spider-Man.No.Way.Home.2021.1080p.BluRay.x264.AAC5.1-[YTS.MX].mp4")
        assertEquals("Spider-Man No Way Home", spider.title); assertEquals(2021, spider.year); assertNull(spider.episode); assertTrue(spider.tagged)
        check("Venom.Let.There.Be.Carnage.2021.1080p.BluRay.REMUX.AVC.DTS-HD.MA.TrueHD.7.1.Atmos-FGT.mkv", "Venom Let There Be Carnage", null)
        check("Green Snake 1993 BluRay REMUX 1080p AVC DTS-HD MA5.1 2Audio-DreamHD.mkv", "Green Snake", null)
        check("Gintama.Yoshiwara.in.Flames.2026.1080p.日语中字.mp4", "Gintama Yoshiwara in Flames", null)
        check("Show.S01E05.1080p.WEB-DL.mkv", "Show", 5.0, 1)
        check("Kimetsu no Yaiba - S01E01 - Cruelty.mkv", "Kimetsu no Yaiba", 1.0, 1)
        val blade = MediaNameParser.parse("Blade.Runner.2049.2017.1080p.BluRay.x264.mkv")
        assertEquals("Blade Runner 2049", blade.title); assertEquals(2017, blade.year)
        val bracketYear = MediaNameParser.parse("Blade Runner 2049 (2017).mkv")
        assertEquals("Blade Runner 2049", bracketYear.title); assertEquals(2017, bracketYear.year)
        val sp = MediaNameParser.parse("Sousou no Frieren 2023 S00E01-[1080p][BDRIP][x265.OPUS].mkv")
        assertEquals("Sousou no Frieren", sp.title); assertTrue(sp.special); assertEquals(1.0, sp.episode)
    }

    @Test fun extrasAreFlagged() {
        listOf(
            "[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][menu][01][1080P][BDRip][HEVC-10bit][FLAC].mkv",
            "[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][NCED1][1080P][BDRip][HEVC-10bit][FLAC].mkv",
            "[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][PV][01][1080P][BDRip][HEVC-10bit][FLAC].mkv",
            "[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][25][Commentary][1080P][BDRip][HEVC-10bit][FLAC].mkv",
            "[Kamigami] Kaze ga Tsuyoku Fuiteiru - Menu01 [BD 1080p x265 Ma10p AAC].mp4",
            "[Kamigami] Kaze ga Tsuyoku Fuiteiru - NCOP2 [BD 1080p x265 Ma10p AAC].mp4",
        ).forEach { name ->
            val parsed = MediaNameParser.parse(name)
            assertTrue("附加内容：$name", parsed.extra)
            assertTrue("附加内容标题：$name -> ${parsed.title}", parsed.title == "Re Zero kara Hajimeru Isekai Seikatsu" || parsed.title == "Kaze ga Tsuyoku Fuiteiru")
        }
        assertFalse(MediaNameParser.parse("[DBD-Raws][Re Zero kara Hajimeru Isekai Seikatsu S1][02][1080P][BDRip][HEVC-10bit][FLAC].mkv").extra)
    }

    @Test fun folderNames() {
        val re = MediaNameParser.parse("[DBD-Raws][Re：从零开始的异世界生活 第一季][01-25TV全集+SP][1080P][BDRip][HEVC-10bit][简繁外挂][FLAC][MKV]")
        assertEquals("Re：从零开始的异世界生活", re.title); assertEquals(1, re.season); assertFalse(re.special)
        assertEquals("Ansatsu Kyoushitsu", MediaNameParser.parse("[Skymoon-Raws] Ansatsu Kyoushitsu S1 - 01~22 [ViuTV][WEB-DL][1080p][AVC AAC][MP4+SRT]").title)
        assertEquals("Spider-Man No Way Home", MediaNameParser.parse("Spider-Man No Way Home (2021) [1080p] [BluRay] [5.1] [YTS.MX]").title)
        assertEquals("東京喰種トーキョーグール √A", MediaNameParser.parse("[DMG] 東京喰種トーキョーグール √A [BDRip][AVC_AAC][1080P][CHT][MP4]").title)
        assertEquals("青蛇 Green Snake", MediaNameParser.parse("【高清影视之家发布 www.HDBTHD.com】青蛇[国语配音+中文字幕].Green.Snake.1993.BluRay.REMUX.1080p.AVC.DTS-HD.MA5.1.2Audio-DreamHD").title)
        assertEquals(2, MediaNameParser.parse("[Group][某作品 第二季][03]").season)
    }

    @Test fun bareNumbersAndAds() {
        val bare = MediaNameParser.parse("01.mkv")
        assertEquals("", bare.title); assertEquals(1.0, bare.episode)
        assertEquals("", MediaNameParser.parse("【更多无水印蓝光原盘请访问 www.HDBTHD.com】【更多无水印蓝光原盘请访问 www.HDBTHD.com】.MP4").title)
    }

    @Test fun datesAreNotReleaseYears() {
        listOf("屏幕录制 2025-03-01 10-20-30.mp4", "VID_20250301_123456.mp4", "录像 2025.03.01.mp4", "录像2025年3月1日.mp4").forEach { name ->
            val parsed = MediaNameParser.parse(name)
            assertNull("年份：$name", parsed.year); assertNull("集数：$name", parsed.episode)
        }
        assertEquals("屏幕录制", MediaNameParser.parse("屏幕录制 2025-03-01 10-20-30.mp4").title)
        assertEquals("VID", MediaNameParser.parse("VID_20250301_123456.mp4").title)
    }

    @Test fun keysAndFolders() {
        assertEquals("re从零开始的异世界生活", MediaNameParser.normalize("Re：从零开始的异世界生活"))
        assertEquals(MediaNameParser.normalize("Sousou no Frieren"), MediaNameParser.normalize("sousou-no.frieren"))
        assertEquals(listOf("Download", "[Group] Title [BD]"), MediaNameParser.folderSegments("http://192.168.1.10:5005/Download/%5BGroup%5D%20Title%20%5BBD%5D/ep01.mkv"))
        assertEquals(listOf("tree", "primary:Movies", "document", "primary:Movies", "Show"), MediaNameParser.folderSegments("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FShow%2F01.mkv"))
    }
}
