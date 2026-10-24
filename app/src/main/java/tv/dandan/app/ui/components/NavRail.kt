package tv.dandan.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens

data class NavItem(val title: String, val icon: ImageVector)

val navItems = listOf(
    NavItem("首页", Icons.Default.Home),
    NavItem("媒体库", Icons.Default.List),
    NavItem("番剧", Icons.Default.Star),
    NavItem("设置", Icons.Default.Settings),
)

/**
 * 左侧导航栏：无焦点时只显示图标，焦点进入时展开文字。
 * 焦点落在某一项即切换栏目（不用按确定），和主流 TV 应用一致。
 */
@Composable
fun NavRail(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, railFocus: FocusRequester = remember { FocusRequester() }, onFocusState: (Boolean) -> Unit = {}, onEnterContent: () -> Boolean = { false }) {
    var hasFocus by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableStateOf(-1) }
    // 由导航栏自己发起的切换：用户可能已经继续往下移动，不能再把焦点拉回这一项。
    var railSelected by remember { mutableStateOf(-1) }
    // 焦点在导航项上停留 180ms 才切换栏目，连续上下移动时不会逐个加载经过的栏目。
    // 延迟后再确认一次：焦点仍停在导航栏、且不是页面切换期间系统临时塞给导航栏的焦点，才算用户选择。
    LaunchedEffect(focusedIndex) {
        fun intended() = focusedIndex >= 0 && hasFocus && tv.dandan.app.ui.FocusGate.railFocused && tv.dandan.app.ui.FocusGate.contentEverFocused && !tv.dandan.app.ui.FocusGate.pendingContentFocus
        if (intended()) { delay(180); if (intended() && focusedIndex != selected) { railSelected = focusedIndex; onSelect(focusedIndex) } }
    }
    // 外部切换栏目（例如返回键回首页）且焦点仍在导航栏时，把焦点移到新选中的栏目项。
    LaunchedEffect(selected) { val own = selected == railSelected; railSelected = -1; if (!own && hasFocus && focusedIndex != selected) { focusedIndex = selected; runCatching { railFocus.requestFocus() } } }
    val width by animateDpAsState(if (hasFocus) TvDimens.railExpanded else TvDimens.railCollapsed, tween(220), label = "rail")
    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(Brush.horizontalGradient(listOf(TvColors.surface, TvColors.background)))
            .onFocusChanged { hasFocus = it.hasFocus; tv.dandan.app.ui.FocusGate.railFocusChanged(it.hasFocus); onFocusState(it.hasFocus) }
            // 从内容区按左键进入导航时，总是落在当前栏目，而不是几何上最近的一项，避免误切换栏目。
            .focusProperties { onEnter = { if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) cancelFocusChange() } }
            .focusGroup()
            // 右键进入内容区由页面决定落点，而不是几何最近的项。
            .onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) onEnterContent() else false }
            .padding(vertical = 28.dp, horizontal = 10.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, bottom = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(TvColors.accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("弹", style = MaterialTheme.typography.labelLarge, color = TvColors.onAccent)
            }
            AnimatedVisibility(hasFocus, enter = fadeIn(tween(180)) + expandHorizontally(), exit = fadeOut(tween(100)) + shrinkHorizontally()) {
                Text("  弹弹 TV", style = MaterialTheme.typography.titleLarge, color = TvColors.onSurface, maxLines = 1)
            }
        }
        navItems.forEachIndexed { index, item ->
            val active = index == selected
            val shape = MaterialTheme.shapes.small as RoundedCornerShape
            ListItem(
                selected = active,
                onClick = { railSelected = index; onSelect(index) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (index == selected) Modifier.focusRequester(railFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
                shape = ListItemDefaults.shape(shape),
                colors = ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = TvColors.muted,
                    selectedContainerColor = TvColors.surfaceVariant,
                    selectedContentColor = TvColors.onSurface,
                    focusedContainerColor = TvColors.accent,
                    focusedContentColor = TvColors.onAccent,
                    focusedSelectedContainerColor = TvColors.accent,
                    focusedSelectedContentColor = TvColors.onAccent,
                ),
                scale = ListItemDefaults.scale(focusedScale = 1f),
                leadingContent = { Icon(item.icon, contentDescription = item.title, modifier = Modifier.size(24.dp)) },
                headlineContent = {
                    AnimatedVisibility(hasFocus, enter = fadeIn(tween(160)), exit = fadeOut(tween(80))) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(hasFocus, enter = fadeIn(tween(200)), exit = fadeOut(tween(100))) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("方向键移动", style = MaterialTheme.typography.labelSmall, color = TvColors.muted)
                Text("确定打开 · 返回上层", style = MaterialTheme.typography.labelSmall, color = TvColors.muted)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
