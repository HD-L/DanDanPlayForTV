package tv.dandan.app.data

import tv.dandan.core.*
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 片库后台识别：为每部作品取一个样本文件，读取前 16MB 计算特征，向弹弹play 匹配出作品，
 * 再取得海报、简介与官方剧集表，把本地文件按集号绑定到官方剧集，播放时直接加载弹幕。
 *
 * 接口按应用分配调用额度，所以尽量少发请求：样本文件每 32 个合并成一次批量精确匹配；
 * 没有精确结果的作品才单独做一次模糊匹配，再不行按名称搜索一次；同一轮里作品资料只取一次。
 * 只处理剧集和电影，个人视频不会发给服务端；读不到文件时不伪造特征，直接按名称搜索。
 * 结果保存在本机；已识别的作品只在文件增减时刷新剧集绑定，没能识别的作品在文件变化或规则升级后才重试。
 */
class LibraryRecognizer internal constructor(
    private val store: EncryptedStore,
    private val api: DanDanApi,
    private val groups: () -> List<LibraryGroup>,
    private val fileHash: (MediaEntry) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pauseMs: Long = 300,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    data class State(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "", val message: String = "")

    @Volatile var state = State(); private set
    private val changes = AtomicLong()
    /** 每保存一部作品的结果加一，界面据此刷新。 */
    val version: Long get() = changes.get()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "片库识别").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    /** 接口额度用尽后暂停自动识别，避免每次打开片库都重复请求；用户手动重试不受限制。 */
    @Volatile private var pausedUntil = 0L

    /** 开始后台识别；正在识别时只记下需要再检查一轮（例如扫描又加入了新作品）。[force] 为用户手动重试。 */
    fun start(force: Boolean = false) {
        if (force) pausedUntil = 0 else if (nowMs() < pausedUntil) return
        if (!busy.compareAndSet(false, true)) { again.set(true); return }
        executor.execute {
            try {
                do { again.set(false); runPending() } while (again.get())
            } catch (e: Exception) {
                log("识别出错", e)
                state = State(message = "识别中断：${e.message ?: "未知错误"}")
            } finally {
                busy.set(false)
                if (state.running) state = state.copy(running = false, current = "")
                if (again.getAndSet(false)) start()
            }
        }
    }

    /** 本轮识别中断（断网、认证失败、额度用尽）：保留已有结果，下次再继续。 */
    private class Stop(message: String) : Exception(message)

    /** 一轮识别里的作品资料缓存：多部作品（续季、合集）对应同一作品时只请求一次。 */
    private inner class Session {
        private val details = HashMap<String, AnimeDetail>()
        fun detail(id: String): AnimeDetail = details.getOrPut(id) { api.detail(id) }
    }

    private class Sample(val entry: MediaEntry, val name: String, val hash: String?)
    private class Found(val animeId: String, val method: RecognitionMethod, val anchor: Pair<String, Long>?)

    /** 识别所有尚无结果或文件有变化的作品（在调用线程执行）。 */
    internal fun runPending() {
        if (!store.credentials().configured) { state = State(message = "配置弹弹play 开发者凭证后，可自动识别海报和剧集"); return }
        val hidden = store.hiddenLibrary()
        val infos = store.seriesInfos()
        val todo = groups().filter { it.kind != LibraryKind.OTHER && it.key !in hidden && needs(it, infos[it.key]) }
        if (todo.isEmpty()) { if (state.running) state = State(); return }
        state = State(running = true, total = todo.size)
        val session = Session()
        var done = 0
        fun next(group: LibraryGroup) { state = state.copy(done = done, current = group.displayTitle); done++ }
        try {
            for (chunk in todo.chunked(BATCH_SIZE)) {
                val fresh = mutableListOf<LibraryGroup>()
                for (group in chunk) {
                    val existing = store.seriesInfos()[group.key]
                    if (existing != null && existing.recognized) {
                        // 已识别的作品只是文件有增减：刷新官方剧集表并重新绑定
                        next(group)
                        guarded(group) { bind(group, existing.animeId, existing.method, null, true, session) }
                    } else fresh += group
                }
                if (fresh.isEmpty()) continue
                // 读取样本文件开头（网络来源每个约 16MB），期间在界面上显示进度
                val samples = fresh.associateWith { group -> state = state.copy(current = "读取文件特征 · ${group.displayTitle}"); sampleOf(group) }
                val exact = guardedBatch(samples.values.filterNotNull())
                for (group in fresh) {
                    next(group)
                    val sample = samples[group]
                    guarded(group) {
                        val hit = sample?.hash?.let(exact::get)
                        val found = if (hit != null) Found(hit.animeId.toString(), RecognitionMethod.HASH, sample.entry.id to hit.episodeId)
                            else fuzzy(group, sample?.let { single(it) }.orEmpty(), session)
                        if (found == null) none(group, true) else bind(group, found.animeId, found.method, found.anchor, true, session)
                    }
                    if (pauseMs > 0) Thread.sleep(pauseMs)
                }
            }
            state = State(done = todo.size, total = todo.size)
        } catch (stop: Stop) {
            state = State(done = done, total = todo.size, message = stop.message.orEmpty())
        }
    }

    /** 执行一部作品的识别：服务端拒绝这部作品的请求时记为未识别；断网、认证失败、额度用尽时停止本轮。 */
    private fun guarded(group: LibraryGroup, work: () -> Unit) {
        try {
            work()
        } catch (e: QuotaExceeded) {
            log("接口额度用尽：${group.key}", e)
            pausedUntil = nowMs() + QUOTA_PAUSE_MS
            throw Stop("识别暂停：弹弹play 接口调用已达到配额上限，稍后打开媒体库会自动继续")
        } catch (e: ServiceRejected) {
            log("服务拒绝识别请求：${group.key}", e)
            runCatching { none(group, keepManual = true) }
        } catch (e: IOException) {
            log("识别中断：${group.key}", e)
            throw Stop("识别中断：${e.message ?: "网络或服务不可用"}")
        } catch (e: IllegalArgumentException) {
            log("作品数据无效，跳过：${group.key}", e)
        }
    }

    /** 批量精确匹配（每次最多 32 个）；批量请求被拒绝时退回逐个匹配。返回 文件特征 → 精确结果。 */
    private fun guardedBatch(samples: List<Sample>): Map<String, EpisodeMatch> {
        val unique = samples.filter { it.hash != null }.distinctBy { it.hash!!.lowercase() }
        if (unique.isEmpty()) return emptyMap()
        val results = try {
            api.matchBatch(unique.map { DanDanApi.BatchFile(it.name, it.entry.sizeBytes, it.hash!!) })
        } catch (e: QuotaExceeded) {
            pausedUntil = nowMs() + QUOTA_PAUSE_MS
            throw Stop("识别暂停：弹弹play 接口调用已达到配额上限，稍后打开媒体库会自动继续")
        } catch (e: ServiceRejected) {
            log("批量匹配被拒绝，改为逐个匹配", e); return emptyMap()
        } catch (e: IOException) {
            throw Stop("识别中断：${e.message ?: "网络或服务不可用"}")
        }
        return unique.zip(results).mapNotNull { (sample, match) -> match?.takeIf { it.animeId > 0 }?.let { sample.hash!! to it } }.toMap()
    }

    /** 正在播放时不读取文件特征（每个样本约 16MB），等播放页退出再继续，避免和播放抢局域网带宽。 */
    private fun waitForPlayback() {
        if (!PlaybackGate.active) return
        val current = state.current
        state = state.copy(current = "播放中，稍后继续 · $current")
        while (PlaybackGate.active) Thread.sleep(1000)
        state = state.copy(current = current)
    }

    private fun needs(group: LibraryGroup, info: SeriesInfo?): Boolean = info == null || info.signature != LibraryRecognition.signature(group) ||
        (!info.recognized && info.version < LibraryRecognition.VERSION)

    /** 匹配用的样本文件与文件名；文件名只有集号（“01.mkv”）时用目录得到的作品名补全。 */
    private fun sample(group: LibraryGroup): Pair<MediaEntry, String>? {
        val item = (group.episodes + group.specials).firstOrNull() ?: return null
        val name = if (item.parsed.title.isBlank() && item.label.isNotEmpty()) "${group.title}${if (group.season > 1) " S${group.season}" else ""} - ${item.label}" else item.entry.title
        return item.entry to name
    }

    /** 服务端的文件匹配要求真实的文件特征（前 16MB 的 MD5）；读不到文件时不伪造，只按名称搜索。 */
    private fun sampleOf(group: LibraryGroup): Sample? {
        val (entry, name) = sample(group) ?: return null
        waitForPlayback()
        val hash = try { fileHash(entry) } catch (e: Exception) { log("无法读取文件特征：${entry.title}", e); null }
        return Sample(entry, name, hash)
    }

    /** 单个文件的匹配：精确关联时只有一个结果，否则是按文件名模糊匹配的候选。 */
    private fun single(sample: Sample): List<EpisodeMatch> = sample.hash?.let { api.matchByName(sample.name, sample.entry.sizeBytes, it) }.orEmpty()

    /** 没有精确结果时：先从模糊匹配的候选里挑，再按名称搜索一次。 */
    private fun fuzzy(group: LibraryGroup, matches: List<EpisodeMatch>, session: Session): Found? {
        matches.singleOrNull { it.exact && it.animeId > 0 }?.let { return Found(it.animeId.toString(), RecognitionMethod.HASH, null) }
        val candidates = matches.filter { it.animeId > 0 }.distinctBy { it.animeId }.map { AnimeSummary(it.animeId.toString(), it.animeTitle, it.imageUrl) }
        choose(group, candidates, session)?.let { return Found(it.id, RecognitionMethod.SEARCH, null) }
        val keyword = group.searchKeywords.firstOrNull() ?: return null
        val results = try { api.search(keyword) } catch (_: IllegalArgumentException) { return null }
        return choose(group, results, session)?.let { Found(it.id, RecognitionMethod.SEARCH, null) }
    }

    /**
     * 从候选里挑出名称足够接近的作品：先比官方名；本地名与官方名文字不同（罗马音、英文与中文）时，
     * 再取前两个候选的其他标题比对。都不够接近时返回 null，宁可不识别也不认错。
     */
    private fun choose(group: LibraryGroup, candidates: List<AnimeSummary>, session: Session): AnimeSummary? {
        if (candidates.isEmpty()) return null
        LibraryRecognition.pick(group, candidates)?.let { return it }
        // 其他标题主要解决“罗马音 / 英文文件名 ↔ 中文作品名”；本地名本身是中日文时不再额外请求
        if (group.searchKeywords.none { name -> name.none { Character.UnicodeScript.of(it.code) in cjkScripts } }) return null
        var best: AnimeSummary? = null
        var bestScore = 59
        for (candidate in candidates.take(ALIAS_CHECKS)) {
            // 单个候选查不到资料时跳过它；断网、超时、额度用尽仍然向上抛出
            val detail = try { session.detail(candidate.id) } catch (_: IllegalArgumentException) { continue } catch (_: ServiceRejected) { continue }
            val score = (detail.titles + detail.anime.title + candidate.title).maxOf { LibraryRecognition.titleScore(group.searchKeywords, group.season, it) }
            if (score > bestScore) { best = candidate; bestScore = score }
        }
        return best
    }

    private fun none(group: LibraryGroup, keepManual: Boolean): SeriesInfo {
        val info = SeriesInfo(group.key, method = RecognitionMethod.NONE, signature = LibraryRecognition.signature(group), updatedAtMs = nowMs())
        if (store.saveSeries(info, emptyMap(), group.items().map { it.entry.id }, keepManual)) changes.incrementAndGet()
        return info
    }

    private fun bind(group: LibraryGroup, animeId: String, method: RecognitionMethod, anchor: Pair<String, Long>?, keepManual: Boolean, session: Session): SeriesInfo {
        val detail = session.detail(animeId)
        val mapped = LibraryRecognition.mapEpisodes(group, detail.episodes, anchor)
        val info = SeriesInfo(
            group.key, animeId, detail.anime.title, detail.anime.imageUrl, detail.anime.description, detail.anime.rating, detail.typeDescription,
            detail.episodes, method, LibraryRecognition.signature(group), nowMs(),
        )
        if (store.saveSeries(info, mapped.mapValues { it.value.id }, group.items().map { it.entry.id }, keepManual)) changes.incrementAndGet()
        return info
    }

    /** 忽略已有结果重新自动识别（用户在作品页选择“重新识别 → 自动识别”）。 */
    fun redo(group: LibraryGroup): SeriesInfo {
        val session = Session()
        val sample = sampleOf(group)
        val matches = sample?.let { single(it) }.orEmpty()
        val exact = matches.singleOrNull { it.exact && it.animeId > 0 }
        val found = if (exact != null && sample != null) Found(exact.animeId.toString(), RecognitionMethod.HASH, sample.entry.id to exact.episodeId) else fuzzy(group, matches, session)
        return found?.let { bind(group, it.animeId, it.method, it.anchor, false, session) } ?: none(group, false)
    }

    /** 用户手动指定作品；以后自动识别不会覆盖。 */
    fun assign(group: LibraryGroup, anime: AnimeSummary): SeriesInfo = bind(group, anime.id, RecognitionMethod.MANUAL, null, false, Session())

    /** 重新识别时给用户挑选的候选：服务端对样本文件的匹配结果加上名称搜索结果。 */
    fun candidates(group: LibraryGroup, keyword: String): List<AnimeSummary> {
        val matched = sampleOf(group)?.let { runCatching { single(it) }.getOrDefault(emptyList()) }.orEmpty()
            .filter { it.animeId > 0 }.distinctBy { it.animeId }.map { AnimeSummary(it.animeId.toString(), it.animeTitle, it.imageUrl) }
        val searched = keyword.trim().takeIf { it.length >= 2 }?.let(api::search).orEmpty()
        return (matched + searched).distinctBy { it.id }
    }

    private companion object {
        val cjkScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        const val BATCH_SIZE = 32
        const val ALIAS_CHECKS = 2
        const val QUOTA_PAUSE_MS = 30 * 60_000L
    }
}
