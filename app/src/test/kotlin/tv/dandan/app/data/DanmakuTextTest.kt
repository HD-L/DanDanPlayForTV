package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class DanmakuTextTest {
    @Test fun conversionPreservesTimingModeColorAndOriginalList() {
        val original = listOf(DanmakuComment(42, "生命不息，奮鬥不止", 123, DanmakuMode.TOP))
        val simplified = DanmakuText.convert(original, DanmakuLanguage.SIMPLIFIED)
        assertEquals(original.single().copy(text = "生命不息，奋斗不止"), simplified.single())
        assertEquals(original, DanmakuText.convert(simplified, DanmakuLanguage.TRADITIONAL))
        assertSame(original, DanmakuText.convert(original, DanmakuLanguage.ORIGINAL))
    }
}
