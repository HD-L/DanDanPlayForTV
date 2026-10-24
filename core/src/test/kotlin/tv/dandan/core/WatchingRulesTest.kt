package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class WatchingRulesTest {
    private val media = MediaEntry("a", "第一集", "content://media/1")
    @Test fun seekStaysInKnownDuration() {
        assertEquals(0L, PlaybackRules.seek(1000, -10000, 60000))
        assertEquals(60000L, PlaybackRules.seek(59000, 10000, 60000))
    }
    @Test fun unknownDurationCanSeekAndOverflowIsSafe() {
        assertEquals(90000L, PlaybackRules.seek(80000, 10000, 0))
        assertEquals(Long.MAX_VALUE, PlaybackRules.seek(Long.MAX_VALUE - 2, 10000, 0))
    }
    @Test fun completedVideoStartsAtBeginning() {
        assertEquals(0L, PlaybackRules.resume(PlaybackProgress(media, 59500, 60000)))
        assertEquals(12000L, PlaybackRules.resume(PlaybackProgress(media, 12000, 60000)))
        assertEquals(0L, PlaybackRules.resume(null))
    }
    @Test fun backClosesControlsBeforeExit() {
        assertEquals(PlaybackAction.HIDE_CONTROLS, PlaybackKeys.action(RemoteKey.BACK, true))
        assertEquals(PlaybackAction.EXIT, PlaybackKeys.action(RemoteKey.BACK, false))
    }
    @Test fun playbackKeysRespectControlFocus() {
        assertEquals(PlaybackAction.FOCUS, PlaybackKeys.action(RemoteKey.RIGHT, true))
        assertEquals(PlaybackAction.SEEK_FORWARD, PlaybackKeys.action(RemoteKey.RIGHT, false))
        assertEquals(PlaybackAction.TOGGLE, PlaybackKeys.action(RemoteKey.PLAY_PAUSE, true))
    }
    @Test fun danmakuWindowResetsOnBackwardSeek() {
        val timeline = DanmakuTimeline(listOf(DanmakuComment(1000,"一"),DanmakuComment(10000,"二")))
        val config = AppSettings(danmakuDurationMs = 4000)
        assertEquals(listOf("二"), timeline.window(11000,config).map { it.text })
        assertEquals(listOf("一"), timeline.window(2000,config).map { it.text })
    }
    @Test fun filteringInvalidPatternsNeverCrashesPlayback() {
        val t = DanmakuTimeline(listOf(DanmakuComment(0,"广告"),DanmakuComment(0,"剧透"),DanmakuComment(0,"保留")))
        val s = AppSettings(blockedKeywords=listOf("广告"),blockedPatterns=listOf("[", "剧.*"))
        assertEquals(listOf("保留"),t.window(1,s).map { it.text })
        assertNotNull(BlockRules.validate(listOf("[")))
    }
    @Test fun visibilityAndDensityAreBounded() {
        val t = DanmakuTimeline((0..100).map { DanmakuComment(it.toLong(),"评论$it") } + DanmakuComment(0,"顶",mode=DanmakuMode.TOP))
        assertEquals(5,t.window(200,AppSettings(danmakuMaxCount=5,showTop=false)).size)
        assertTrue(t.window(200,AppSettings(danmakuEnabled=false)).isEmpty())
    }
    @Test fun srtParsesMultilineAndIgnoresInvalidBlocks() {
        val parsed = SubtitleParser.parse("1\n00:00:01,250 --> 00:00:03,000\n你好\n第二行\n\n2\n00:00:05,000 --> 00:00:04,000\n无效")
        assertEquals(listOf(SubtitleCue(1250,3000,"你好\n第二行")),parsed)
    }
    @Test fun vttAcceptsCueSettingsAndBom() {
        val parsed = SubtitleParser.parse("\uFEFFWEBVTT\n\n00:01.000 --> 00:02.500 align:start\n字幕")
        assertEquals(SubtitleCue(1000,2500,"字幕"), parsed.single())
    }
    @Test fun assReadsDeclaredFormatAndRemovesOverrideTags() {
        val parsed = SubtitleParser.parse("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\an8}第一行\\N第二行,逗号")
        assertEquals(SubtitleCue(1000,2500,"第一行\n第二行,逗号"), parsed.single())
    }
    @Test fun subtitleDelayUsesMediaClockAndEndIsExclusive() {
        val cues = listOf(SubtitleCue(1000,2000,"甲"), SubtitleCue(1000,3000,"乙"))
        assertEquals("甲\n乙", SubtitleParser.at(cues,2000,500))
        assertEquals("乙",SubtitleParser.at(cues,2000,0))
        assertEquals("",SubtitleParser.at(cues,4000,0))
    }
    @Test fun malformedSubtitleTimeDoesNotHideValidCue() {
        val parsed = SubtitleParser.parse("00:00:99,000 --> 00:00:99,999\n无效\n\n00:00:01,000 --> 00:00:02,000\n有效")
        assertEquals(listOf(SubtitleCue(1000,2000,"有效")), parsed)
    }
    @Test fun subtitleIndexKeepsEarlierOverlappingCueAfterSeek() {
        val timeline = SubtitleTimeline(listOf(SubtitleCue(0,5000,"长"),SubtitleCue(1000,2000,"短"),SubtitleCue(6000,7000,"后")))
        assertEquals("长",timeline.at(3000))
        assertEquals("后",timeline.at(6500))
        assertEquals("长\n短",timeline.at(1500))
    }
    @Test fun blockingRegexUsesSafeLinearSyntax() {
        assertNotNull(BlockRules.validate(listOf("(?=广告)")))
        assertNull(BlockRules.validate(listOf("广告|剧透")))
    }
    @Test fun denseWindowPrioritizesRecentCandidatesWithinWorkBudget() {
        val comments = listOf(DanmakuComment(0, "较早评论")) + (1..6000).map { DanmakuComment(1, "广告") }
        val active = DanmakuTimeline(comments).window(2, AppSettings(danmakuMaxCount = 1, blockedKeywords = listOf("广告")))
        assertTrue("高密度窗口只考虑最近候选，屏蔽不应造成无界扫描", active.isEmpty())
    }
}
