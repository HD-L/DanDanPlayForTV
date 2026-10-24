package tv.dandan.app.remote

import org.json.JSONObject
import tv.dandan.core.AppSettings
import tv.dandan.core.DanmakuLanguage

internal object RemoteConfigRules {
    fun apply(old: AppSettings, body: JSONObject): AppSettings {
        val allowed = setOf("danmakuEnabled", "danmakuSizeSp", "danmakuAlpha", "danmakuDurationMs", "danmakuStroke", "danmakuMaxLines", "danmakuMaxCount", "showScrolling", "showTop", "showBottom", "cloudDanmakuBlock", "danmakuLanguage")
        require(body.keys().asSequence().all { it in allowed }) { "包含不支持的弹幕设置" }
        fun number(key: String, fallback: Float, min: Float, max: Float): Float {
            if (!body.has(key)) return fallback
            require(body.get(key) is Number) { "数值设置格式无效" }
            val value = body.getDouble(key).toFloat()
            require(value.isFinite() && value in min..max) { "设置超出范围" }
            return value
        }
        fun integer(key: String, fallback: Int, max: Int): Int {
            val value = number(key, fallback.toFloat(), 0f, max.toFloat())
            require(value == value.toInt().toFloat()) { "条数和行数需要整数" }
            return value.toInt()
        }
        fun flag(key: String, fallback: Boolean): Boolean {
            if (!body.has(key)) return fallback
            require(body.get(key) is Boolean) { "开关设置格式无效" }
            return body.getBoolean(key)
        }
        val duration = number("danmakuDurationMs", old.danmakuDurationMs.toFloat(), 1000f, 30000f)
        require(duration == duration.toLong().toFloat()) { "弹幕经过时间需要整数毫秒" }
        return old.copy(danmakuEnabled = flag("danmakuEnabled", old.danmakuEnabled), danmakuSizeSp = number("danmakuSizeSp", old.danmakuSizeSp, 10f, 80f),
            danmakuAlpha = number("danmakuAlpha", old.danmakuAlpha, 0f, 1f), danmakuDurationMs = duration.toLong(), danmakuStroke = number("danmakuStroke", old.danmakuStroke, 0f, 10f),
            danmakuMaxLines = integer("danmakuMaxLines", old.danmakuMaxLines, 30), danmakuMaxCount = integer("danmakuMaxCount", old.danmakuMaxCount, 300),
            showScrolling = flag("showScrolling", old.showScrolling), showTop = flag("showTop", old.showTop), showBottom = flag("showBottom", old.showBottom),
            cloudDanmakuBlock = flag("cloudDanmakuBlock", old.cloudDanmakuBlock),
            danmakuLanguage = if (body.has("danmakuLanguage")) runCatching { DanmakuLanguage.valueOf(body.getString("danmakuLanguage")) }.getOrElse { throw IllegalArgumentException("弹幕语言无效") } else old.danmakuLanguage)
    }
}
