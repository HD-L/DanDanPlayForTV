@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.UserConfig

/**
 * 原生「我的」落地页，替换原嵌入的手机版 PersonalFragment。
 * 配置类设置（播放器/弹幕/字幕/应用）走原生 [TvSettingsScreen]；其余功能入口暂路由到现有屏，后续逐步原生化。
 */
@Composable
fun TvPersonalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf<TvSettingsSection?>(null) }

    settings?.let { section ->
        TvSettingsScreen(section = section, onBack = { settings = null })
        return
    }

    val entries: List<Pair<String, () -> Unit>> = listOf(
        "账号 / 登录" to {
            if (UserConfig.isUserLoggedIn()) TvUserInfoActivity.start(context)
            else TvLoginActivity.start(context)
        },
        "播放器设置" to { settings = TvSettingsSection.PLAYER },
        "弹幕设置" to { settings = TvSettingsSection.DANMU },
        "字幕设置" to { settings = TvSettingsSection.SUBTITLE },
        "应用设置" to { settings = TvSettingsSection.APP },
        "我的追番" to { TvAnimeListActivity.start(context, TvAnimeListMode.FOLLOW) },
        "云端历史" to { TvAnimeListActivity.start(context, TvAnimeListMode.HISTORY) },
        "扫描目录管理" to { TvScanManagerActivity.start(context) },
        "缓存目录管理" to { TvCacheManagerActivity.start(context) },
        "常用文件夹" to { TvCommonlyFolderActivity.start(context) },
        "B站弹幕下载" to { TvBiliBiliDanmuActivity.start(context) },
        "射手字幕下载" to { TvShooterSubtitleActivity.start(context) },
        "投屏接收端" to { TvScreencastReceiverActivity.start(context) },
    )

    Column(modifier = modifier.fillMaxSize()) {
        Text(text = "我的", modifier = Modifier.padding(start = 32.dp, top = 28.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(entries) { (label, action) ->
                TvEntryCard(label = label, onClick = action)
            }
        }
    }
}
