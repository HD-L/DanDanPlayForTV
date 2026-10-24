package tv.dandan.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tv.dandan.core.*
import java.io.IOException

private val FILE_HASH = Regex("[0-9a-f]{32}")
private const val MAX_FILE_HASHES = 5000

internal class EncryptedStore(private val storage: StoreStorage, private val secrets: StoredSecrets) {
    constructor(context: Context) : this(AndroidStoreStorage(context), KeystoreStoredSecrets())
    private fun encrypt(value: String): String = if (value.isEmpty()) "" else secrets.encrypt(value)
    private fun decrypt(value: String): String = try { if (value.isEmpty()) "" else secrets.decrypt(value) }
        catch (_: Exception) { throw IOException("无法解密保存的凭证，请重新填写账号或开发者密钥") }
    private fun array(name: String): JSONArray = try { JSONArray(storage.read(name) ?: "[]") } catch (_: Exception) { throw IOException("保存的数据损坏，请重新配置") }
    private fun objectValue(name: String) = JSONObject(storage.read(name) ?: "{}")
    /** 大字段（索引、识别结果）按原始文本缓存解析结果；存储层在写入前返回同一个字符串对象。 */
    private val parsed = HashMap<String, Pair<String?, Any>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(name: String, parse: (String?) -> T): T = RepositoryTransactions.run {
        val raw = storage.read(name)
        parsed[name]?.takeIf { it.first === raw }?.second as T? ?: parse(raw).also { parsed[name] = raw to it }
    }
    private fun put(name: String, value: String) = write(mapOf(name to value))
    private fun write(changes: Map<String, String?>) { if (!storage.write(changes)) throw IOException("保存数据失败，请检查设备空间；现有数据未修改") }
    fun sources(): List<MediaSource> = RepositoryTransactions.run { LibraryJson.objects(array("sources")).map { item -> MediaSource(item.getString("id"), item.getString("name"), SourceKind.valueOf(item.getString("kind")), item.getString("location"), decrypt(item.optString("username")), decrypt(item.optString("password"))) } }
    private fun sourceJson(source: MediaSource) = JSONObject().put("id", source.id).put("name", source.name).put("kind", source.kind.name).put("location", source.location).put("username", encrypt(source.username)).put("password", encrypt(source.password))
    fun saveSource(source: MediaSource) = RepositoryTransactions.run {
        SourceRules.validate(source)
        val original = sources().firstOrNull { it.id == source.id }
        val values = LibraryJson.objects(array("sources")).filterNot { it.optString("id") == source.id }.toMutableList()
        require(values.size < 100) { "媒体来源最多保存 100 个" }
        values += sourceJson(source)
        val changes = if (original != null && (original.location != source.location || original.kind != source.kind)) removalChanges(source.id).toMutableMap() else mutableMapOf()
        changes["sources"] = JSONArray(values).toString()
        write(changes)
        if (original != null) RepositoryTransactions.invalidateHistory()
    }
    private fun removalChanges(id: String): Map<String, String?> {
        val currentHistory = history(); val currentFavorites = favorites(); val currentIndex = index()
        val owners = objectValue("episodeSources"); val bindings = objectValue("episodes"); val scans = objectValue("scanStates")
        val deletedIds = (currentHistory.map { it.media } + currentFavorites + currentIndex).filter { it.sourceId == id }.map { it.id }.toMutableSet()
        owners.keys().asSequence().filter { owners.optString(it) == id }.toList().forEach(deletedIds::add)
        deletedIds.forEach { bindings.remove(it); owners.remove(it) }; scans.remove(id)
        val library = objectValue("libraryEpisodes"); deletedIds.forEach(library::remove)
        val hashes = objectValue("fileHashes"); deletedIds.forEach(hashes::remove)
        return mapOf("libraryEpisodes" to library.toString(), "fileHashes" to hashes.toString(), "sources" to JSONArray(LibraryJson.objects(array("sources")).filterNot { it.optString("id") == id }).toString(), "history" to historyJson(currentHistory.filterNot { it.media.sourceId == id }), "favorites" to mediaList(currentFavorites.filterNot { it.sourceId == id }), "index" to mediaList(currentIndex.filterNot { it.sourceId == id }), "episodes" to bindings.toString(), "episodeSources" to owners.toString(), "scanStates" to scans.toString())
    }
    fun deleteSource(id: String) = RepositoryTransactions.run { write(removalChanges(id)); RepositoryTransactions.invalidateHistory() }
    fun credentials(): ApiCredentials = RepositoryTransactions.run { val item = objectValue("credentials"); ApiCredentials(item.optString("id"), decrypt(item.optString("secret"))) }
    fun saveCredentials(value: ApiCredentials) = RepositoryTransactions.run {
        require(value.appId.length <= 200 && value.appSecret.length <= 4096 && !(value.appId + value.appSecret).any { it.isISOControl() }) { "开发者凭证格式不正确" }
        require((value.appId.isBlank() && value.appSecret.isBlank()) || value.configured) { "请同时填写 AppId 和 AppSecret" }
        put("credentials", credentialsJson(value))
    }
    private fun credentialsJson(value: ApiCredentials) = JSONObject().put("id", value.appId.trim()).put("secret", encrypt(value.appSecret.trim())).toString()
    fun history(): List<PlaybackProgress> = RepositoryTransactions.run { LibraryJson.objects(array("history")).map {
        PlaybackProgress(LibraryJson.media(it.getJSONObject("media")), it.optLong("position"), it.optLong("duration"), it.optLong("updated"))
    }.sortedByDescending { it.updatedAtMs }.take(100) }
    private fun historyJson(values: List<PlaybackProgress>): String = JSONArray(values.take(100).map { JSONObject().put("media", LibraryJson.media(it.media)).put("position", it.positionMs).put("duration", it.durationMs).put("updated", it.updatedAtMs) }).toString()
    fun historyEpoch(): Long = RepositoryTransactions.historyEpoch()
    fun saveProgress(progress: PlaybackProgress, expectedEpoch: Long) = RepositoryTransactions.run {
        if (expectedEpoch != historyEpoch()) return@run
        if (progress.media.sourceId.isNotEmpty() && sources().none { it.id == progress.media.sourceId }) return@run
        require(progress.positionMs >= 0 && progress.durationMs >= 0) { "播放进度无效" }
        validateMedia(progress.media)
        put("history", historyJson(HistoryRules.merge(history(), progress)))
    }
    fun episodeFor(id: String): EpisodeBinding? = RepositoryTransactions.run { objectValue("episodes").optJSONObject(id)?.let { EpisodeBinding(it.getLong("episode"), it.optLong("shift")) } }
    fun saveEpisode(id: String, value: EpisodeBinding, sourceId: String = "") = RepositoryTransactions.run {
        require(id.isNotBlank() && id.length <= 200 && value.episodeId > 0 && value.shiftMs in -86400000..86400000) { "弹幕库编号或偏移无效" }
        require(sourceId.isEmpty() || sources().any { it.id == sourceId }) { "来源已删除" }
        val json = objectValue("episodes"); val owners = objectValue("episodeSources")
        if (!json.has(id) && json.length() >= 1000) { val removed = json.keys().next(); json.remove(removed); owners.remove(removed) }
        json.put(id, JSONObject().put("episode", value.episodeId).put("shift", value.shiftMs)); owners.put(id, sourceId)
        write(mapOf("episodes" to json.toString(), "episodeSources" to owners.toString()))
    }
    fun clearHistory() = RepositoryTransactions.run { write(mapOf("history" to null)); RepositoryTransactions.invalidateHistory() }
    fun settings(): AppSettings = RepositoryTransactions.run { LibraryJson.settings(objectValue("settings")) }
    fun saveSettings(s: AppSettings) = RepositoryTransactions.run { SettingsRules.validate(s); put("settings", LibraryJson.settings(s).toString()) }
    /** 本地追番：按番剧编号去重，最多 300 部，最近关注的排在前面。 */
    fun follows(): List<AnimeSummary> = RepositoryTransactions.run { LibraryJson.objects(array("animeFollows")).map { LibraryJson.anime(it) } }
    fun toggleFollow(anime: AnimeSummary): Boolean = RepositoryTransactions.run {
        require(anime.id.isNotBlank() && anime.id.length <= 200 && anime.title.length <= 500) { "番剧资料无效" }
        val current = follows()
        val following = current.any { it.id == anime.id }
        val next = if (following) current.filterNot { it.id == anime.id } else (listOf(anime) + current).take(300)
        put("animeFollows", JSONArray(next.map(LibraryJson::anime)).toString())
        !following
    }
    /** 本地已看标记：番剧编号 → 剧集编号集合。 */
    /** 全部已看标记，片库海报墙一次读取。 */
    fun allWatchedEpisodes(): Map<String, Set<Long>> = RepositoryTransactions.run {
        val all = objectValue("watchedEpisodes")
        all.keys().asSequence().associateWith { id -> all.optJSONArray(id)?.let { array -> (0 until array.length()).map { array.getLong(it) }.toSet() }.orEmpty() }
    }
    fun watchedEpisodes(animeId: String): Set<Long> = RepositoryTransactions.run { objectValue("watchedEpisodes").optJSONArray(animeId)?.let { array -> (0 until array.length()).map { array.getLong(it) }.toSet() }.orEmpty() }
    fun setWatched(animeId: String, episodeIds: Collection<Long>, watched: Boolean) = RepositoryTransactions.run {
        require(animeId.isNotBlank() && animeId.length <= 200) { "番剧编号无效" }
        val all = objectValue("watchedEpisodes")
        val next = (watchedEpisodes(animeId) + if (watched) episodeIds.toSet() else emptySet()) - (if (watched) emptySet() else episodeIds.toSet())
        if (next.isEmpty()) all.remove(animeId) else all.put(animeId, JSONArray(next.sorted().take(5000)))
        put("watchedEpisodes", all.toString())
    }
    fun favorites(): List<MediaEntry> = RepositoryTransactions.run { LibraryJson.objects(array("favorites")).map { LibraryJson.media(it) } }
    fun toggleFavorite(media: MediaEntry): Boolean = RepositoryTransactions.run {
        validateMedia(media)
        require(!media.directory && (media.sourceId.isEmpty() || sources().any { it.id == media.sourceId })) { "来源已删除或媒体不是视频" }
        val values = favorites(); val exists = values.any { it.id == media.id }
        require(exists || values.size < 1000) { "收藏最多保存 1000 个" }
        put("favorites", mediaList(if (exists) values.filterNot { it.id == media.id } else values + media))
        !exists
    }
    fun clearFavorites() = RepositoryTransactions.run { write(mapOf("favorites" to null)) }
    fun index(): List<MediaEntry> = cached("index") { raw ->
        val values = try { JSONArray(raw ?: "[]") } catch (_: Exception) { throw IOException("保存的数据损坏，请重新配置") }
        LibraryJson.objects(values).map { LibraryJson.media(it) }
    }
    fun saveIndex(sourceId: String, entries: List<MediaEntry>, expectedEpoch: Long, status: ScanSnapshot? = null): Boolean = RepositoryTransactions.run {
        if (historyEpoch() != expectedEpoch || sources().none { it.id == sourceId }) return@run false
        require(entries.size <= 50000 && entries.all { !it.directory && it.sourceId == sourceId }) { "索引条目无效" }
        entries.forEach(::validateMedia)
        val combined = index().filterNot { it.sourceId == sourceId } + entries.distinctBy { it.id }
        require(combined.size <= 50000) { "媒体库索引最多保存 50000 个视频，请删除部分来源索引" }
        val changes = mutableMapOf<String, String?>("index" to mediaList(combined))
        if (status != null) { val states = objectValue("scanStates"); states.put(sourceId, scanJson(status)); changes["scanStates"] = states.toString() }
        write(changes); true
    }
    fun scanStates(): List<ScanSnapshot> = RepositoryTransactions.run { val j = objectValue("scanStates"); j.keys().asSequence().map { id -> j.getJSONObject(id).let { ScanSnapshot(id, ScanPhase.valueOf(it.getString("phase")), directories = it.optInt("directories"), pendingDirectories = it.optInt("pending"), message = it.optString("message"), updatedAtMs = it.optLong("updated")) } }.toList() }
    private fun scanJson(s: ScanSnapshot) = JSONObject().put("phase", s.phase.name).put("directories", s.directories).put("pending", s.pendingDirectories).put("message", s.message).put("updated", s.updatedAtMs)
    /** 片库识别结果：作品键 → 弹弹play 作品资料。 */
    fun seriesInfos(): Map<String, SeriesInfo> = cached("seriesInfo") { raw ->
        val j = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        j.keys().asSequence().mapNotNull { key -> runCatching { LibraryJson.series(key, j.getJSONObject(key)) }.getOrNull() }.associateBy { it.key }
    }
    /** 识别产生的剧集绑定：文件编号 → 弹幕库剧集编号。手动绑定（episodes）优先于它。 */
    fun libraryBindings(): Map<String, Long> = cached("libraryEpisodes") { raw ->
        val j = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        j.keys().asSequence().associateWith { j.optLong(it) }.filterValues { it > 0 }
    }
    /**
     * 保存一部作品的识别结果，并一次性替换它的剧集绑定。[keepManual] 为真时不覆盖用户手动指定的结果，
     * 用于后台自动识别；返回是否已保存。
     */
    fun saveSeries(info: SeriesInfo, bindings: Map<String, Long>, unbind: Collection<String>, keepManual: Boolean): Boolean = RepositoryTransactions.run {
        require(info.key.isNotBlank() && info.key.length <= 1000 && bindings.keys.all { it.isNotBlank() && it.length <= 200 } && bindings.values.all { it > 0 }) { "识别结果无效" }
        val all = objectValue("seriesInfo")
        if (keepManual && info.method != RecognitionMethod.MANUAL && all.optJSONObject(info.key)?.optString("method") == RecognitionMethod.MANUAL.name) return@run false
        if (!all.has(info.key) && all.length() >= 3000) {
            all.keys().asSequence().minByOrNull { all.optJSONObject(it)?.optLong("updated") ?: 0L }?.let(all::remove)
        }
        all.put(info.key, LibraryJson.series(info))
        val episodes = objectValue("libraryEpisodes")
        unbind.forEach(episodes::remove)
        bindings.forEach { (id, episode) -> episodes.put(id, episode) }
        require(episodes.length() <= 60000) { "片库剧集绑定超过上限，请删除部分来源索引" }
        write(mapOf("seriesInfo" to all.toString(), "libraryEpisodes" to episodes.toString()))
        true
    }
    /** 弹幕匹配用的文件特征（前 16MB 的 MD5）：文件编号 → 大小与特征。文件大小变了视为换了文件，需要重新计算。 */
    fun fileHash(id: String, sizeBytes: Long): String? = RepositoryTransactions.run {
        objectValue("fileHashes").optJSONObject(id)?.takeIf { sizeBytes > 0 && it.optLong("size", -1) == sizeBytes }?.optString("hash")?.takeIf(FILE_HASH::matches)
    }
    /** 记住文件特征，网络来源下次播放或识别时不必再读 16MB。最多保留 [MAX_FILE_HASHES] 个，满了丢掉最久未更新的。 */
    fun saveFileHash(id: String, sizeBytes: Long, hash: String) = RepositoryTransactions.run {
        require(id.isNotBlank() && id.length <= 200 && sizeBytes > 0 && FILE_HASH.matches(hash)) { "文件特征无效" }
        val all = objectValue("fileHashes")
        if (!all.has(id) && all.length() >= MAX_FILE_HASHES) all.keys().asSequence().minByOrNull { all.optJSONObject(it)?.optLong("updated") ?: 0L }?.let(all::remove)
        all.put(id, JSONObject().put("size", sizeBytes).put("hash", hash).put("updated", System.currentTimeMillis()))
        put("fileHashes", all.toString())
    }
    fun hiddenLibrary(): Set<String> = RepositoryTransactions.run { LibraryJson.strings(array("hiddenLibrary")).toSet() }
    fun setLibraryHidden(key: String, hidden: Boolean) = RepositoryTransactions.run {
        require(key.isNotBlank() && key.length <= 1000) { "作品无效" }
        val current = hiddenLibrary()
        val next = if (hidden) current + key else current - key
        require(next.size <= 5000) { "隐藏的作品最多 5000 部" }
        put("hiddenLibrary", JSONArray(next.toList()).toString())
    }
    fun clearIndex() = RepositoryTransactions.run { write(mapOf("index" to null, "scanStates" to null)); RepositoryTransactions.invalidateHistory() }
    fun backupSnapshot(includeCredentials: Boolean = true): BackupSnapshot = RepositoryTransactions.run {
        val episodes = objectValue("episodes").let { j -> j.keys().asSequence().associateWith { j.getJSONObject(it).let { EpisodeBinding(it.getLong("episode"), it.optLong("shift")) } } }
        val owners = objectValue("episodeSources").let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        val exportedSources = if (includeCredentials) sources() else LibraryJson.objects(array("sources")).map { MediaSource(it.getString("id"), it.getString("name"), SourceKind.valueOf(it.getString("kind")), it.getString("location")) }
        BackupSnapshot(exportedSources, settings(), history(), favorites(), index(), episodes, owners, if (includeCredentials) credentials() else ApiCredentials())
    }
    fun restore(snapshot: BackupSnapshot) = RepositoryTransactions.run {
        BackupCodec.validate(snapshot)
        val episodes = JSONObject().also { j -> snapshot.episodes.forEach { (id, b) -> j.put(id, JSONObject().put("episode", b.episodeId).put("shift", b.shiftMs)) } }
        // 加密与序列化全部成功后，只进行一次跨字段原子写入。
        val changes = mapOf("sources" to JSONArray(snapshot.sources.map(::sourceJson)).toString(), "settings" to LibraryJson.settings(snapshot.settings).toString(), "history" to historyJson(snapshot.history), "favorites" to mediaList(snapshot.favorites), "index" to mediaList(snapshot.index), "episodes" to episodes.toString(), "episodeSources" to JSONObject(snapshot.episodeSources).toString(), "credentials" to credentialsJson(snapshot.credentials), "scanStates" to null)
        write(changes)
        RepositoryTransactions.invalidateHistory()
    }
    private fun validateMedia(m: MediaEntry) { require(m.id.isNotBlank() && m.id.length <= 200 && m.title.isNotBlank() && m.title.length <= 1000 && m.sizeBytes >= 0) { "媒体字段无效" }; listOf(m.uri, m.subtitleUri, m.danmakuUri).filter { it.isNotEmpty() }.forEach(SourceRules::validateMediaUri) }
    private fun mediaList(values: List<MediaEntry>) = JSONArray(values.map(LibraryJson::media)).toString()
}
