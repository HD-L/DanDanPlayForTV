@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Compose for TV 接入冒烟测试。
 *
 * 不接入启动流程，仅用于验证 Compose 编译器 + tv-material3 + activity-compose 在新工具链
 * （Gradle 8.5 / AGP 8.2.2 / Kotlin 1.9.22 / Compose Compiler 1.5.8）下可编译、可运行、焦点可用。
 *
 * 验证方式：
 *   adb shell am start -n com.xyoye.dandanplay/com.xyoye.dandanplay.ui.tv.TvComposeSmokeActivity
 */
class TvComposeSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TvSmokeScreen()
            }
        }
    }
}

@Composable
private fun TvSmokeScreen() {
    var count by remember { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Compose for TV 冒烟测试 OK")
            Text(text = "遥控器 OK 键点击次数：$count")
            Button(onClick = { count++ }) {
                Text(text = "按 OK 键 +1")
            }
        }
    }
}

@Preview
@Composable
private fun TvSmokeScreenPreview() {
    MaterialTheme { TvSmokeScreen() }
}
