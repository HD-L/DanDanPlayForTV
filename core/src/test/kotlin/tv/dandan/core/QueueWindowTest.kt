package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class QueueWindowTest {
    @Test fun largeQueuesKeepCurrentItemAndOriginalSequenceInsideCountAndByteBudget() {
        val queue = (0 until 5000).map { MediaEntry("$it", "第${it}集", "https://example.com/$it.mp4") }
        val current = queue[2500].copy(subtitleUri = "https://example.com/chosen.srt")
        val window = QueueWindow.select(current, queue)
        assertTrue(window.truncated); assertTrue(window.entries.size <= 101)
        assertEquals(current, window.entries.first { it.id == current.id })
        assertEquals(window.entries.map { it.id.toInt() }.sorted(), window.entries.map { it.id.toInt() })
        assertTrue(window.entries.sumOf(QueueWindow::estimatedBytes) <= 200 * 1024)
    }
    @Test fun longNamesAndUrisUseByteBudgetAndSingleItemStillPlaysWhenAbsentFromQueue() {
        val queue = (0 until 100).map { MediaEntry("$it", "中".repeat(1000), "https://example.com/" + "a".repeat(8000) + "/$it.mp4") }
        val window = QueueWindow.select(queue[50], queue)
        assertTrue(window.truncated); assertTrue(window.entries.size < 30)
        assertTrue(window.entries.sumOf(QueueWindow::estimatedBytes) <= 200 * 1024)
        assertEquals(listOf(queue[0]), QueueWindow.select(queue[0], emptyList()).entries)
    }
}
