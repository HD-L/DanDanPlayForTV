package tv.dandan.app.data

import android.content.Context
import org.json.JSONObject
import java.io.IOException

data class SubtitlePreferences(val account: SubtitleAccount = SubtitleAccount(), val autoMatch: Boolean = true, val language: String = "zh-cn")

class SubtitleStore internal constructor(private val storage: StoreStorage, private val secrets: StoredSecrets) {
    constructor(context: Context) : this(AndroidStoreStorage(context), KeystoreStoredSecrets())
    fun read(): SubtitlePreferences = RepositoryTransactions.run {
        try {
            val j = JSONObject(storage.read("subtitleService") ?: "{}")
            fun secret(key: String) = j.optString(key).takeIf { it.isNotEmpty() }?.let(secrets::decrypt).orEmpty()
            SubtitlePreferences(SubtitleAccount(secret("apiKey"), secret("username"), secret("password"), j.optString("userAgent", "DanDanTV v0.2.0")), j.optBoolean("autoMatch", true), j.optString("language", "zh-cn"))
        } catch (_: Exception) { throw IOException("无法读取字幕服务配置，请重新配置服务凭证") }
    }
    fun save(value: SubtitlePreferences) = RepositoryTransactions.run {
        require(value.language in LANGUAGES.map { it.first }) { "字幕语言无效" }
        if (listOf(value.account.apiKey, value.account.username, value.account.password).any { it.isNotBlank() }) value.account.validate()
        fun encrypted(text: String) = if (text.isEmpty()) "" else secrets.encrypt(text)
        val j = JSONObject().put("apiKey", encrypted(value.account.apiKey)).put("username", encrypted(value.account.username)).put("password", encrypted(value.account.password))
            .put("userAgent", value.account.userAgent).put("autoMatch", value.autoMatch).put("language", value.language)
        if (!storage.write(mapOf("subtitleService" to j.toString()))) throw IOException("字幕服务配置保存失败")
    }
    companion object { val LANGUAGES = listOf("zh-cn" to "简体中文", "zh-tw" to "繁体中文", "en" to "英语", "ja" to "日语") }
}
