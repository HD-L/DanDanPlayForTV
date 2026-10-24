package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SubtitleCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val srt = "1\n00:00:00,000 --> 00:00:01,000\n中文字幕\n"

    @Test fun concurrentInstancesDownloadSameSubtitleOnlyOnce() {
        val directory = temporary.newFolder("字幕")
        val first = SubtitleCache(directory)
        val second = SubtitleCache(directory)
        val entered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val one = executor.submit<java.io.File> {
                first.getOrDownload(42, "zh-cn") {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    srt.toByteArray()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = executor.submit<java.io.File> {
                secondStarted.countDown()
                second.getOrDownload(42, "zh-cn") { calls.incrementAndGet(); srt.toByteArray() }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { two.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            val firstFile = one.get(5, TimeUnit.SECONDS)
            assertEquals(firstFile, two.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(srt, firstFile.readText())
            assertArrayEquals(srt.toByteArray(), first.rawForCanonical(firstFile.name)!!.readBytes())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun invalidBodyAndInvalidTimingPublishNeitherCanonicalNorRaw() {
        val directory = temporary.newFolder("字幕")
        val cache = SubtitleCache(directory)
        listOf("-->", "1\n00:00:99,000 --> 00:00:99,999\n无效\n").forEach { body ->
            assertThrows(IllegalArgumentException::class.java) { cache.getOrDownload(42, "zh-cn") { body.toByteArray() } }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun corruptedCacheIsDiscardedAndDownloadedAgain() {
        val directory = temporary.newFolder("字幕")
        val cache = SubtitleCache(directory)
        val original = cache.getOrDownload(42, "zh-cn") { srt.toByteArray() }
        original.writeText("-->")
        val replacement = srt.replace("中文字幕", "新的字幕")
        val calls = AtomicInteger()
        val loaded = SubtitleCache(directory).getOrDownload(42, "zh-cn") { calls.incrementAndGet(); replacement.toByteArray() }
        assertEquals(1, calls.get())
        assertEquals(replacement, loaded.readText())
        assertArrayEquals(replacement.toByteArray(), cache.rawForCanonical(loaded.name)!!.readBytes())
    }

    @Test fun chineseLegacyBytesArePreservedBesideNormalizedUtf8() {
        val cache = SubtitleCache(temporary.newFolder("字幕"))
        val text = srt.replace("中文字幕", "中文")
        val bytes = text.toByteArray(Charset.forName("GB18030"))
        val canonical = cache.getOrDownload(42, "zh-cn") { bytes }
        assertEquals(text, canonical.readText(Charsets.UTF_8))
        assertArrayEquals(bytes, cache.rawForCanonical(canonical.name)!!.readBytes())
    }

    @Test fun validAssHasCanonicalExtensionAndCanBeParsed() {
        val cache = SubtitleCache(temporary.newFolder("字幕"))
        val text = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,中文字幕\n"
        val canonical = cache.getOrDownload(42, "zh-cn") { text.toByteArray() }
        assertEquals("42-zh-cn.ass", canonical.name)
        assertEquals(text, canonical.readText())
    }

    @Test fun rawLookupRequiresExistingCanonicalAndSafeCacheName() {
        val cache = SubtitleCache(temporary.newFolder("字幕"))
        val canonical = cache.getOrDownload(42, "zh-cn") { srt.toByteArray() }
        assertNotNull(cache.rawForCanonical(canonical.name))
        listOf("../42-zh-cn.srt", "/42-zh-cn.srt", "42-zh-cn.raw", "42-zh-cn.srt/other", "43-zh-cn.srt").forEach {
            assertNull(cache.rawForCanonical(it))
        }
        canonical.delete()
        assertNull(cache.rawForCanonical(canonical.name))
    }
}
