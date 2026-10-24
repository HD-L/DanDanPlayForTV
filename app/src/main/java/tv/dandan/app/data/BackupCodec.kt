package tv.dandan.app.data

import org.json.JSONArray
import org.json.JSONObject
import tv.dandan.core.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 可移植格式独立于 Android Keystore；不包含在线字幕服务的提供方凭证。 */
object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "dandan-tv-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val CIPHER = "AES-256-GCM"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    fun validatePassword(password: String) { require(password.length in 10..256 && !password.any { it.isISOControl() }) { "备份密码须为 10 至 256 个字符，不能含控制字符" } }
    fun export(snapshot: BackupSnapshot, password: String? = null): String {
        val includeSecrets = password != null
        if (includeSecrets) validatePassword(password!!)
        validate(snapshot)
        if (!includeSecrets) validatePublicAddresses(snapshot)
        val payload = encode(snapshot, includeSecrets).toString()
        val root = JSONObject().put("format", FORMAT).put("version", VERSION).put("createdAt", System.currentTimeMillis())
        if (!includeSecrets) root.put("mode", "public").put("data", JSONObject(payload))
        else {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            root.put("mode", "password").put("cipher", CIPHER).put("kdf", KDF).put("iterations", ITERATIONS).put("salt", base64(salt)).put("iv", base64(iv))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = derive(password!!, salt)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(aad(root))
                root.put("payload", base64(cipher.doFinal(payload.toByteArray(Charsets.UTF_8))))
            } finally { key.fill(0) }
        }
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8 MB，请缩小媒体库索引" } }
    }
    fun isEncrypted(text: String): Boolean = envelope(text).getString("mode") == "password"
    fun import(text: String, password: String? = null): BackupSnapshot = try {
        val root = envelope(text)
        val secret = root.getString("mode") == "password"
        val data = if (!secret) root.getJSONObject("data") else {
            require(password != null) { "此备份需要密码，请用手机填写" }
            validatePassword(password)
            val salt = bytes(root.getString("salt")); val iv = bytes(root.getString("iv")); val encrypted = bytes(root.getString("payload"))
            require(salt.size == 16 && iv.size == 12 && encrypted.size >= 16) { "加密备份参数无效" }
            val key = derive(password, salt)
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(aad(root)); cipher.doFinal(encrypted)
            } catch (_: Exception) { throw IllegalArgumentException("备份密码不正确或文件已损坏") } finally { key.fill(0) }
            val raw = plain.toString(Charsets.UTF_8)
            checkJsonLimits(raw)
            JSONObject(raw)
        }
        decode(data, secret).also(::validate)
    } catch (e: IllegalArgumentException) { throw e } catch (_: Exception) { throw IllegalArgumentException("备份格式无效；现有数据未修改") }
    private fun envelope(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 8 MB" }
        checkJsonLimits(text)
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("无法识别备份；仅支持弹弹 TV 新工程格式") }
        require(LibraryJson.string(root, "format") == FORMAT && LibraryJson.integer(root, "version") == VERSION.toLong()) { "不支持此备份格式或版本；旧工程数据库需要独立迁移器" }
        require(LibraryJson.integer(root, "createdAt") >= 0) { "备份日期无效" }
        when (LibraryJson.string(root, "mode")) {
            "public" -> { LibraryJson.fields(root, setOf("format", "version", "createdAt", "mode", "data")); root.getJSONObject("data") }
            "password" -> {
                LibraryJson.fields(root, setOf("format", "version", "createdAt", "mode", "cipher", "kdf", "iterations", "salt", "iv", "payload"))
                require(LibraryJson.string(root, "cipher") == CIPHER && LibraryJson.string(root, "kdf") == KDF && LibraryJson.integer(root, "iterations") == ITERATIONS.toLong()) { "不支持此加密备份算法或参数" }
                listOf("salt", "iv", "payload").forEach { LibraryJson.string(root, it) }
            }
            else -> throw IllegalArgumentException("不支持此备份模式")
        }
        return root
    }
    private fun encode(s: BackupSnapshot, secret: Boolean): JSONObject = JSONObject()
        .put("sources", JSONArray(s.sources.map { source -> JSONObject().put("id", source.id).put("name", source.name).put("kind", source.kind.name).put("location", source.location).also { if (secret) it.put("username", source.username).put("password", source.password) } }))
        .put("settings", LibraryJson.settings(s.settings)).put("history", JSONArray(s.history.map { JSONObject().put("media", LibraryJson.media(it.media)).put("position", it.positionMs).put("duration", it.durationMs).put("updated", it.updatedAtMs) }))
        .put("favorites", JSONArray(s.favorites.map(LibraryJson::media))).put("index", JSONArray(s.index.map(LibraryJson::media)))
        .put("episodes", JSONObject().also { j -> s.episodes.forEach { (id, binding) -> j.put(id, JSONObject().put("episode", binding.episodeId).put("shift", binding.shiftMs)) } })
        .put("episodeSources", JSONObject(s.episodeSources))
        .also { if (secret) it.put("credentials", JSONObject().put("appId", s.credentials.appId).put("appSecret", s.credentials.appSecret)) }
    private fun decode(j: JSONObject, secret: Boolean): BackupSnapshot {
        LibraryJson.fields(j, setOf("sources", "settings", "history", "favorites", "index", "episodes", "episodeSources") + if (secret) setOf("credentials") else emptySet())
        val sources = LibraryJson.objects(j.getJSONArray("sources")).map { item ->
            LibraryJson.fields(item, setOf("id", "name", "kind", "location") + if (secret) setOf("username", "password") else emptySet())
            MediaSource(LibraryJson.string(item, "id"), LibraryJson.string(item, "name"), SourceKind.valueOf(LibraryJson.string(item, "kind")), LibraryJson.string(item, "location"), if (secret) LibraryJson.string(item, "username") else "", if (secret) LibraryJson.string(item, "password") else "")
        }
        val history = LibraryJson.objects(j.getJSONArray("history")).map { item ->
            LibraryJson.fields(item, setOf("media", "position", "duration", "updated"))
            PlaybackProgress(LibraryJson.media(item.getJSONObject("media"), true), LibraryJson.integer(item, "position"), LibraryJson.integer(item, "duration"), LibraryJson.integer(item, "updated"))
        }
        val bindings = j.getJSONObject("episodes").let { item -> item.keys().asSequence().associateWith { id -> item.getJSONObject(id).let { LibraryJson.fields(it, setOf("episode", "shift")); EpisodeBinding(LibraryJson.integer(it, "episode"), LibraryJson.integer(it, "shift")) } } }
        val owners = j.getJSONObject("episodeSources").let { item -> item.keys().asSequence().associateWith { LibraryJson.string(item, it) } }
        val credentials = if (!secret) ApiCredentials() else j.getJSONObject("credentials").let { LibraryJson.fields(it, setOf("appId", "appSecret")); ApiCredentials(LibraryJson.string(it, "appId"), LibraryJson.string(it, "appSecret")) }
        return BackupSnapshot(sources, LibraryJson.settings(j.getJSONObject("settings"), true), history, LibraryJson.objects(j.getJSONArray("favorites")).map { LibraryJson.media(it, true) }, LibraryJson.objects(j.getJSONArray("index")).map { LibraryJson.media(it, true) }, bindings, owners, credentials)
    }
    internal fun validate(s: BackupSnapshot) {
        require(s.sources.size <= 100 && s.history.size <= 100 && s.favorites.size <= 1000 && s.index.size <= 50000 && s.episodes.size <= 1000) { "备份条目超过业务预算" }
        fun validId(id: String) { require(id.isNotBlank() && id.length <= 200 && !id.any { it.isISOControl() }) { "备份编号无效" } }
        val sources = s.sources.associateBy { it.id }
        require(sources.size == s.sources.size) { "来源编号重复" }
        s.sources.forEach { validId(it.id); require(!it.name.any { ch -> ch.isISOControl() }) { "来源名称含无效控制字符" }; require(SourceRules.validate(it) == it) { "来源字段需要规范化" }; if (it.kind == SourceKind.DOCUMENT_TREE) SourceRules.requireWithin(it, it.location) }
        SettingsRules.validate(s.settings)
        fun media(m: MediaEntry) {
            validId(m.id); require(m.title.isNotBlank() && m.title.length <= 1000 && !m.title.any { it.isISOControl() } && !m.directory && m.sizeBytes >= 0) { "媒体字段无效" }
            listOf(m.uri, m.subtitleUri, m.danmakuUri).filter { it.isNotEmpty() }.forEach(SourceRules::validateMediaUri)
            if (m.sourceId.isNotEmpty()) SourceRules.requireWithin(sources[m.sourceId] ?: throw IllegalArgumentException("媒体引用了不存在的来源"), m.uri)
        }
        listOf(s.history.map { it.media }, s.favorites, s.index).forEach { values -> require(values.map { it.id }.distinct().size == values.size) { "媒体编号重复" }; values.forEach(::media) }
        val identities = mutableMapOf<String, Pair<String, String>>()
        (s.history.map { it.media } + s.favorites + s.index).forEach { entry ->
            val identity = entry.sourceId to entry.uri
            require(identities.putIfAbsent(entry.id, identity)?.let { it == identity } != false) { "同一媒体编号对应了不同来源或地址" }
        }
        s.history.forEach { require(it.positionMs >= 0 && it.durationMs >= 0 && it.updatedAtMs >= 0 && (it.durationMs == 0L || it.positionMs <= it.durationMs)) { "观看进度字段无效" } }
        s.episodes.forEach { (id, binding) -> validId(id); require(binding.episodeId > 0 && binding.shiftMs in -86400000..86400000) { "剧集绑定字段无效" } }
        s.episodeSources.forEach { (id, owner) ->
            require(id in s.episodes && (owner.isEmpty() || owner in sources)) { "剧集来源引用无效" }
            if (id in identities) require(identities.getValue(id).first == owner) { "剧集绑定来源与媒体归属不一致" }
        }
        require(s.credentials.appId.length <= 200 && s.credentials.appSecret.length <= 4096 && !(s.credentials.appId + s.credentials.appSecret).any { it.isISOControl() } && ((s.credentials.appId.isBlank() && s.credentials.appSecret.isBlank()) || s.credentials.configured)) { "开发者凭证字段无效" }
    }
    private fun validatePublicAddresses(snapshot: BackupSnapshot) {
        val sensitive = setOf("token", "key", "apikey", "auth", "authorization", "accesstoken", "refreshtoken", "idtoken", "password", "passwd", "pwd", "secret", "appsecret", "clientsecret", "signature", "sig", "session", "sessionid", "credential", "credentials", "jwt", "bearer", "xamzcredential", "xamzsignature", "xamzsecuritytoken", "xgoogcredential", "xgoogsignature")
        val media = snapshot.history.map { it.media } + snapshot.favorites + snapshot.index
        val uris = snapshot.sources.map { it.location } + media.flatMap { listOf(it.uri, it.subtitleUri, it.danmakuUri) }
        uris.filter { it.isNotEmpty() }.forEach { raw ->
            val uri = java.net.URI(raw)
            if (uri.scheme.lowercase(java.util.Locale.ROOT) in setOf("http", "https", "ftp", "smb")) {
                uri.rawQuery.orEmpty().split('&', ';').filter { it.isNotEmpty() }.forEach { parameter ->
                    val key = java.net.URLDecoder.decode(parameter.substringBefore('='), "UTF-8").lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                    require(key !in sensitive) { "媒体地址含访问令牌或签名参数，请选择包含凭据的密码备份" }
                }
            }
        }
    }
    private fun checkJsonLimits(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 32) { "备份层级过深" } }; '}', ']' -> depth-- }
        }
        require(depth == 0 && !quoted) { "备份结构无效" }
    }
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun aad(root: JSONObject) = "$FORMAT|$VERSION|${root.getLong("createdAt")}|$CIPHER|$KDF|$ITERATIONS".toByteArray(Charsets.UTF_8)
    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun bytes(text: String): ByteArray = Base64.getDecoder().decode(text)
}
