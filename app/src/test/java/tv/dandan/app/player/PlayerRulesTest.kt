package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.DanmakuMode

class PlayerRulesTest {
    @Test fun xmlParsesTimingColorAndThreeModes() {
        val parsed = DanmakuXml.parse("<i><d p=\"1.25,1,25,16711680\">滚动 &amp; 文本</d><d p=\"2,5,25,16777215\">顶部</d><d p=\"3,4,25,255\">底部</d></i>")
        assertEquals(3, parsed.size)
        assertEquals(1250L, parsed[0].timeMs)
        assertEquals("滚动 & 文本", parsed[0].text)
        assertEquals(0xffff0000.toInt(), parsed[0].color)
        assertEquals(DanmakuMode.TOP, parsed[1].mode)
        assertEquals(DanmakuMode.BOTTOM, parsed[2].mode)
    }
    @Test fun xmlCannotReadExternalEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            DanmakuXml.parse("<!DOCTYPE i [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><i><d p=\"0,1,25,1\">&xxe;</d></i>")
        }
    }
    @Test fun xmlRejectsOversizedAndMalformedFiles() {
        assertThrows(IllegalArgumentException::class.java) { DanmakuXml.parse("x".repeat(8 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { DanmakuXml.parse("<i><d>") }
    }
    @Test fun xmlSkipsInvalidTimesUnsupportedModesAndEmptyText() {
        val parsed = DanmakuXml.parse("<i><d p=\"-2,1,25,1\">错</d><d p=\"NaN,1,25,1\">错</d><d p=\"2,7,25,1\">特效</d><d p=\"4,1,25,1\"> </d><d p=\"5,1,25,1\">可见</d></i>")
        assertEquals(listOf("可见"), parsed.map { it.text })
    }
    @Test fun unlimitedDanmakuStillHasAHardRenderingBudget() {
        assertEquals(300, DanmakuBudget.count(0))
        assertEquals(60, DanmakuBudget.count(60))
        assertEquals(300, DanmakuBudget.count(10000))
    }
    @Test fun commandsValidateParametersBeforeMutatingPlayer() {
        assertEquals(PlayerCommand.Seek(1234), PlayerCommands.parse("seek", mapOf("position" to "1234")))
        assertEquals(PlayerCommand.SeekBy(-10000), PlayerCommands.parse("seekBy", mapOf("offset" to "-10000")))
        assertEquals(PlayerCommand.Speed(1.5f), PlayerCommands.parse("speed", mapOf("speed" to "1.5")))
        assertEquals(PlayerCommand.Volume(60), PlayerCommands.parse("volume", mapOf("percent" to "60")))
        assertEquals(PlayerCommand.Play, PlayerCommands.parse("resume", emptyMap()))
    }
    @Test fun invalidCommandsDoNotReportFakeSuccess() {
        listOf(
            "seek" to mapOf("position" to "-1"),
            "seek" to mapOf("position" to "溢出"),
            "speed" to mapOf("speed" to "NaN"),
            "speed" to mapOf("speed" to "0"),
            "volume" to mapOf("percent" to "101"),
            "brightness" to mapOf("percent" to "60"),
            "unknown" to emptyMap(),
        ).forEach { (action, args) ->
            val error = assertThrows(IllegalArgumentException::class.java) { PlayerCommands.parse(action, args) }
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }
}
