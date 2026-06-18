@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseAppCompatActivity
import com.xyoye.common_component.bridge.LoginObserver
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.extension.deletable
import com.xyoye.common_component.services.ScreencastReceiveService
import com.xyoye.common_component.utils.scraper.MediaScraper
import com.xyoye.common_component.utils.scraper.ScrapeProgress
import com.xyoye.common_component.application.DanDanPlay
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.dandanplay.R
import com.xyoye.dandanplay.databinding.ActivityTvMainBinding
import com.xyoye.dandanplay.ui.main.MainViewModel
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.fragment.media.MediaViewModel
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Compose for TV 主框架。左侧桌面式导航栏（替代手机端底部 Tab）+ 右侧内容区。
 *
 * - 媒体库：原生 Compose 卡片网格（复用 [MediaViewModel]），点击进入原生文件浏览 [TvStorageFileActivity]
 *   或现有的播放历史/投屏链路。
 * - 首页 / 我的：直接嵌入现有的 HomeFragment / PersonalFragment（已实现全部番剧/设置功能且带焦点支持），
 *   达成全功能复刻；后续可逐屏原生化为 10 尺界面。
 *
 * 继承 AppCompatActivity 以提供 supportFragmentManager 承载被嵌入的 Fragment。
 */
class TvMainActivity : BaseAppCompatActivity<ActivityTvMainBinding>(), LoginObserver {

    private val mainViewModel: MainViewModel by viewModels()

    @Autowired
    lateinit var receiveService: ScreencastReceiveService

    override fun initStatusBar() {}

    override fun getLayoutId() = R.layout.activity_tv_main

    override fun initView() {
        ARouter.getInstance().inject(this)

        dataBinding.composeView.setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvMainScreen()
                }
            }
        }

        // 复刻原 MainActivity 的启动初始化：数据库迁移、云端弹幕屏蔽列表、投屏接收自启、自动重登录
        mainViewModel.initDatabase()
        mainViewModel.initCloudBlockData()
        initScreencastReceive()
        if (UserConfig.isUserLoggedIn()) {
            mainViewModel.reLogin()
        }
    }

    override fun getLoginLiveData() = mainViewModel.reLoginLiveData

    private fun initScreencastReceive() {
        if (ScreencastConfig.isStartReceiveWhenLaunch().not()) {
            return
        }
        if (receiveService.isRunning(this)) {
            return
        }
        var httpPort = ScreencastConfig.getReceiverPort()
        if (httpPort == 0) {
            httpPort = Random.nextInt(20000, 30000)
            ScreencastConfig.putReceiverPort(httpPort)
        }
        val receiverPwd = ScreencastConfig.getReceiverPassword()
        receiveService.startService(this, httpPort, receiverPwd)
    }
}

private enum class TvDestination(val title: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    SEARCH("搜索", Icons.Default.Search),
    MEDIA("媒体库", Icons.Default.List),
    PERSONAL("我的", Icons.Default.Person),
}

@Composable
private fun TvMainScreen() {
    var selected by remember { mutableStateOf(TvDestination.HOME) }
    Row(modifier = Modifier.fillMaxSize()) {
        TvNavRail(
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.fillMaxHeight()
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selected) {
                TvDestination.HOME -> TvHomeScreen(
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.SEARCH -> TvSearchScreen(
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.MEDIA -> TvMediaScreen(
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                )

                TvDestination.PERSONAL -> TvPersonalScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 图标导航栏：默认窄（仅图标），任一项获得焦点时展开显示文字（聚焦展开）。
 */
@Composable
private fun TvNavRail(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (expanded) 240.dp else 104.dp,
        label = "railWidth"
    )
    Column(
        modifier = modifier
            .width(railWidth)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TvDestination.values().forEach { dest ->
            Button(
                onClick = { onSelect(dest) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (dest == selected) Modifier.focusRequester(selectedFocus)
                        else Modifier
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(imageVector = dest.icon, contentDescription = dest.title)
                    if (expanded) {
                        Text(text = dest.title)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
}

/**
 * 在 Compose 内容区嵌入一个由 ARouter 路由获得的现有 Fragment。
 * 进入时通过 supportFragmentManager 添加，离开 composition（切换目的地）时移除。
 */
@Composable
private fun FragmentHost(route: String, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as FragmentActivity
    val fragmentManager = activity.supportFragmentManager
    val containerId = rememberSaveable { View.generateViewId() }

    AndroidView(
        factory = { context -> FragmentContainerView(context).apply { id = containerId } },
        modifier = modifier
    ) {
        if (fragmentManager.findFragmentById(containerId) == null && !fragmentManager.isStateSaved) {
            val fragment = ARouter.getInstance().build(route).navigation() as? Fragment
            if (fragment != null) {
                fragmentManager.commit { add(containerId, fragment) }
            }
        }
    }

    DisposableEffect(route) {
        onDispose {
            val fragment = fragmentManager.findFragmentById(containerId)
            if (fragment != null && !fragmentManager.isStateSaved) {
                fragmentManager.commit(allowStateLoss = true) { remove(fragment) }
            }
        }
    }
}

@Composable
private fun TvMediaScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mediaViewModel: MediaViewModel = viewModel()
    val scrapeViewModel: TvMediaScrapeViewModel = viewModel()
    val libraries by mediaViewModel.mediaLibWithStatusLiveData.observeAsState()
    val list = libraries ?: emptyList()
    var showAddPicker by remember { mutableStateOf(false) }
    var editState by remember { mutableStateOf<Pair<MediaType, MediaLibraryEntity?>?>(null) }
    var manageTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }

    LaunchedEffect(Unit) {
        mediaViewModel.initLocalStorage()
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(text = "媒体库")
                Button(onClick = { showAddPicker = true }) {
                    Text(text = "＋ 添加网络媒体库")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(list) { library ->
                    MediaLibraryCard(
                        library = library,
                        onClick = { openMediaLibrary(context, library, mediaViewModel) },
                        onLongClick = { if (library.mediaType.deletable) manageTarget = library }
                    )
                }
            }
        }

        if (showAddPicker) {
            AddStoragePicker(
                onDismiss = { showAddPicker = false },
                onPick = { type ->
                    showAddPicker = false
                    if (type.tvFormSupported()) {
                        editState = type to null
                    } else {
                        ARouter.getInstance()
                            .build(RouteTable.Stream.StoragePlus)
                            .withSerializable("mediaType", type)
                            .navigation(context)
                    }
                }
            )
        }

        editState?.let { (type, data) ->
            TvStorageEditDialog(
                mediaType = type,
                editData = data,
                onDismiss = { editState = null },
                onSaved = { editState = null }
            )
        }

        manageTarget?.let { lib ->
            ManageStorageDialog(
                library = lib,
                scrapeable = MediaScraper.isScrapeable(lib.mediaType),
                onScrape = {
                    manageTarget = null
                    scrapeViewModel.scrape(lib)
                },
                onEdit = {
                    manageTarget = null
                    if (lib.mediaType.tvFormSupported()) {
                        editState = lib.mediaType to lib
                    } else {
                        ARouter.getInstance()
                            .build(RouteTable.Stream.StoragePlus)
                            .withSerializable("mediaType", lib.mediaType)
                            .withParcelable("editData", lib)
                            .navigation(context)
                    }
                },
                onDelete = {
                    manageTarget = null
                    mediaViewModel.deleteStorage(lib)
                },
                onDismiss = { manageTarget = null }
            )
        }

        if (scrapeViewModel.scraping) {
            ScrapeProgressDialog(progress = scrapeViewModel.progress)
        }
    }
}

/** 媒体库逐源刮削的状态持有者；用 viewModelScope 保证刮削不随弹窗/重组取消 */
class TvMediaScrapeViewModel : ViewModel() {
    var scraping by mutableStateOf(false)
        private set
    var progress by mutableStateOf<ScrapeProgress?>(null)
        private set

    private var job: Job? = null

    fun scrape(library: MediaLibraryEntity) {
        if (scraping) return
        scraping = true
        progress = null
        job = viewModelScope.launch {
            try {
                val result = MediaScraper.scrape(library) { progress = it }
                ToastCenter.showSuccess("刮削完成：扫描 ${result.scanned}，识别 ${result.matched}")
            } catch (e: Exception) {
                e.printStackTrace()
                ToastCenter.showError("刮削失败：${e.message}")
            } finally {
                scraping = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }
}

@Composable
private fun ScrapeProgressDialog(progress: ScrapeProgress?) {
    Dialog(onDismissRequest = { }) {
        Surface(modifier = Modifier.width(460.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "正在刮削…")
                Text(text = "已扫描 ${progress?.scanned ?: 0}　识别 ${progress?.matched ?: 0}")
                progress?.currentFile?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ManageStorageDialog(
    library: MediaLibraryEntity,
    scrapeable: Boolean,
    onScrape: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = library.displayName)
                if (scrapeable) {
                    Button(
                        onClick = onScrape,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
                    ) { Text(text = "刮削此源") }
                }
                Button(
                    onClick = onEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scrapeable) Modifier else Modifier.focusRequester(firstFocus))
                ) { Text(text = "编辑") }
                Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(text = "删除") }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(text = "取消") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

/**
 * 新增网络媒体库类型选择器，复刻 MediaFragment.showAddStorageDialog。
 * 选中类型后路由到现有的 StoragePlusActivity 完成具体表单录入与持久化。
 */
@Composable
private fun AddStoragePicker(onDismiss: () -> Unit, onPick: (MediaType) -> Unit) {
    val types = remember { MediaType.values().filter { it.deletable } }
    val firstItemFocus = remember { FocusRequester() }

    // 用 Compose Dialog（独立窗口）天然将焦点限制在弹层内，避免遥控器焦点逃逸到背景
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(440.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = "新增网络媒体库")
                types.forEachIndexed { index, type ->
                    Button(
                        onClick = { onPick(type) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == 0) Modifier.focusRequester(firstItemFocus)
                                else Modifier
                            )
                    ) {
                        Text(text = type.storageName)
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "取消")
                }
            }
        }
    }

    LaunchedEffect(Unit) { firstItemFocus.requestFocus() }
}

@Composable
private fun MediaLibraryCard(
    library: MediaLibraryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (library.mediaType.cover != 0) {
                Image(
                    painter = painterResource(id = library.mediaType.cover),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = library.displayName)
                Text(text = library.mediaType.storageName)
            }
        }
    }
}

/**
 * 复刻 MediaFragment.launchMediaStorage 的点击路由逻辑。
 */
internal fun openMediaLibrary(
    context: Context,
    library: MediaLibraryEntity,
    mediaViewModel: MediaViewModel
) {
    when (library.mediaType) {
        // 串流 / 磁链 / 历史 → 原生播放历史页
        MediaType.STREAM_LINK,
        MediaType.MAGNET_LINK,
        MediaType.OTHER_STORAGE ->
            TvPlayHistoryActivity.start(context, library.mediaType)

        // 投屏接收 → 检测设备连通性（不跳转）
        MediaType.SCREEN_CAST ->
            mediaViewModel.checkScreenDeviceRunning(library)

        // 本地 / 外部存储：需文件读取权限后再浏览扫描
        MediaType.LOCAL_STORAGE,
        MediaType.EXTERNAL_STORAGE ->
            ensureLocalStorageAccess(context) { TvStorageFileActivity.start(context, library) }

        // 其余（SMB / FTP / WebDav / Alist / 远程）→ 原生 Compose 文件浏览
        else ->
            TvStorageFileActivity.start(context, library)
    }
}

/**
 * 本地视频扫描需要文件读取权限：
 * - Android 11+ 扫描任意目录依赖「所有文件访问」(MANAGE_EXTERNAL_STORAGE)，无运行时弹窗，引导去系统设置开启；
 * - Android 10 及以下走运行时存储权限弹窗。
 */
internal fun ensureLocalStorageAccess(context: Context, onReady: () -> Unit) {
    val activity = context as? AppCompatActivity ?: run {
        onReady()
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (Environment.isExternalStorageManager()) {
            onReady()
            return
        }
        CommonDialog.Builder(activity).apply {
            content = "扫描本地视频需要『所有文件访问』权限。\n请在接下来的系统设置中开启「允许管理所有文件」，返回后重新进入本地媒体库。"
            addPositive("去授权") {
                it.dismiss()
                val opened = runCatching {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }.isSuccess
                if (!opened) {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
            addNegative("取消") { it.dismiss() }
        }.build().show()
    } else {
        DanDanPlay.permission.storage.request(activity) {
            onGranted { onReady() }
            onDenied { ToastCenter.showError("获取存储权限失败，无法扫描本地视频") }
        }
    }
}
