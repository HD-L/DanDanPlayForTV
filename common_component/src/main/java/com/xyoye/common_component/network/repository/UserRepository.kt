package com.xyoye.common_component.network.repository

import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.network.Retrofit
import com.xyoye.common_component.utils.SecurityHelper

/**
 * Created by xyoye on 2024/1/6.
 */

object UserRepository : BaseRepository() {

    /**
     * 账号登录
     */
    suspend fun login(account: String, password: String) = run {
        // 登录签名：官方包用原生 SecurityHelper（内置官方 appSecret）；自编译包用「应用设置」里的开发者凭据
        // (DevelopConfig)，按 DanDanPlay 规则自行计算 hash = md5(appId + password + unixTimestamp + userName + appSecret)。
        // SecurityHelper.appId/buildHash 与官方签名证书绑定，自编译包取到的是 "error"，故必须改走 DevelopConfig。
        val security = SecurityHelper.getInstance()
        val timestamp = System.currentTimeMillis() / 1000
        val appId: String
        val hash: String
        if (security.isOfficialApplication) {
            appId = security.appId
            hash = security.buildHash(appId + password + timestamp + account)
        } else {
            appId = DevelopConfig.getAppId().orEmpty()
            val appSecret = DevelopConfig.getAppSecret().orEmpty()
            hash = (appId + password + timestamp + account + appSecret).toMd5String()
        }

        request()
            .param("userName", account)
            .param("password", password)
            .param("appId", appId)
            .param("unixTimestamp", timestamp.toString())
            .param("hash", hash)
            .doPost {
                Retrofit.danDanService.login(it)
            }
    }


    /**
     * 刷新Token
     */
    suspend fun refreshToken() = request()
        .doGet {
            Retrofit.danDanService.refreshToken()
        }

    /**
     * 注册账号
     */
    suspend fun register(
        account: String,
        password: String,
        screenName: String,
        email: String,
        appId: String,
        timestamp: String,
        sign: String
    ) = request()
        .param("userName", account)
        .param("password", password)
        .param("screenName", screenName)
        .param("email", email)
        .param("appId", appId)
        .param("unixTimestamp", timestamp)
        .param("hash", sign)
        .doPost {
            Retrofit.danDanService.register(it)
        }

    /**
     * 重置密码
     */
    suspend fun resetPassword(
        account: String,
        email: String,
        appId: String,
        timestamp: String,
        sign: String
    ) = request()
        .param("userName", account)
        .param("email", email)
        .param("appId", appId)
        .param("unixTimestamp", timestamp)
        .param("hash", sign)
        .doPost {
            Retrofit.danDanService.resetPassword(it)
        }

    /**
     * 找回账号
     */
    suspend fun retrieveAccount(
        email: String,
        appId: String,
        timestamp: String,
        sign: String
    ) = request()
        .param("email", email)
        .param("appId", appId)
        .param("unixTimestamp", timestamp)
        .param("hash", sign)
        .doPost {
            Retrofit.danDanService.retrieveAccount(it)
        }

    /**
     * 修改昵称
     */
    suspend fun updateScreenName(screenName: String) = request()
        .param("screenName", screenName)
        .doPost {
            Retrofit.danDanService.updateScreenName(it)
        }

    /**
     * 修改密码
     */
    suspend fun updatePassword(oldPassword: String, newPassword: String) = request()
        .param("oldPassword", oldPassword)
        .param("newPassword", newPassword)
        .doPost {
            Retrofit.danDanService.updatePassword(it)
        }

    /**
     * 校验凭证
     */
    suspend fun checkAuthenticate(appId: String, appSecret: String) = request()
        .doGet {
            Retrofit.danDanService.checkAuthenticate(appId, appSecret, 1)
        }
}