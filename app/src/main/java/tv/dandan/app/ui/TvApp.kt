package tv.dandan.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import tv.dandan.app.ui.components.NavRail
import tv.dandan.app.ui.components.PhoneFormDialog
import tv.dandan.app.ui.components.PhoneFormUiState
import tv.dandan.app.ui.components.ToastHost
import tv.dandan.app.ui.components.Toaster
import tv.dandan.app.ui.screens.*
import tv.dandan.app.ui.theme.DanDanTvTheme
import tv.dandan.app.ui.theme.TvColors

/** 应用外壳：左侧导航 + 右侧按路由切换的内容区，页面切换带方向感的滑动。 */
@Composable
fun TvApp(host: TvHost, toaster: Toaster, railFocus: FocusRequester, phoneForm: PhoneFormUiState, onRailFocus: (Boolean) -> Unit = {}) {
    val navigator = host.navigator
    DanDanTvTheme {
        Box(Modifier.fillMaxSize().background(TvColors.background)) {
            Row(Modifier.fillMaxSize()) {
                NavRail(selected = navigator.tab, onSelect = { if (it != navigator.tab) navigator.switchTab(it, fromRail = true) }, railFocus = railFocus, onFocusState = onRailFocus, onEnterContent = { FocusGate.enterContent(navigator.current.key) })
                val route = navigator.current
                val stateHolder = rememberSaveableStateHolder()
                // 兜底：新页面没有声明初始焦点（如空态）时，2 秒后结束"切换中"状态，导航栏恢复正常。
                LaunchedEffect(route.key) { kotlinx.coroutines.delay(2000); FocusGate.pendingContentFocus = false }
                AnimatedContent(
                    targetState = route,
                    contentKey = { it.key },
                    transitionSpec = {
                        val forward = navigator.direction >= 0
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 12 else -it / 12 })
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { if (forward) -it / 16 else it / 16 })
                    },
                    label = "route",
                    // 内容区向左离开时总是回到当前栏目的导航项，而不是几何最近的一项。
                    // 向左离开内容区：先记住当前聚焦项，再把焦点交给当前栏目的导航项。
                    modifier = Modifier.fillMaxSize().focusRequester(FocusGate.contentGroup).focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Left) { FocusGate.leaveContent(navigator.current.key); railFocus.requestFocus(); cancelFocusChange() } } }.focusGroup(),
                ) { target ->
                    // 每个页面一个焦点组：记录焦点当前落在哪个页面，并作为"进入内容区"的最后兜底目标。
                    // 退场动画期间旧页面仍然可聚焦，只看"离开了导航栏"会误判，必须确认焦点进的是当前页面。
                    val group = remember { FocusRequester() }
                    androidx.compose.runtime.DisposableEffect(target.key) {
                        FocusGate.routeGroups[target.key] = group
                        onDispose { if (FocusGate.routeGroups[target.key] === group) FocusGate.routeGroups.remove(target.key) }
                    }
                    Box(Modifier.fillMaxSize().focusRequester(group).onFocusChanged { if (it.hasFocus) FocusGate.focusedRouteKey = target.key }.focusGroup()) {
                        stateHolder.SaveableStateProvider(target.key) { RouteContent(target, host) }
                    }
                }
            }
            PhoneFormDialog(phoneForm)
            ToastHost(toaster)
        }
    }
}

@Composable
private fun RouteContent(route: Route, host: TvHost) {
    // 离开页面时取消属于该页面的手机表单，迟到的提交不会作用于下一页。
    val owner = remember(route.key) { Any() }
    LaunchedEffect(owner) { }
    androidx.compose.runtime.DisposableEffect(owner) { onDispose { host.cancelPhoneForm(owner) } }
    when (route) {
        Route.Home -> HomeScreen(host, owner)
        Route.Library -> LibraryScreen(host, owner)
        Route.Anime -> AnimeHubScreen(host, owner)
        Route.Settings -> SettingsScreen(host)
        is Route.Directory -> DirectoryScreen(host, owner, route)
        Route.Favorites -> FavoritesScreen(host, owner)
        is Route.LibrarySeries -> LibrarySeriesScreen(host, route)
        is Route.LibraryMatch -> LibraryMatchScreen(host, owner, route)
        Route.LibraryIndex -> LibraryIndexScreen(host, owner)
        is Route.SourceActions -> SourceActionsScreen(host, owner, route.source)
        is Route.SourceEditor -> SourceEditorScreen(host, owner, route.source)
        is Route.MediaActions -> MediaActionsScreen(host, owner, route)
        is Route.MatchDanmaku -> MatchDanmakuScreen(host, route.entry)
        is Route.AttachmentUrl -> AttachmentUrlScreen(host, owner, route)
        is Route.SubtitleSearch -> SubtitleSearchScreen(host, owner, route)
        Route.VideoUrl -> VideoUrlScreen(host, owner)
        is Route.AnimeSearch -> AnimeSearchScreen(host, owner, route.bindingMedia)
        Route.AnimeSeasons -> AnimeSeasonsScreen(host)
        is Route.AnimeSeasonList -> AnimeSeasonListScreen(host, route.season)
        is Route.AnimeDetail -> AnimeDetailScreen(host, route.anime, route.bindingMedia)
        is Route.AnimeTagSearch -> AnimeTagSearchScreen(host, route.tag)
        Route.AnimeFollows -> AnimeFollowsScreen(host)
        is Route.MagnetSearch -> MagnetSearchScreen(host, owner, route)
        is Route.TorrentFiles -> TorrentFilesScreen(host, route)
        Route.MagnetSettings -> MagnetSettingsScreen(host, owner)
        is Route.Preference -> PreferenceScreen(host, route.section)
        Route.BlockSettings -> BlockSettingsScreen(host, owner)
        Route.Credentials -> CredentialsScreen(host, owner)
        Route.CloudBlock -> CloudBlockScreen(host)
        Route.Remote -> RemoteScreen(host)
        Route.SubtitleSettings -> SubtitleSettingsScreen(host, owner)
        Route.DataManagement -> DataManagementScreen(host, owner)
        is Route.BackupPreview -> BackupPreviewScreen(host, route)
        Route.BackupRestored -> BackupRestoredScreen(host)
        Route.Capabilities -> CapabilitiesScreen(host)
        is Route.Confirm -> ConfirmScreen(host, route)
    }
}
