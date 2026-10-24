package tv.dandan.app.ui

import tv.dandan.core.AppSettings
import tv.dandan.app.data.SettingsRules

object BlockedFormRules {
    private fun lines(value: String) = value.lines().map(String::trim).filter(String::isNotBlank).distinct()
    fun apply(settings: AppSettings, keywordText: String? = null, patternText: String? = null): AppSettings {
        val edited = settings.copy(blockedKeywords = keywordText?.let(::lines) ?: settings.blockedKeywords,
            blockedPatterns = patternText?.let(::lines) ?: settings.blockedPatterns)
        SettingsRules.validate(edited)
        return edited
    }
    fun validate(settings: AppSettings, keywordText: String? = null, patternText: String? = null): String? =
        runCatching { apply(settings, keywordText, patternText); null }.getOrElse { it.message ?: "屏蔽规则无效" }
}
