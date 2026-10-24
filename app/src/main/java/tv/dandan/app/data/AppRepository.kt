package tv.dandan.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import tv.dandan.core.*
import java.io.IOException
import java.security.MessageDigest

class AppRepository(context: Context) {
    private val context = context.applicationContext
    private val store = EncryptedStore(this.context)
    val api = DanDanApi(::credentials)
    val library: LibraryCatalog get() = synchronized(catalogLock) { catalog ?: LibraryCatalog(store, ::listEntries) { recognizer.start() }.also { catalog = it } }
    val recognizer: LibraryRecognizer get() = synchronized(catalogLock) { libraryRecognizer ?: LibraryRecognizer(store, api, ::libraryGroups, ::fileHash, log = { message, error -> android.util.Log.w("LibraryRecognizer", message, error) }).also { libraryRecognizer = it } }
    companion object {
        private val catalogLock = Any()
        private var catalog: LibraryCatalog? = null
        private var libraryRecognizer: LibraryRecognizer? = null
        @Volatile private var groupCache: Pair<List<MediaEntry>, List<LibraryGroup>>? = null
    }
    @Synchronized fun sources(): List<MediaSource> = store.sources()
    @Synchronized fun saveSource(source: MediaSource) { val valid = SourceRules.validate(source); library.forget(valid.id); store.saveSource(valid) }
    @Synchronized fun deleteSource(id: String) { library.forget(id); store.deleteSource(id) }
    @Synchronized fun history(): List<PlaybackProgress> = store.history()
    @Synchronized fun progress(mediaId: String): PlaybackProgress? = store.history().firstOrNull { it.media.id == mediaId }
    fun historyEpoch(): Long = store.historyEpoch()
    @Synchronized fun saveProgress(progress: PlaybackProgress, expectedEpoch: Long = historyEpoch()) {
        require(progress.positionMs >= 0 && progress.durationMs >= 0) { "播放进度无效" }
        store.saveProgress(progress, expectedEpoch)
        // 片库中已识别的剧集看完时同步标记官方剧集已看：与番剧详情页一致，也不受观看记录 100 条上限影响
        if (PlayerUiRules.watchedFraction(progress.positionMs, progress.durationMs) >= 1f) runCatching { markOfficialWatched(progress.media.id) }
    }
    private fun markOfficialWatched(mediaId: String) {
        val episode = episodeFor(mediaId)?.episodeId ?: return
        val anime = store.seriesInfos().values.firstOrNull { info -> info.recognized && info.episodes.any { it.id == episode } }?.animeId ?: return
        if (episode !in store.watchedEpisodes(anime)) store.setWatched(anime, listOf(episode), true)
    }
    @Synchronized fun clearHistory() { store.clearHistory() }
    @Synchronized fun settings(): AppSettings = store.settings()
    @Synchronized fun saveSettings(settings: AppSettings) { store.saveSettings(settings) }
    @Synchronized fun credentials(): ApiCredentials = store.credentials()
    @Synchronized fun saveCredentials(credentials: ApiCredentials) { store.saveCredentials(credentials) }
    /** 剧集绑定：手动绑定优先，其次是片库识别得到的绑定。 */
    @Synchronized fun episodeFor(mediaId: String): EpisodeBinding? = store.episodeFor(mediaId) ?: store.libraryBindings()[mediaId]?.let { EpisodeBinding(it) }
    @Synchronized fun saveEpisode(mediaId: String, binding: EpisodeBinding) { store.saveEpisode(mediaId, binding) }
    @Synchronized fun saveEpisode(media: MediaEntry, binding: EpisodeBinding) { store.saveEpisode(media.id, binding, media.sourceId) }
    @Synchronized fun follows(): List<AnimeSummary> = store.follows()
    @Synchronized fun isFollowing(animeId: String): Boolean = store.follows().any { it.id == animeId }
    @Synchronized fun toggleFollow(anime: AnimeSummary): Boolean = store.toggleFollow(anime)
    @Synchronized fun watchedEpisodes(animeId: String): Set<Long> = store.watchedEpisodes(animeId)
    @Synchronized fun allWatchedEpisodes(): Map<String, Set<Long>> = store.allWatchedEpisodes()
    @Synchronized fun setWatched(animeId: String, episodeIds: Collection<Long>, watched: Boolean) = store.setWatched(animeId, episodeIds, watched)
    @Synchronized fun favorites(): List<MediaEntry> = store.favorites()
    @Synchronized fun isFavorite(mediaId: String): Boolean = store.favorites().any { it.id == mediaId }
    @Synchronized fun toggleFavorite(media: MediaEntry): Boolean = store.toggleFavorite(media)
    @Synchronized fun clearFavorites() { store.clearFavorites() }
    @Synchronized fun indexedEntries(): List<MediaEntry> = store.index()
    /** 按作品归并的片库；索引未变化时复用上次的结果。 */
    fun libraryGroups(): List<LibraryGroup> {
        val index = indexedEntries()
        groupCache?.takeIf { it.first === index }?.let { return it.second }
        return LibraryGrouping.group(index).also { groupCache = index to it }
    }
    fun seriesInfos(): Map<String, SeriesInfo> = store.seriesInfos()
    fun libraryBindings(): Map<String, Long> = store.libraryBindings()
    fun hiddenLibrary(): Set<String> = store.hiddenLibrary()
    fun setLibraryHidden(key: String, hidden: Boolean) = store.setLibraryHidden(key, hidden)
    @Synchronized fun clearIndex() { library.cancelAll(); store.clearIndex() }
    fun exportBackup(password: String? = null): String = BackupCodec.export(store.backupSnapshot(password != null), password)
    fun validateBackup(text: String, password: String? = null): BackupSnapshot = BackupCodec.import(text, password)
    @Synchronized fun restoreBackup(snapshot: BackupSnapshot) { BackupCodec.validate(snapshot); library.cancelAll(); store.restore(snapshot) }
    fun readBackup(uri: Uri): String {
        val bytes = (context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开备份文件，请重新授权")).use { readLimited(it, BackupCodec.MAX_BYTES) }
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw IOException("备份必须使用 UTF-8 文本格式") }
    }
    fun writeBackup(uri: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupCodec.MAX_BYTES) { "备份超过 8 MB" }
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入备份，请检查文件授权和设备空间")).use { it.write(bytes); it.flush() }
    }
    fun listEntries(source: MediaSource, directoryUri: String = source.location): List<MediaEntry> {
        SourceRules.validate(source)
        SourceRules.requireWithin(source, directoryUri)
        val entries = try {
            when (source.kind) {
                SourceKind.HTTP -> listOf(MediaEntry(stableId(source.id, source.location), source.name, source.location, sourceId = source.id))
                SourceKind.DOCUMENT_TREE -> {
                    val uri = Uri.parse(directoryUri)
                    val tree = Uri.parse(source.location)
                    val documentId = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
                    val result = mutableListOf<MediaEntry>()
                    (context.contentResolver.query(children, projection, null, null, null) ?: throw IOException("目录授权已撤回或无法读取，请重新选择目录")).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (result.size >= 10000) throw IOException("目录条目超过 10000，请选择更具体的目录")
                            val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString()
                            SourceRules.requireWithin(source, child)
                            result += MediaEntry(stableId(source.id, child), cursor.getString(1).orEmpty(), child, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, source.id, if (cursor.isNull(3)) 0 else cursor.getLong(3).coerceAtLeast(0))
                        }
                    }
                    result
                }
                else -> NetworkBrowser.list(source, directoryUri)
            }
        } catch (e: IOException) { throw e } catch (_: SecurityException) { throw IOException("目录授权已撤回，请重新选择目录") } catch (_: Exception) { throw IOException("无法读取来源，请检查地址、账号和连接") }
        if (entries.size > 10000) throw IOException("目录条目超过 10000，请选择更具体的目录")
        return entriesForSource(source, entries)
    }
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 弹弹play 匹配用的文件特征：文件前 16MB 的 MD5（十六进制小写）。网络来源需要读取 16MB，算过的结果会保存下来，下次不必再读。 */
    fun fileHash(media: MediaEntry): String = hashCache.getOrPut(media.id) {
        runCatching { store.fileHash(media.id, media.sizeBytes) }.getOrNull()?.let { return@getOrPut it }
        computeHash(media).also { hash -> if (media.sizeBytes > 0) runCatching { store.saveFileHash(media.id, media.sizeBytes, hash) } }
    }

    /** 是否已经知道文件特征（不用再读取文件）。 */
    fun knowsFileHash(media: MediaEntry): Boolean =
        hashCache.containsKey(media.id) || runCatching { store.fileHash(media.id, media.sizeBytes) != null }.getOrDefault(false)

    private fun computeHash(media: MediaEntry): String {
        val digest = MessageDigest.getInstance("MD5")
        openHead(media.uri, media.sourceId).use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = HASH_BYTES
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read); left -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 从文件开头读取的输入流（本地、SMB/FTP、HTTP/WebDAV），HTTP 请求只取前 16MB 的范围。 */
    private fun openHead(uri: String, sourceId: String): java.io.InputStream {
        val parsed = Uri.parse(uri)
        val source = sourceForUri(sources(), uri, sourceId)
        source?.let { SourceRules.requireWithin(it, uri) }
        return when (parsed.scheme?.lowercase()) {
            "content", "file" -> context.contentResolver.openInputStream(parsed) ?: throw IOException("无法打开文件，请检查授权")
            "smb", "ftp" -> {
                val reader = RemoteReader.open(source ?: throw IOException("请先添加此网络来源及账号"), uri, 0)
                object : java.io.FilterInputStream(reader.asInputStream()) { override fun close() { reader.close() } }
            }
            "http", "https" -> {
                val response = SecureHttp.open(uri, source, 0, HASH_BYTES)
                if (!response.isSuccessful) { response.close(); throw IOException("读取文件失败（HTTP ${response.code}）") }
                val body = response.body ?: run { response.close(); throw IOException("文件内容为空") }
                object : java.io.FilterInputStream(body.byteStream()) { override fun close() { response.close() } }
            }
            else -> throw IOException("不支持此文件地址")
        }
    }

    fun readText(uri: String, sourceId: String = ""): String {
        val parsed = Uri.parse(uri)
        val source = sourceForUri(sources(), uri, sourceId)
        source?.let { SourceRules.requireWithin(it, uri) }
        val bytes = try {
            when (parsed.scheme?.lowercase()) {
                "content", "file" -> (context.contentResolver.openInputStream(parsed) ?: throw IOException("无法打开文件，请检查授权")).use { readLimited(it, 2 * 1024 * 1024) }
                "smb", "ftp" -> {
                    val configured = source ?: throw IOException("请先添加此网络来源及账号")
                    RemoteReader.open(configured, uri, 0).use { reader -> readLimited(reader.asInputStream(), 2 * 1024 * 1024) }
                }
                "http", "https" -> SecureHttp.open(uri, source).use { response ->
                    if (!response.isSuccessful) throw IOException("读取文件失败（HTTP ${response.code}）")
                    val body = response.body ?: throw IOException("文件内容为空")
                    readLimited(body.byteStream(), 2 * 1024 * 1024)
                }
                else -> throw IOException("不支持此文件地址")
            }
        } catch (e: IOException) { throw e } catch (_: Exception) { throw IOException("文件超过允许大小或无法读取") }
        val text = TextDecoding.decode(bytes).removePrefix("\uFEFF")
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "拒绝包含外部实体声明的 XML" }
        return text
    }
}

internal fun stableId(sourceId: String, uri: String): String = MessageDigest.getInstance("SHA-256").digest("$sourceId:$uri".toByteArray()).joinToString("") { "%02x".format(it) }
internal fun entriesForSource(source: MediaSource, entries: List<MediaEntry>): List<MediaEntry> = if (source.kind == SourceKind.HTTP) entries else associateSidecars(entries)
internal fun sourceForUri(sources: List<MediaSource>, uri: String, sourceId: String = ""): MediaSource? =
    sources.filter { runCatching { SourceRules.requireWithin(it, uri) }.isSuccess }.let { matched -> matched.firstOrNull { it.id == sourceId } ?: matched.maxByOrNull { it.location.length } }
internal fun associateSidecars(entries: List<MediaEntry>): List<MediaEntry> {
    val video = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv", "3gp", "m3u8", "mpd")
    val subtitles = setOf("srt", "vtt", "ass", "ssa", "ttml")
    return entries.filter { it.directory || it.title.substringAfterLast('.', "").lowercase() in video }.map { entry ->
        if (entry.directory) entry else {
            val stem = entry.title.substringBeforeLast('.').lowercase()
            val candidates = entries.filter { !it.directory && it.title.substringBeforeLast('.').lowercase().let { name -> name == stem || name.startsWith("$stem.") } }.sortedBy { it.title.length }
            entry.copy(subtitleUri = candidates.firstOrNull { it.title.substringAfterLast('.').lowercase() in subtitles }?.uri.orEmpty(), danmakuUri = candidates.firstOrNull { it.title.substringAfterLast('.').equals("xml", true) }?.uri.orEmpty())
        }
    }.sortedWith(LibraryRules.naturalOrder)
}

private const val HASH_BYTES = 16L * 1024 * 1024
