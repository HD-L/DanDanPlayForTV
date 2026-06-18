package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.Retrofit

/**
 * Created by xyoye on 2024/1/20.
 */

object AlistRepository : BaseRepository() {

    /**
     * 登录Alist，获取Token
     */
    suspend fun login(url: String, userName: String, password: String) = request()
        .param("username", userName)
        .param("password", password)
        .doPost {
            Retrofit.alistService.login(url, it)
        }

    /**
     * 获取Alist当前用户信息
     */
    suspend fun getUserInfo(url: String, token: String) = request()
        .doGet {
            Retrofit.alistService.getUserInfo(url, token)
        }

    /**
     * 打开文件夹（password 为 Alist 目录 meta 密码，受密码保护的目录需要）
     */
    suspend fun openDirectory(url: String, token: String, path: String, password: String? = null) = request()
        .param("path", path)
        .param("password", password ?: "")
        .doPost {
            Retrofit.alistService.openDirectory(url, token, it)
        }

    /**
     * 打开文件（获取下载地址，password 为 Alist 目录 meta 密码）
     */
    suspend fun openFile(url: String, token: String, path: String, password: String? = null) = request()
        .param("path", path)
        .param("password", password ?: "")
        .doPost {
            Retrofit.alistService.openFile(url, token, it)
        }
}