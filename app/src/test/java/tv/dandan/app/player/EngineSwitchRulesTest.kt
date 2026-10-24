package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test

class EngineSwitchRulesTest {
    @Test fun replaceReleasesOldDecoderBeforeConstructingNewDecoder() {
        val events = mutableListOf<String>()
        val owner = DecoderOwner<FakeDecoder>()
        owner.replace { events += "建立一"; FakeDecoder { events += "释放一" } }
        owner.replace { events += "建立二"; FakeDecoder { events += "释放二" } }
        assertEquals(listOf("建立一", "释放一", "建立二"), events)
        owner.close(); owner.close()
        assertEquals(listOf("建立一", "释放一", "建立二", "释放二"), events)
    }

    @Test fun failedReplacementCannotKeepAnOldDecoderAlive() {
        var closed = false
        val owner = DecoderOwner<FakeDecoder>()
        owner.replace { FakeDecoder { closed = true } }
        assertThrows(IllegalStateException::class.java) { owner.replace { error("初始化失败") } }
        assertTrue(closed)
        assertNull(owner.current)
    }

    @Test fun switchingRetainsPositionSpeedVolumeAndSubtitleChoice() {
        val state = DecoderRestore(123_456, true, 1.5f, 37, textDisabled = true, audioOrdinal = 2, textOrdinal = 1)
        assertEquals(state, state.forForeground(true))
        assertEquals(state.copy(play = false), state.forForeground(false))
        assertTrue(state.forForeground(false).textDisabled)
    }

    @Test fun restorationValidatesUntrustedValuesAndNeverResumesInBackground() {
        val state = DecoderRestore(-3, true, Float.NaN, 500)
        val safe = state.forForeground(false)
        assertEquals(0L, safe.position)
        assertEquals(1f, safe.speed)
        assertEquals(100, safe.volumePercent)
        assertFalse(safe.play)
    }

    @Test fun failureSnapshotRetainsKnownDurationAndLimitsPosition() {
        val state = DecoderRestore(position = 45_000, play = false, speed = 1.5f, volumePercent = 37, duration = 60_000)
        assertEquals(45_000L, state.forForeground(true).position)
        assertEquals(60_000L, state.forForeground(true).duration)
        assertEquals(37, state.forForeground(true).volumePercent)
        assertEquals(60_000L, state.copy(position = 90_000).forForeground(true).position)
        assertEquals(0L, state.copy(duration = -1).forForeground(true).duration)
    }

    private class FakeDecoder(private val onClose: () -> Unit) : java.io.Closeable {
        override fun close() = onClose()
    }
}
