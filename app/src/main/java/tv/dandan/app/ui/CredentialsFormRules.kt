package tv.dandan.app.ui

import tv.dandan.core.ApiCredentials

object CredentialsFormRules {
    fun normalize(appId: String, appSecret: String): ApiCredentials {
        require(!appId.any(Char::isISOControl) && !appSecret.any(Char::isISOControl)) { "凭证不能包含控制字符" }
        val id = appId.trim()
        val secret = appSecret.trim()
        if (secret.isEmpty()) return ApiCredentials()
        require(id.isNotEmpty()) { "填写 AppSecret 时还需要填写 AppId" }
        return ApiCredentials(id, secret)
    }
}
