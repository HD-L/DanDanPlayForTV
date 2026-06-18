@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.utils.meida.VideoScan
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.FolderBean
import com.xyoye.data_component.entity.ExtendFolderEntity
import com.xyoye.data_component.entity.MediaLibraryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生 TV 扫描目录管理：
 * - 扩展目录：把任意本地文件夹纳入本地媒体库扫描（添加时扫描视频、删除时移除关联）
 * - 目录过滤：按文件夹开关是否在本地媒体库中显示
 * 纯本地数据库 + 文件扫描，无网络无登录。
 */
class TvScanManagerActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvScanManagerActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvScanManagerScreen(onExit = { finish() })
                }
            }
        }
    }
}

class TvScanManagerViewModel : ViewModel() {

    private val extendDao = DatabaseManager.instance.getExtendFolderDao()
    private val videoDao = DatabaseManager.instance.getVideoDao()

    var extendFolders by mutableStateOf<List<ExtendFolderEntity>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    val folderLiveData: LiveData<MutableList<FolderBean>> = videoDao.getAllFolder()

    fun loadExtend() {
        viewModelScope.launch {
            extendFolders = withContext(Dispatchers.IO) { extendDao.getAll() }
        }
    }

    fun addExtendFolder(folderPath: String) {
        viewModelScope.launch {
            scanning = true
            val videos = withContext(Dispatchers.IO) { VideoScan.traverse(folderPath) }
            if (videos.isEmpty()) {
                scanning = false
                ToastCenter.showError("失败，当前文件夹内未识别到任何视频")
                return@launch
            }
            withContext(Dispatchers.IO) {
                extendDao.insert(ExtendFolderEntity(folderPath, videos.size))
                refreshLocalStorage()
            }
            scanning = false
            ToastCenter.showSuccess("已添加，识别到 ${videos.size} 个视频")
            loadExtend()
        }
    }

    fun removeExtendFolder(entity: ExtendFolderEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                extendDao.delete(entity.folderPath)
                videoDao.deleteExtend()
            }
            loadExtend()
        }
    }

    fun updateFilter(folderPath: String, filter: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { videoDao.updateFolderFilter(filter, folderPath) }
        }
    }

    private suspend fun refreshLocalStorage() {
        val storage = StorageFactory.createStorage(MediaLibraryEntity.LOCAL) ?: return
        val root = storage.getRootFile() ?: return
        storage.openDirectory(root, true)
    }
}

@Composable
private fun TvScanManagerScreen(onExit: () -> Unit) {
    val viewModel: TvScanManagerViewModel = viewModel()
    var tab by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadExtend() }
    BackHandler(enabled = true) { onExit() }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { tab = 0 }) { Text(text = if (tab == 0) "● 扩展目录" else "扩展目录") }
            Button(onClick = { tab = 1 }) { Text(text = if (tab == 1) "● 目录过滤" else "目录过滤") }
        }

        Column(modifier = Modifier.fillMaxSize().padding(top = 20.dp)) {
            if (tab == 0) {
                ExtendTab(viewModel = viewModel, onAdd = { picking = true })
            } else {
                FilterTab(viewModel = viewModel)
            }
        }
    }

    if (picking) {
        TvLocalFolderPickerDialog(
            initialPath = Environment.getExternalStorageDirectory().absolutePath,
            onDismiss = { picking = false },
            onConfirm = { path ->
                picking = false
                viewModel.addExtendFolder(path)
            }
        )
    }
}

@Composable
private fun ExtendTab(viewModel: TvScanManagerViewModel, onAdd: () -> Unit) {
    Button(onClick = onAdd) {
        Text(text = if (viewModel.scanning) "扫描中…" else "＋ 添加扩展目录")
    }
    if (viewModel.extendFolders.isEmpty()) {
        Text(
            text = "还没有扩展目录。添加后，该文件夹下的视频会并入本地媒体库。",
            color = Color(0xFFB5B5B5),
            modifier = Modifier.padding(top = 16.dp)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(viewModel.extendFolders) { entity ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(modifier = Modifier.fillMaxWidth(0.82f)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(text = entity.folderPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = "${entity.childCount} 个视频", color = Color(0xFF8A8A8A))
                    }
                }
                Button(onClick = { viewModel.removeExtendFolder(entity) }) { Text(text = "删除") }
            }
        }
    }
}

@Composable
private fun FilterTab(viewModel: TvScanManagerViewModel) {
    val folders by viewModel.folderLiveData.observeAsState()
    val list = folders ?: emptyList()
    if (list.isEmpty()) {
        Text(text = "本地媒体库暂无文件夹", color = Color(0xFFB5B5B5))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(list) { folder ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(modifier = Modifier.fillMaxWidth(0.7f)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(text = folder.folderPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${folder.fileCount} 个视频" + if (folder.isFilter) "　·　已隐藏" else "",
                            color = if (folder.isFilter) Color(0xFFE5736B) else Color(0xFF8A8A8A)
                        )
                    }
                }
                Button(onClick = { viewModel.updateFilter(folder.folderPath, !folder.isFilter) }) {
                    Text(text = if (folder.isFilter) "显示" else "隐藏")
                }
            }
        }
    }
}
