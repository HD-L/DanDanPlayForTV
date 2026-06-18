@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.utils.subtitle.SubtitleUtils
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.SubDetailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生 TV 射手字幕下载：配置 API 密钥 → 关键词搜索 → 选条目看详情 → 下载单文件 / 下载并解压。
 * 直接走 ResourceRepository（避开 Paging3）。密钥需在 assrt.net 申请，否则接口报错。
 */
class TvShooterSubtitleActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvShooterSubtitleActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvShooterSubtitleScreen(onExit = { finish() })
                }
            }
        }
    }
}

class TvShooterSubtitleViewModel : ViewModel() {
    var results by mutableStateOf<List<SubDetailData>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var detail by mutableStateOf<SubDetailData?>(null)
        private set

    private fun token() = SubtitleConfig.getShooterSecret().orEmpty()

    fun search(keyword: String) {
        if (token().isEmpty()) { ToastCenter.showWarning("请先填写射手 API 密钥"); return }
        if (keyword.isBlank()) { ToastCenter.showWarning("请输入要搜索的影片名"); return }
        viewModelScope.launch {
            loading = true
            val result = ResourceRepository.searchSubtitle(token(), keyword, 1)
            loading = false
            if (result.isFailure) {
                ToastCenter.showError(result.exceptionOrNull()?.message ?: "搜索失败")
                return@launch
            }
            results = result.getOrNull()?.sub?.subs ?: emptyList()
            if (results.isEmpty()) ToastCenter.showWarning("没有搜到字幕")
        }
    }

    fun openDetail(id: Int) {
        viewModelScope.launch {
            loading = true
            val result = ResourceRepository.getSubtitleDetail(token(), id.toString())
            loading = false
            if (result.isFailure) {
                ToastCenter.showError(result.exceptionOrNull()?.message ?: "获取详情失败")
                return@launch
            }
            val sub = result.getOrNull()?.sub?.subs?.firstOrNull()
            if (sub == null) { ToastCenter.showError("获取字幕详情失败"); return@launch }
            detail = sub
        }
    }

    fun closeDetail() { detail = null }

    fun downloadSingle(fileName: String, url: String) {
        viewModelScope.launch {
            loading = true
            val path = withContext(Dispatchers.IO) {
                ResourceRepository.getResourceResponseBody(url).getOrNull()?.byteStream()
                    ?.let { SubtitleUtils.saveSubtitle(fileName, it) }
            }
            loading = false
            if (path.isNullOrEmpty()) ToastCenter.showError("保存字幕失败")
            else ToastCenter.showSuccess("已保存：$path")
        }
    }

    fun downloadAndUnzip(fileName: String, url: String) {
        viewModelScope.launch {
            loading = true
            val path = withContext(Dispatchers.IO) {
                ResourceRepository.getResourceResponseBody(url).getOrNull()?.byteStream()
                    ?.let { SubtitleUtils.saveAndUnzipFile(fileName, it) }
            }
            loading = false
            if (path.isNullOrEmpty()) ToastCenter.showError("解压失败，请尝试手动解压")
            else ToastCenter.showSuccess("已解压到：$path")
        }
    }
}

@Composable
private fun TvShooterSubtitleScreen(onExit: () -> Unit) {
    val viewModel: TvShooterSubtitleViewModel = viewModel()
    var secret by remember { mutableStateOf(SubtitleConfig.getShooterSecret().orEmpty()) }
    var keyword by remember { mutableStateOf("") }

    BackHandler(enabled = true) { onExit() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = "射手字幕下载")
        TvFormField(label = "API 密钥", value = secret, isPassword = true, placeholder = "assrt.net 申请") {
            secret = it
            SubtitleConfig.putShooterSecret(it)
        }
        TvFormField(label = "影片名", value = keyword, placeholder = "输入要搜索的影片名") { keyword = it }
        Button(onClick = { viewModel.search(keyword) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = if (viewModel.loading) "处理中…" else "搜索字幕")
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (viewModel.results.isEmpty()) {
                Text(
                    text = "搜索结果将显示在此处。",
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(viewModel.results) { item ->
                        Surface(onClick = { viewModel.openDetail(item.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = item.native_name?.takeIf { it.isNotBlank() } ?: item.videoname.orEmpty(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = listOfNotNull(item.lang?.desc, item.subtype, item.upload_time)
                                        .joinToString("　·　"),
                                    color = Color(0xFF8A8A8A)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    viewModel.detail?.let { detail ->
        SubtitleDetailDialog(
            detail = detail,
            onDismiss = { viewModel.closeDetail() },
            onDownloadZip = {
                viewModel.closeDetail()
                viewModel.downloadAndUnzip(detail.filename ?: "subtitle.zip", detail.url.orEmpty())
            },
            onDownloadFile = { file ->
                viewModel.closeDetail()
                viewModel.downloadSingle(file.f ?: "subtitle.srt", file.url.orEmpty())
            }
        )
    }
}

@Composable
private fun SubtitleDetailDialog(
    detail: SubDetailData,
    onDismiss: () -> Unit,
    onDownloadZip: () -> Unit,
    onDownloadFile: (com.xyoye.data_component.data.SubFileData) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(0.6f)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = detail.native_name?.takeIf { it.isNotBlank() } ?: detail.videoname.orEmpty())
                detail.filename?.let { Text(text = it, color = Color(0xFF8A8A8A), maxLines = 1, overflow = TextOverflow.Ellipsis) }

                if (!detail.url.isNullOrEmpty()) {
                    Button(onClick = onDownloadZip, modifier = Modifier.fillMaxWidth()) {
                        Text(text = "下载整包并解压")
                    }
                }

                val files = detail.filelist ?: emptyList()
                if (files.isNotEmpty()) {
                    Text(text = "单文件下载")
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { file ->
                            Button(onClick = { onDownloadFile(file) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = file.f ?: file.url.orEmpty(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(text = "关闭") }
            }
        }
    }
}
