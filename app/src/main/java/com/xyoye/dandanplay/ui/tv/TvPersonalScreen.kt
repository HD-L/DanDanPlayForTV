@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface

/**
 * 独立的「设置」页 Activity（由左侧导航栏底部齿轮启动，不内嵌进主框架）。
 * 内容为左右分栏的 [TvSettingsLanding]：左侧功能栏（设置分区 + 功能入口），右侧随焦点联动的详细设置。
 */
class TvSettingsActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvSettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvSettingsLanding(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
