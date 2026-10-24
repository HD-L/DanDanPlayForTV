package tv.dandan.app.ui

import tv.dandan.app.data.SubtitleAccount
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm

object SubtitleForms {
    fun configuration(account: SubtitleAccount) = PhoneForm("配置在线字幕服务", "OpenSubtitles 的凭证与弹弹play开发者凭证独立。已存秘密不显示，留空保留；请填写你注册的应用名称与版本。",
        listOf(PhoneField("apiKey", "OpenSubtitles API Key", account.apiKey, secret = true, required = true, maxLength = 4096),
            PhoneField("username", "OpenSubtitles 账号", account.username, secret = true, required = true, maxLength = 4096),
            PhoneField("password", "OpenSubtitles 密码", account.password, secret = true, required = true, maxLength = 4096),
            PhoneField("userAgent", "注册应用名称与版本", account.userAgent, required = true, maxLength = 200)))
    fun account(values: Map<String, String>) = SubtitleAccount(values.getValue("apiKey").trim(), values.getValue("username").trim(), values.getValue("password"), values.getValue("userAgent").trim())
    fun validate(values: Map<String, String>): String? = runCatching { account(values).validate(); null }.getOrElse { it.message ?: "字幕服务配置无效" }
}
