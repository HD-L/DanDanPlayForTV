package tv.dandan.app.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class RemoteConfigRulesTest {
    @Test fun allVisualCloudAndLanguageFieldsApplyWithoutTouchingPlayback() {
        val old = AppSettings(playbackSpeed = 1.5f, playbackEngine = PlaybackEngine.VLC)
        val changed = RemoteConfigRules.apply(old, JSONObject("""{"danmakuStroke":3,"danmakuMaxLines":12,"danmakuMaxCount":100,"showScrolling":false,"showTop":false,"showBottom":false,"cloudDanmakuBlock":false,"danmakuLanguage":"SIMPLIFIED"}"""))
        assertEquals(3f, changed.danmakuStroke); assertFalse(changed.cloudDanmakuBlock); assertFalse(changed.showScrolling)
        assertEquals(DanmakuLanguage.SIMPLIFIED, changed.danmakuLanguage)
        assertEquals(PlaybackEngine.VLC, changed.playbackEngine); assertEquals(1.5f, changed.playbackSpeed)
    }
    @Test fun invalidTypesUnknownFieldsAndFractionalCountNeverSilentlyPass() {
        for (body in listOf("{\"showTop\":\"false\"}", "{\"danmakuMaxCount\":1.5}", "{\"danmakuLanguage\":\"wrong\"}", "{\"danmakuAlpha\":2}", "{\"unknown\":true}")) {
            assertThrows(IllegalArgumentException::class.java) { RemoteConfigRules.apply(AppSettings(), JSONObject(body)) }
        }
    }
}
