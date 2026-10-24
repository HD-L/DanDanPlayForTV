package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test

class ReadWindowTest {
    @Test fun boundedRequestCanExtendPastFileWithoutBecomingTruncation() {
        assertEquals(ReadWindow(2, 5), ReadWindows.resolve(8, 5, 10))
        assertEquals(ReadWindow(0, 0), ReadWindows.resolve(10, -1, 10))
        assertThrows(PositionOutsideMediaException::class.java) { ReadWindows.resolve(11, -1, 10) }
    }

    @Test fun http416AtExactlyEofIsAValidEmptyOpen() {
        assertEquals(ReadWindow(0, 5), ReadWindows.http(416, "bytes */10", 0, 10, 5))
        assertEquals(ReadWindow(0, 0), ReadWindows.http(416, "bytes */10", 0, 10, -1))
        assertThrows(PositionOutsideMediaException::class.java) { ReadWindows.http(416, "bytes */10", 0, 11, -1) }
    }

    @Test fun contentRangeDeterminesRemainingWhileOpenReturnsRequestedLength() {
        assertEquals(ReadWindow(2, 5), ReadWindows.http(206, "bytes 8-9/10", 2, 8, 5))
        assertEquals(ReadWindow(2, 2), ReadWindows.http(206, "bytes 8-9/10", 2, 8, -1))
        assertEquals(ReadWindow(2, 5), ReadWindows.http(200, null, 2, 0, 5))
        assertThrows(java.io.IOException::class.java) { ReadWindows.http(206, "bytes 7-9/10", 3, 8, 2) }
    }
}
