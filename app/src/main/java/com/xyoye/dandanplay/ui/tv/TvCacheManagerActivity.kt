@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.utils.IOUtils
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.common_component.utils.formatFileSize
import com.xyoye.common_component.utils.isDanmuFile
import com.xyoye.common_component.utils.isSubtitleFile
import com.xyoye.data_component.bean.CacheBean
import com.xyoye.data_component.enums.CacheType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存管理：展示系统缓存 / 各类缓存大小，逐项确认后清除。纯文件 I/O，无网络无登录。
 * 作为「设置」分组下的一个详情面板内联展示（[CacheSettings]），不再是独立页面。
 */
class TvCacheManagerViewModel : ViewModel() {

    private val appCacheDir = BaseApplication.getAppContext().cacheDir
    private val externalCacheDir = File(PathHelper.getCachePath())

    val systemCachePath: String = appCacheDir.absolutePath ?: ""
    val externalCachePath: String = externalCacheDir.absolutePath ?: ""

    var systemCacheSize by mutableStateOf("…")
        private set
    var externalCacheSize by mutableStateOf("…")
        private set
    var caches by mutableStateOf<List<CacheBean>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set

    fun refreshCache() {
        viewModelScope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                val sys = formatFileSize(IOUtils.getDirectorySize(appCacheDir))
                val ext = formatFileSize(IOUtils.getDirectorySize(externalCacheDir))
                val list = mutableListOf<CacheBean>()
                CacheType.values().forEach {
                    val count = when (it) {
                        CacheType.DANMU_CACHE -> getDanmuFileCount(PathHelper.getDanmuDirectory())
                        CacheType.SUBTITLE_CACHE -> getSubtitleFileCount(PathHelper.getSubtitleDirectory())
                        else -> 0
                    }
                    list.add(CacheBean(it, count, getCacheSize(it)))
                }
                list.add(CacheBean(null, 0, getCacheSize(null)))
                Triple(sys, ext, list)
            }
            systemCacheSize = result.first
            externalCacheSize = result.second
            caches = result.third
            loading = false
        }
    }

    fun clearSystemCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { clearCacheDirectory(appCacheDir) }
            refreshCache()
        }
    }

    fun clearCacheByType(cacheType: CacheType?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (cacheType != null) {
                    clearCacheDirectory(PathHelper.getCacheDirectory(cacheType))
                } else {
                    val named = CacheType.values().map { PathHelper.getCacheDirectory(it).absolutePath }
                    externalCacheDir.listFiles()?.forEach {
                        if (it.absolutePath !in named) clearCacheDirectory(it)
                    }
                }
            }
            refreshCache()
        }
    }

    private fun getCacheSize(cacheType: CacheType?): Long {
        return if (cacheType != null) {
            IOUtils.getDirectorySize(PathHelper.getCacheDirectory(cacheType))
        } else {
            val total = IOUtils.getDirectorySize(externalCacheDir)
            var named = 0L
            CacheType.values().forEach { named += getCacheSize(it) }
            (total - named).coerceAtLeast(0)
        }
    }

    private fun clearCacheDirectory(directory: File) {
        if (!directory.exists()) return
        if (directory.isFile) directory.delete()
        directory.listFiles()?.forEach {
            if (it.isDirectory) clearCacheDirectory(it) else it.delete()
        }
    }

    private fun getDanmuFileCount(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile && isDanmuFile(dir.absolutePath)) return 1
        var count = 0
        dir.listFiles()?.forEach {
            count += if (it.isDirectory) getDanmuFileCount(it)
            else if (isDanmuFile(it.absolutePath)) 1 else 0
        }
        return count
    }

    private fun getSubtitleFileCount(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile && isSubtitleFile(dir.absolutePath)) return 1
        var count = 0
        dir.listFiles()?.forEach {
            count += if (it.isDirectory) getSubtitleFileCount(it)
            else if (isSubtitleFile(it.absolutePath)) 1 else 0
        }
        return count
    }
}

/** 待清理项：标题 + 确认提示 + 执行动作 */
private data class ClearTarget(val title: String, val tips: String, val action: () -> Unit)

/**
 * 缓存管理详情面板（内联在「设置」右栏）。直接铺行（不用 LazyColumn）以适配设置右栏的整体滚动。
 */
@Composable
internal fun CacheSettings() {
    val viewModel: TvCacheManagerViewModel = viewModel()
    var pending by remember { mutableStateOf<ClearTarget?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshCache() }

    CacheRow(
        title = "系统缓存",
        subtitle = viewModel.systemCachePath,
        size = viewModel.systemCacheSize,
        onClick = {
            pending = ClearTarget("系统缓存", "清除应用的系统临时缓存，确认清除？") {
                viewModel.clearSystemCache()
            }
        }
    )
    CacheRow(
        title = "缓存目录",
        subtitle = viewModel.externalCachePath,
        size = viewModel.externalCacheSize,
        onClick = null
    )
    viewModel.caches.forEach { cache ->
        val type = cache.cacheType
        val title = type?.displayName ?: "其他缓存"
        val countSuffix = if (cache.fileCount > 0) "（${cache.fileCount}）" else ""
        CacheRow(
            title = "$title$countSuffix",
            subtitle = type?.dirName ?: "未归类的缓存文件",
            size = formatFileSize(cache.totalSize),
            onClick = {
                val tips = type?.clearTips ?: "清除其他未归类缓存，确认清除？"
                pending = ClearTarget(title, tips) { viewModel.clearCacheByType(type) }
            }
        )
    }

    pending?.let { target ->
        Dialog(onDismissRequest = { pending = null }) {
            Surface(modifier = Modifier.fillMaxWidth(0.55f)) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = "清除${target.title}")
                    Text(text = target.tips, color = Color(0xFFB5B5B5))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = {
                            target.action()
                            pending = null
                        }) { Text(text = "确认清除") }
                        Button(onClick = { pending = null }) { Text(text = "取消") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheRow(title: String, subtitle: String, size: String, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = subtitle, color = Color(0xFF8A8A8A), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(text = size)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Surface(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
