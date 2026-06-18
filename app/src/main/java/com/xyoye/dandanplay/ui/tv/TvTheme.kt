@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 纯 TV 深色主题，与嵌入的现有深色 Fragment 观感统一。
 * 背景近黑、卡片深灰、蓝色强调，文字浅色。
 */
private val TvDarkColorScheme = darkColorScheme(
    primary = Color(0xFF3F8CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1F4F8F),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF5AA0FF),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF161619),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF26262B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF34343A),
    onSurfaceVariant = Color(0xFFBFBFBF),
)

@Composable
fun TvAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvDarkColorScheme, content = content)
}
