package com.xyoye.common_component.config

import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

/**
 * TV 界面显隐配置：控制左侧导航栏各内容面板是否展示，默认全部展示。
 * 生成 [UiConfig]，访问形如 UiConfig.isShowPosterWall() / UiConfig.putShowPosterWall(value)。
 */
@MMKVKotlinClass(className = "UiConfig")
object UiConfigTable {
    //海报墙（首页）
    @MMKVFiled
    var showPosterWall = true

    //番剧
    @MMKVFiled
    var showAnime = true

    //历史记录
    @MMKVFiled
    var showHistory = true

    //串流面板
    @MMKVFiled
    var showStream = true

    //磁力面板
    @MMKVFiled
    var showMagnet = true

    //投屏接收
    @MMKVFiled
    var showScreencast = true
}
