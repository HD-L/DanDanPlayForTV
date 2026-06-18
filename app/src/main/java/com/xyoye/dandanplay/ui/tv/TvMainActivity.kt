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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseAppCompatActivity
import com.xyoye.common_component.bridge.LoginObserver
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.config.UiConfig
import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.extension.deletable
import com.xyoye.common_component.services.ScreencastReceiveService
import com.xyoye.common_component.utils.SecurityHelper
import com.xyoye.common_component.utils.scraper.ScrapeManager
import com.xyoye.data_component.entity.ScrapeTaskEntity
import com.xyoye.data_component.entity.ScrapeTaskStatus
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

/**
 * 内容区目的地（均通过左侧图标导航栏切换）；搜索是独立页（由「番剧」面板搜索按钮打开 [TvSearchActivity]），不属于内容区目的地。
 * 用户头像不是目的地，而是触发登录弹窗 / 账号信息的独立动作。
 */
private enum class TvDestination(val title: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home),
    WEEKLY("每周番剧", Icons.Default.DateRange),
    HISTORY("播放历史", Icons.Default.History),
    STREAM("串流播放", Icons.Default.Link),
    MAGNET("磁链播放", Icons.Default.Download),
    SCREENCAST("投屏接收端", Icons.Default.Cast),
}

/** 可在「界面设置」里开关显隐的内容面板，按导航栏从上到下的固定顺序。SEARCH 不在此列（入口在「番剧」面板）。 */
private val toggleableDestinations = listOf(
    TvDestination.HOME,
    TvDestination.WEEKLY,
    TvDestination.HISTORY,
    TvDestination.STREAM,
    TvDestination.MAGNET,
    TvDestination.SCREENCAST,
)

/** 读取某面板当前是否展示（对应「界面设置」的开关，默认展示）。 */
private fun TvDestination.isVisible(): Boolean = when (this) {
    TvDestination.HOME -> UiConfig.isShowPosterWall()
    TvDestination.WEEKLY -> UiConfig.isShowAnime()
    TvDestination.HISTORY -> UiConfig.isShowHistory()
    TvDestination.STREAM -> UiConfig.isShowStream()
    TvDestination.MAGNET -> UiConfig.isShowMagnet()
    TvDestination.SCREENCAST -> UiConfig.isShowScreencast()
}

@Composable
private fun TvMainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showLogin by remember { mutableStateOf(false) }

    // 当前可见面板（受「界面设置」开关控制）。设置是独立 Activity，返回时按 ON_RESUME 重新读取配置刷新。
    var visibleDestinations by remember { mutableStateOf(toggleableDestinations.filter { it.isVisible() }) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                visibleDestinations = toggleableDestinations.filter { it.isVisible() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selected by remember { mutableStateOf(visibleDestinations.firstOrNull() ?: TvDestination.HOME) }
    // 当前选中的面板被隐藏后，回退到第一个可见面板（SEARCH 不在可切换列表里，不受影响）。
    LaunchedEffect(visibleDestinations) {
        if (selected in toggleableDestinations && selected !in visibleDestinations) {
            selected = visibleDestinations.firstOrNull() ?: TvDestination.HOME
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        TvNavRail(
            destinations = visibleDestinations,
            selected = selected,
            onSelect = { selected = it },
            onUserClick = {
                // 已登录 → 账号信息页；未登录 → 专门的登录弹窗
                if (UserConfig.isUserLoggedIn()) {
                    TvUserInfoActivity.start(context)
                } else {
                    showLogin = true
                }
            },
            onSettingsClick = {
                // 设置是独立页，启动其专用 Activity（不内嵌进 shell）
                TvSettingsActivity.start(context)
            },
            modifier = Modifier.fillMaxHeight()
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selected) {
                TvDestination.HOME -> TvHomeScreen(
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.WEEKLY -> TvWeeklyAnimeScreen(
                    onSearch = { TvSearchActivity.start(context) },
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.HISTORY -> TvPlayHistoryScreen(
                    mediaType = MediaType.OTHER_STORAGE,
                    showCloudTab = true,
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.STREAM -> TvPlayHistoryScreen(
                    mediaType = MediaType.STREAM_LINK,
                    title = "串流播放",
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.MAGNET -> TvPlayHistoryScreen(
                    mediaType = MediaType.MAGNET_LINK,
                    title = "磁链播放",
                    modifier = Modifier.fillMaxSize()
                )

                TvDestination.SCREENCAST -> TvScreencastReceiverScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showLogin) {
        TvLoginDialog(onDismiss = { showLogin = false })
    }
}

/**
 * 左侧竖排图标导航栏（参考 B 站 TV 番剧页布局）：
 * - 中部：由 [destinations] 决定展示哪些内容面板（受「界面设置」开关过滤；搜索入口已移至「番剧」面板顶部 tag 行）
 * - 底部：用户头像（点按弹出登录弹窗 / 进入账号信息） + 设置（专门的设置页，常驻）
 */
@Composable
private fun TvNavRail(
    destinations: List<TvDestination>,
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    onUserClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .width(72.dp)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 内容导航（按「界面设置」的显隐开关过滤；搜索入口已移至「番剧」面板顶部 tag 行）
        destinations.forEachIndexed { index, dest ->
            NavRailIcon(
                icon = dest.icon,
                contentDescription = dest.title,
                selected = selected == dest,
                focusRequester = if (index == 0) firstFocus else null,
                onClick = { onSelect(dest) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 底部：用户信息（登录弹窗 / 账号页） + 设置（独立 Activity）
        NavRailIcon(
            icon = Icons.Default.Person,
            contentDescription = "用户信息",
            selected = false,
            onClick = onUserClick
        )
        NavRailIcon(
            icon = Icons.Default.Settings,
            contentDescription = "设置",
            selected = false,
            onClick = onSettingsClick
        )
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

/** 导航栏圆形图标按钮：选中态蓝底，聚焦放大 + 白色描边。 */
@Composable
private fun NavRailIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF3F8CFF) else Color(0x1FFFFFFF),
            contentColor = if (selected) Color.White else Color(0xFFBFBFBF),
            focusedContainerColor = Color(0xFF3F8CFF),
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CircleShape)
        ),
        modifier = modifier
            .size(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 专门的登录弹窗（账号 / 密码）。由左侧导航栏底部用户头像在未登录时触发，
 * 复用 [TvLoginViewModel] 的登录逻辑与 [TvFormField] 的遥控器输入。
 */
@Composable
private fun TvLoginDialog(onDismiss: () -> Unit) {
    val viewModel: TvLoginViewModel = viewModel()
    val official = remember {
        runCatching { SecurityHelper.getInstance().isOfficialApplication() }.getOrDefault(false)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(560.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = "登录 DanDanPlay")
                if (official != true) {
                    Text(
                        text = "提示：自编译版本账号功能受限，需官方包或在「设置 - 应用设置」配置开发者凭据，否则登录会返回 403。",
                        color = Color(0xFFE5A23B)
                    )
                }
                TvFormField(label = "帐号", value = viewModel.account, placeholder = "未填写") { viewModel.account = it }
                TvFormField(label = "密码", value = viewModel.password, isPassword = true, placeholder = "未填写") { viewModel.password = it }
                Button(
                    onClick = { viewModel.login(onSuccess = onDismiss) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(text = if (viewModel.loading) "登录中…" else "登录") }
            }
        }
    }
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
internal fun TvMediaScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mediaViewModel: MediaViewModel = viewModel()
    val libraries by mediaViewModel.mediaLibWithStatusLiveData.observeAsState()
    val scrapeTasks by ScrapeManager.observeTasks().observeAsState()
    val taskByStorage = (scrapeTasks ?: emptyList()).associateBy { it.storageId }
    // 历史记录(OTHER_STORAGE)/串流(STREAM_LINK)/磁链(MAGNET_LINK) 已各自独立为侧边栏面板，故从媒体库管理隐藏
    val list = (libraries ?: emptyList())
        .filterNot {
            it.mediaType == MediaType.OTHER_STORAGE ||
                it.mediaType == MediaType.STREAM_LINK ||
                it.mediaType == MediaType.MAGNET_LINK
        }
    var showAddPicker by remember { mutableStateOf(false) }
    var editState by remember { mutableStateOf<Pair<MediaType, MediaLibraryEntity?>?>(null) }
    var menuTarget by remember { mutableStateOf<MediaLibraryEntity?>(null) }

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
                        scrapeLabel = scrapeStatusLabel(taskByStorage[library.id]),
                        onClick = { menuTarget = library },
                        onLongClick = { menuTarget = library }
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

        menuTarget?.let { lib ->
            val task = taskByStorage[lib.id]
            val scraping = task?.status == ScrapeTaskStatus.PENDING ||
                task?.status == ScrapeTaskStatus.RUNNING
            LibraryActionMenu(
                library = lib,
                scrapeable = ScrapeManager.isScrapeable(lib),
                scraping = scraping,
                onScrape = {
                    menuTarget = null
                    ScrapeManager.enqueue(context, lib)
                },
                onStopScrape = {
                    menuTarget = null
                    ScrapeManager.stop(context, lib.id)
                },
                onBrowse = {
                    menuTarget = null
                    openMediaLibrary(context, lib, mediaViewModel)
                },
                onEdit = {
                    menuTarget = null
                    when {
                        lib.mediaType == MediaType.LOCAL_STORAGE ->
                            TvScanManagerActivity.start(context)
                        lib.mediaType.tvFormSupported() ->
                            editState = lib.mediaType to lib
                        else ->
                            ARouter.getInstance()
                                .build(RouteTable.Stream.StoragePlus)
                                .withSerializable("mediaType", lib.mediaType)
                                .withParcelable("editData", lib)
                                .navigation(context)
                    }
                },
                onDelete = {
                    menuTarget = null
                    mediaViewModel.deleteStorage(lib)
                },
                onDismiss = { menuTarget = null }
            )
        }
    }
}

/** 媒体库列表项副标题：据刮削任务状态显示「等待刮削 / 刮削中 x/y / 刮削失败」，无任务返回 null */
private fun scrapeStatusLabel(task: ScrapeTaskEntity?): String? = when (task?.status) {
    ScrapeTaskStatus.PENDING -> "等待刮削…"
    ScrapeTaskStatus.RUNNING -> "刮削中 ${task.scanned}/${task.total}　识别 ${task.matched}"
    ScrapeTaskStatus.FAILED -> "刮削失败"
    else -> null
}

/**
 * 媒体库点击菜单（本地 / 网络库统一）：刮削↔停止刮削(据任务状态)、浏览文件、编辑、删除。
 * 不可刮削类型(串流/磁链/投屏/远程)隐藏刮削项；不可删除类型(本地媒体库等)隐藏删除项。
 */
@Composable
private fun LibraryActionMenu(
    library: MediaLibraryEntity,
    scrapeable: Boolean,
    scraping: Boolean,
    onScrape: () -> Unit,
    onStopScrape: () -> Unit,
    onBrowse: () -> Unit,
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
                    if (scraping) {
                        Button(
                            onClick = onStopScrape,
                            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
                        ) { Text(text = "停止刮削") }
                    } else {
                        Button(
                            onClick = onScrape,
                            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
                        ) { Text(text = "刮削") }
                    }
                }

                Button(
                    onClick = onBrowse,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scrapeable) Modifier else Modifier.focusRequester(firstFocus))
                ) { Text(text = "浏览文件") }

                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(text = "编辑") }

                if (library.mediaType.deletable) {
                    Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(text = "删除") }
                }

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
    scrapeLabel: String? = null,
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
                if (scrapeLabel != null) {
                    Text(text = scrapeLabel, color = Color(0xFF4FC3F7))
                }
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
