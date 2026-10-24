package tv.dandan.app.ui.components

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import kotlinx.coroutines.delay

/** 页面骨架：标题区固定，内容区可滚动，左右留出电视安全边距。 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    scrollable: Boolean = true,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(start = TvDimens.screenPaddingStart, top = TvDimens.screenPaddingTop)) {
        Row(Modifier.fillMaxWidth().padding(end = TvDimens.screenPaddingEnd), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.displaySmall, color = TvColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            headerTrailing?.invoke()
        }
        Spacer(Modifier.height(20.dp))
        if (scrollable) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = TvDimens.screenPaddingEnd, bottom = 40.dp).restoreFocusOnReentry(), content = content)
        } else Column(Modifier.fillMaxSize(), content = content)
    }
}

/** 标题 + 横向卡片列表；LazyRow 记住上次焦点项，上下行切换时回到原位置。 */
@Composable
fun <T> ContentRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    trailing: String = "",
    key: ((T) -> Any)? = null,
    state: LazyListState = rememberLazyListState(),
    firstItemFocus: FocusRequester? = null,
    spacing: Dp = TvDimens.cardSpacing,
    /** [firstItemFocus] 绑定到第几项；用于返回页面时恢复到上次聚焦的卡片。 */
    focusIndex: Int = 0,
    onItemFocused: (Int) -> Unit = {},
    itemContent: @Composable (index: Int, item: T, modifier: Modifier) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        RowHeader(title, trailing = trailing)
        Spacer(Modifier.height(14.dp))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            contentPadding = PaddingValues(start = ROW_BLEED, end = TvDimens.screenPaddingEnd, top = 10.dp, bottom = 14.dp),
            modifier = Modifier.fillMaxWidth().bleedStart(ROW_BLEED).focusRestorer().focusGroup(),
        ) {
            val target = focusIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            items.forEachIndexed { index, item ->
                item(key = key?.invoke(item)) {
                    val focusModifier = (if (index == target && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier)
                        .onFocusChanged { if (it.hasFocus) onItemFocused(index) }
                    itemContent(index, item, focusModifier)
                }
            }
        }
    }
}

fun LazyListScope.rowSpacer(height: Dp = TvDimens.rowSpacing) = item { Spacer(Modifier.height(height)) }

/** 横向列表向左多留出的绘制区：首张卡片聚焦放大后边框不被裁掉，卡片本身仍与标题对齐。 */
val ROW_BLEED = 16.dp

/** 把组件向左扩出 [amount]（宽度同时增加），配合同样大小的起始内边距使用。 */
fun Modifier.bleedStart(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val widened = if (constraints.hasBoundedWidth) constraints.copy(minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra), maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(widened)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/** 加载态：延迟 250ms 再显示文字，避免快速请求时闪烁。 */
@Composable
fun LoadingPane(message: String = "正在加载…", modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); visible = true }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulseDots()
                Spacer(Modifier.height(14.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = TvColors.muted)
            }
        }
    }
}

@Composable
private fun PulseDots() {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(320); phase = (phase + 1) % 3 } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            Box(Modifier.size(10.dp).background(if (index == phase) TvColors.accent else TvColors.surfaceBright, MaterialTheme.shapes.extraSmall))
        }
    }
}

/** 失败态：说明 + 重试 + 返回；初始焦点落在重试。 */
@Composable
fun ErrorPane(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, extra: @Composable (() -> Unit)? = null) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(retryFocus) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(.7f)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = TvColors.amber, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(14.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = TvColors.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton("重试", onClick = onRetry, icon = Icons.Default.Refresh, modifier = Modifier.focusRequester(retryFocus))
                if (onBack != null) TvButton("返回", onClick = onBack, filled = false)
            }
            extra?.invoke()
        }
    }
}

/** 行内失败态：用于页面的一部分加载失败而其他部分仍可用的情况。 */
@Composable
fun InlineError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, onFix: (() -> Unit)? = null, fixLabel: String = "去配置") {
    Row(modifier.fillMaxWidth().padding(end = TvDimens.screenPaddingEnd, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = TvColors.amber, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TvColors.muted, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(14.dp))
        if (onFix != null) { TvButton(fixLabel, onClick = onFix); Spacer(Modifier.width(10.dp)) }
        TvButton("重试", onClick = onRetry, filled = false, icon = Icons.Default.Refresh)
    }
}

/** 空态：一句说明，可附带一个主操作。 */
@Composable
fun EmptyPane(message: String, modifier: Modifier = Modifier, actionLabel: String = "", onAction: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = TvColors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onAction != null && actionLabel.isNotBlank()) { Spacer(Modifier.height(18.dp)); TvButton(actionLabel, onClick = onAction) }
        }
    }
}

/** 统一按钮：填充式用于主操作，描边式用于次操作。 */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val container = when { danger -> TvColors.danger.copy(alpha = .18f); filled -> TvColors.surfaceBright; else -> Color.Transparent }
    val focusedContainer = when { danger -> TvColors.danger; else -> TvColors.accent }
    val focusedContent = if (danger) Color.White else TvColors.onAccent
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(containerColor = container, contentColor = if (danger) TvColors.danger else TvColors.onSurface, focusedContainerColor = focusedContainer, focusedContentColor = focusedContent, disabledContainerColor = TvColors.surfaceVariant, disabledContentColor = TvColors.muted),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** 段落说明文字，用于页面内提示。 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (emphasis) TvColors.onSurface else TvColors.muted, modifier = modifier.fillMaxWidth(.9f).padding(vertical = 6.dp))
}

/** 顶部渐变遮罩，让海报墙的大图与文字分层。 */
@Composable
fun BackdropScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.horizontalGradient(0f to TvColors.background, .45f to TvColors.background.copy(alpha = .92f), 1f to TvColors.background.copy(alpha = .35f))))
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to TvColors.background.copy(alpha = .35f), 1f to TvColors.background)))
}

/**
 * 页面容器的焦点恢复：第一次进入记下"已访问"；之后返回本页（路由状态仍保存）时，
 * 页面的初始焦点请求改由此容器的 focusRestorer 处理，回到上次聚焦的项。
 */
@Composable
fun Modifier.restoreFocusOnReentry(): Modifier {
    val group = remember { FocusRequester() }
    var visited by rememberSaveable { mutableStateOf(false) }
    // 记下本次组合开始时是否已访问过；效果按声明顺序先于页面内容的初始焦点请求执行。
    val reentered = remember { visited }
    androidx.compose.runtime.DisposableEffect(Unit) {
        if (reentered) tv.dandan.app.ui.FocusGate.restoreTarget = group
        visited = true
        onDispose { if (tv.dandan.app.ui.FocusGate.restoreTarget === group) tv.dandan.app.ui.FocusGate.restoreTarget = null }
    }
    return this.focusRequester(group).focusRestorer().focusGroup()
}

/** 列表顶部 28dp 渐隐：滚动时上一行被裁切的标题不会生硬地漂在标题区下方。 */
fun Modifier.topFade(height: androidx.compose.ui.unit.Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx()
        drawRect(brush = Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black, startY = 0f, endY = size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/**
 * 海报墙的焦点记忆：记住最后聚焦的行与卡片，返回页面时滚动到该行并聚焦同一张卡片。
 * 嵌套的 LazyRow 不能依赖外层 focusRestorer，因此显式保存。
 */
class WallFocus internal constructor(private val rowState: androidx.compose.runtime.MutableState<String?>, private val itemState: androidx.compose.runtime.MutableIntState) {
    private val requesters = mutableMapOf<String, FocusRequester>()
    fun requester(row: String): FocusRequester = requesters.getOrPut(row) { FocusRequester() }
    fun indexFor(row: String): Int = if (rowState.value == row) itemState.intValue else 0
    fun onFocused(row: String): (Int) -> Unit = { index -> rowState.value = row; itemState.intValue = index }
    /** 滚动到记住的行（不存在时用 [fallbackRow]）并请求焦点。 */
    suspend fun restore(list: androidx.compose.foundation.lazy.LazyListState, rowOrder: List<String>, fallbackRow: String) {
        val row = rowState.value?.takeIf { it in rowOrder } ?: fallbackRow
        val position = rowOrder.indexOf(row)
        if (position > 0) list.scrollToItem(position)
        androidx.compose.runtime.withFrameNanos { }
        tv.dandan.app.ui.requestContentFocus(requester(row))
    }
}

@Composable
fun rememberWallFocus(): WallFocus {
    val row = rememberSaveable { mutableStateOf<String?>(null) }
    val item = rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    return remember { WallFocus(row, item) }
}
