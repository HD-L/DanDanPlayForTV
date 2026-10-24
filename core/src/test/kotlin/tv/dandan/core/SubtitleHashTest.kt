package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleHashTest {
    @Test fun officialHashAddsSizeAndLittleEndianWordsWithUnsignedOverflow() {
        val first = ByteArray(65536); val last = ByteArray(65536)
        assertEquals("0000000000020000", SubtitleHash.calculate(131072, first, last))
        first[0] = 1; last[0] = 2
        assertEquals("0000000000020003", SubtitleHash.calculate(131072, first, last))
        first.fill(-1); last.fill(-1)
        assertEquals("000000000001c000", SubtitleHash.calculate(131072, first, last))
    }
    @Test fun unknownSmallOrTruncatedFilesCannotClaimAHash() {
        assertThrows(IllegalArgumentException::class.java) { SubtitleHash.calculate(1000, ByteArray(65536), ByteArray(65536)) }
        assertThrows(IllegalArgumentException::class.java) { SubtitleHash.calculate(131072, ByteArray(65536), ByteArray(32)) }
    }
}
