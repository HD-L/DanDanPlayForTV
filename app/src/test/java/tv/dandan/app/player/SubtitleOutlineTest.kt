package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleOutlineTest {
    @Test fun zeroDisablesOutlineAndEveryPositiveWeightEnablesIt() {
        assertFalse(SubtitleOutline.enabled(0f))
        assertEquals(0, SubtitleOutline.vlcThickness(0f))
        assertTrue(SubtitleOutline.enabled(.1f))
        assertTrue(SubtitleOutline.vlcThickness(.1f) >= 1)
        assertEquals(4, SubtitleOutline.vlcThickness(2f))
        assertEquals(6, SubtitleOutline.vlcThickness(3f))
    }
    @Test fun invalidValuesCannotReachNativeRenderer() {
        assertFalse(SubtitleOutline.enabled(Float.NaN))
        assertFalse(SubtitleOutline.enabled(-1f))
        assertEquals(0, SubtitleOutline.vlcThickness(Float.POSITIVE_INFINITY))
        assertEquals(50, SubtitleOutline.vlcThickness(1000f))
    }
}
