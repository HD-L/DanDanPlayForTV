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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2b · 原生 Compose-for-TV 文件浏览。
 *
 * 完全基于 common_component 的 Storage 抽象（StorageFactory / getRootFile / openDirectory / createPlayUrl）
 * 与起播管道（StorageVideoSourceFactory → VideoSourceManager → ARouter 播放器），不依赖 storage_component 内部。
 * 适用于 LOCAL / SMB / FTP / WebDav / Alist / 远程 / 外部 等所有走文件浏览的存储源。
 */
class TvStorageFileActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LIBRARY = "storageLibrary"

        fun start(context: Context, library: MediaLibraryEntity) {
            val intent = Intent(context, TvStorageFileActivity::class.java)
                .putExtra(EXTRA_LIBRARY, library)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val library = intent.getParcelableExtra<MediaLibraryEntity>(EXTRA_LIBRARY)
        val storage = library?.let { StorageFactory.createStorage(it) }
        if (storage == null) {
            ToastCenter.showError("无法打开该媒体库")
            finish()
            return
        }

        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvStorageFileScreen(storage, onExit = { finish() })
                }
            }
        }
    }
}

class TvStorageFileViewModel : ViewModel() {

    private lateinit var storage: Storage
    private var started = false
    private val directoryStack = ArrayDeque<StorageFile>()

    var files by mutableStateOf<List<StorageFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set

    fun start(storage: Storage) {
        if (started) return
        started = true
        this.storage = storage
        viewModelScope.launch {
            loading = true
            val root = withContext(Dispatchers.IO) { storage.getRootFile() }
            if (root == null) {
                loading = false
                ToastCenter.showError("无法读取根目录")
                return@launch
            }
            directoryStack.addLast(root)
            load(root)
        }
    }

    fun enterDirectory(folder: StorageFile) {
        directoryStack.addLast(folder)
        viewModelScope.launch { load(folder) }
    }

    /** @return false 表示已在根目录，调用方应退出页面 */
    fun back(): Boolean {
        if (directoryStack.size <= 1) return false
        directoryStack.removeLast()
        val parent = directoryStack.last()
        viewModelScope.launch { load(parent) }
        return true
    }

    fun play(context: Context, file: StorageFile) {
        viewModelScope.launch {
            loading = true
            val source = withContext(Dispatchers.IO) { StorageVideoSourceFactory.create(file) }
            loading = false
            if (source == null) {
                ToastCenter.showError("无法播放该文件")
                return@launch
            }
            VideoSourceManager.getInstance().setSource(source)
            ARouter.getInstance().build(RouteTable.Player.Player).navigation(context)
        }
    }

    private suspend fun load(directory: StorageFile) {
        loading = true
        title = directory.fileName().ifEmpty { "媒体库" }
        val children = withContext(Dispatchers.IO) { storage.openDirectory(directory, false) }
        files = children
            .filter { it.isDirectory() || it.isVideoFile() }
            .sortedWith(
                compareByDescending<StorageFile> { it.isDirectory() }
                    .thenBy { it.fileName().lowercase() }
            )
        loading = false
    }

    override fun onCleared() {
        super.onCleared()
        if (started) {
            storage.close()
        }
    }
}

@Composable
private fun TvStorageFileScreen(storage: Storage, onExit: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TvStorageFileViewModel = viewModel()

    LaunchedEffect(Unit) { viewModel.start(storage) }

    BackHandler(enabled = true) {
        if (!viewModel.back()) onExit()
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text(text = viewModel.title.ifEmpty { "媒体库" })
        Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            when {
                viewModel.loading && viewModel.files.isEmpty() ->
                    Text(text = "加载中…", modifier = Modifier.align(Alignment.Center))

                viewModel.files.isEmpty() ->
                    Text(text = "此目录没有可播放的内容", modifier = Modifier.align(Alignment.Center))

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(viewModel.files) { file ->
                        StorageFileCard(file) {
                            if (file.isDirectory()) viewModel.enterDirectory(file)
                            else viewModel.play(context, file)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageFileCard(file: StorageFile, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = if (file.isDirectory()) "📁" else "🎬")
            Text(
                text = file.fileName(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
