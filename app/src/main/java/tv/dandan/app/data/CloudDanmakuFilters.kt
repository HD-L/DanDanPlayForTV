package tv.dandan.app.data

import android.content.Context
import android.util.AtomicFile
import com.google.re2j.Pattern
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** 来源为公开维护仓库，更新方式是主动拉取，不代表官方服务器推送。 */
data class CloudFilterSnapshot(val count: Int, val skipped: Int, val updatedAt: Long)
internal data class ParsedCloudRules(val rules: List<String>, val skipped: Int)
internal object CloudRuleParser {
    const val MAX_RULES = 2000
    const val MAX_BYTES = 512 * 1024
    fun parse(texts: List<String>): ParsedCloudRules {
        require(texts.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_BYTES) { "云屏蔽规则超过 512 KiB" }
        val rules = linkedSetOf<String>(); var skipped = 0; var rawCount = 0
        texts.forEach { text ->
            text.removePrefix("\uFEFF").lineSequence().forEach { line ->
                val rule = line.trim()
                if (rule.isNotEmpty() && !rule.startsWith('#')) {
                    require(++rawCount <= MAX_RULES) { "云屏蔽规则超过 2000 条" }
                    if (rule.length > 256 || rule.any { it.isISOControl() } || runCatching { Pattern.compile(rule) }.isFailure) skipped++
                    else rules += rule
                }
            }
        }
        require(rules.isNotEmpty()) { "云屏蔽规则为空或全部不兼容，保留当前缓存" }
        return ParsedCloudRules(rules.toList(), skipped)
    }
}
internal interface CloudFilterStorage {
    fun read(): String?
    /** 返回失败时不得发布新的缓存内容。 */
    fun write(text: String): Boolean
}
private class AndroidCloudFilterStorage(context: Context) : CloudFilterStorage {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "cloud-block-v1.json"))
    override fun read(): String? = synchronized(CACHE_LOCK) { runCatching { file.openRead().use { readLimited(it, 1024 * 1024) }.toString(Charsets.UTF_8) }.getOrNull() }
    override fun write(text: String): Boolean = synchronized(CACHE_LOCK) {
        try { AtomicDiskFile.replace(file.baseFile, text.toByteArray(Charsets.UTF_8)); true }
        catch (_: Exception) { false }
    }

    companion object { private val CACHE_LOCK = Any() }
}

class CloudDanmakuFilters internal constructor(
    private val storage: CloudFilterStorage,
    private val baseline: () -> String,
    private val fetch: (String, Long, Int) -> ByteArray,
    private val nowMs: () -> Long,
    private val wallTime: () -> Long,
) {
    constructor(context: Context) : this(AndroidCloudFilterStorage(context), { context.applicationContext.assets.open("cloud-block.txt").use { readLimited(it, CloudRuleParser.MAX_BYTES) }.toString(Charsets.UTF_8) }, CloudRuleHttp::fetch, { System.nanoTime() / 1_000_000 }, System::currentTimeMillis)
    private data class Loaded(val rules: List<String>, val snapshot: CloudFilterSnapshot)
    fun rules(): List<String> = load().rules
    fun status(): CloudFilterSnapshot = load().snapshot
    fun refresh(): CloudFilterSnapshot {
        if (!REFRESH_LOCK.tryLock()) throw IOException("云屏蔽规则正在刷新，请稍后查看状态")
        try {
            val deadline = nowMs() + 20_000
            var total = 0
            val texts = CATEGORIES.map { category ->
                val remaining = deadline - nowMs()
                if (remaining <= 0 || Thread.currentThread().isInterrupted) throw IOException("云屏蔽刷新超过 20 秒或已取消，现有缓存保留")
                val bytes = fetch(category, remaining, CloudRuleParser.MAX_BYTES - total)
                total += bytes.size
                if (total > CloudRuleParser.MAX_BYTES || nowMs() >= deadline) throw IOException("云屏蔽刷新超出总预算，现有缓存保留")
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
            val parsed = CloudRuleParser.parse(texts)
            if (nowMs() >= deadline || Thread.currentThread().isInterrupted) throw IOException("云屏蔽刷新超过 20 秒或已取消，现有缓存保留")
            val snapshot = CloudFilterSnapshot(parsed.rules.size, parsed.skipped, wallTime())
            val json = JSONObject().put("version", 1).put("source", REPOSITORY).put("updatedAt", snapshot.updatedAt).put("skipped", snapshot.skipped).put("rules", JSONArray(parsed.rules)).toString()
            if (!storage.write(json)) throw IOException("无法保存云屏蔽规则，现有缓存保留")
            return snapshot
        } catch (e: IOException) { throw e } catch (_: Exception) { throw IOException("云屏蔽规则刷新失败，现有缓存保留；请检查网络或稍后重试") }
        finally { REFRESH_LOCK.unlock() }
    }
    private fun load(): Loaded {
        val cached = runCatching {
            val text = storage.read() ?: return@runCatching null
            val j = JSONObject(text)
            LibraryJson.fields(j, setOf("version", "source", "updatedAt", "skipped", "rules"))
            require(LibraryJson.integer(j, "version") == 1L && LibraryJson.string(j, "source") == REPOSITORY)
            val updated = LibraryJson.integer(j, "updatedAt"); val skipped = LibraryJson.integer(j, "skipped")
            require(updated >= 0 && skipped in 0..CloudRuleParser.MAX_RULES.toLong())
            val rules = LibraryJson.strings(j.getJSONArray("rules"))
            require(rules.size in 1..CloudRuleParser.MAX_RULES && rules.distinct().size == rules.size)
            require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
            rules.forEach { require(it.isNotBlank() && it.length <= 256 && !it.any { ch -> ch.isISOControl() }); Pattern.compile(it) }
            Loaded(rules, CloudFilterSnapshot(rules.size, skipped.toInt(), updated))
        }.getOrNull()
        if (cached != null) return cached
        val parsed = CloudRuleParser.parse(listOf(baseline()))
        return Loaded(parsed.rules, CloudFilterSnapshot(parsed.rules.size, parsed.skipped, 0))
    }
    companion object {
        const val REPOSITORY = "https://github.com/Cathgao/dandanplay-filter"
        const val BASELINE_COMMIT = "0a661e08661b39912242b411adac1f698fc69a00"
        internal val CATEGORIES = listOf("不友善-简体.txt", "不友善-繁体.txt", "地名-简体.txt", "地名-繁体.txt", "基本-简体.txt", "基本-繁体.txt", "笑点太高-简体.txt", "笑点太高-繁体.txt", "视频无关1-简体.txt", "视频无关1-繁体.txt", "视频无关2-简体.txt", "视频无关2-繁体.txt")
        private val REFRESH_LOCK = ReentrantLock()
    }
}
private object CloudRuleHttp {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun fetch(category: String, remainingMs: Long, budget: Int): ByteArray {
        require(category in CloudDanmakuFilters.CATEGORIES && remainingMs > 0 && budget > 0)
        val path = URLEncoder.encode(category, "UTF-8").replace("+", "%20")
        val url = "https://raw.githubusercontent.com/Cathgao/dandanplay-filter/master/$path"
        val limited = client.newBuilder().callTimeout(remainingMs, TimeUnit.MILLISECONDS).build()
        limited.newCall(Request.Builder().url(url).header("Accept", "text/plain").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("公开规则仓库返回 HTTP ${response.code}，现有缓存保留")
            return readLimited(response.body?.byteStream() ?: throw IOException("公开规则响应为空"), budget)
        }
    }
}
