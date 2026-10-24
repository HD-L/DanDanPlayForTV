package tv.dandan.core

object SubtitleHash {
    const val CHUNK_SIZE = 65536
    fun calculate(size: Long, first: ByteArray, last: ByteArray): String {
        require(size >= CHUNK_SIZE * 2L && first.size == CHUNK_SIZE && last.size == CHUNK_SIZE) { "视频太小或读取不完整，无法计算字幕指纹" }
        var sum = size
        for (chunk in listOf(first, last)) for (offset in chunk.indices step 8) {
            var word = 0L
            for (i in 0..7) word = word or ((chunk[offset + i].toLong() and 255) shl (i * 8))
            sum += word
        }
        return java.lang.Long.toUnsignedString(sum, 16).padStart(16, '0')
    }
}
