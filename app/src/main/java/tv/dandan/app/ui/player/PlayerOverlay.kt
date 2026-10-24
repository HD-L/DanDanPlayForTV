package tv.dandan.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.dandan.app.ui.components.PhoneFormDialog
import tv.dandan.app.ui.components.PhoneFormUiState
import tv.dandan.app.ui.components.ToastHost
import tv.dandan.app.ui.components.Toaster
import tv.dandan.app.ui.theme.DanDanTvTheme
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.core.PlayerUiRules
import kotlin.math.abs

private val Panel = Color(0xF2111821)
private val Track = Color.White.copy(alpha = .28f)
private val Buffered = Color.White.copy(alpha = .5f)

/**
 * 播放器叠加层，交互参照 YouTube TV：
 * - 隐藏时：确定键暂停/继续并呼出控制栏，左右键快进快退 10 秒（连按累加），按住右键临时 2 倍速，上下键呼出控制栏。
 * - 控制栏：顶部标题，底部进度条（左右键拖动预览、停手后跳转）、按钮行、向下展开的选集栏。
 * - 结束时下一集倒计时，可立即播放或取消；设置类选项从右侧面板展开。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerOverlay(ui: PlayerUi, actions: PlayerActions, toaster: Toaster, phoneForm: PhoneFormUiState) {
    DanDanTvTheme {
        val rootFocus = remember { FocusRequester() }
        val scrubFocus = remember { FocusRequester() }
        val dialogFocus = remember { FocusRequester() }
        val panelFocus = remember { FocusRequester() }
        val cardShown = ui.upNextSeconds != null || ui.finished
        val showControls = ui.controlsVisible && ui.menus.isEmpty() && !ui.blocking && !cardShown
        // 只有画面上没有任何控件时，方向键才是"快进快退/呼出控制栏"。
        val bareVideo = !showControls && ui.menus.isEmpty() && !ui.blocking && !cardShown
        // 焦点归属只由状态决定：失败/连播/结束卡片 > 侧边面板 > 控制栏进度条 > 隐藏态根节点。
        val focusTarget = when {
            ui.error != null && ui.phase == PlayerPhase.ERROR -> dialogFocus
            ui.upNextSeconds != null || ui.finished -> dialogFocus
            ui.menus.isNotEmpty() -> panelFocus
            showControls -> scrubFocus
            else -> rootFocus
        }
        LaunchedEffect(focusTarget, ui.focusSerial, ui.menus.size, ui.menus.lastOrNull()?.title) {
            repeat(20) { withFrameNanos { }; if (runCatching { focusTarget.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
        var lastRightDown by remember { mutableLongStateOf(0L) }
        Box(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { actions.interacted(); false }
                .focusRequester(rootFocus)
                .onKeyEvent { event -> if (!bareVideo) false else hiddenKeys(event, ui, actions, { lastRightDown }, { lastRightDown = it }) }
                .focusable(),
        ) {
            // 顶部与底部渐变：只在控制栏可见时出现，保证白字在任何画面上都可读。
            AnimatedVisibility(showControls, enter = fadeIn(tween(200)), exit = fadeOut(tween(260))) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent))))
                    val scrimHeight by animateDpAsState(if (ui.shelfOpen) 760.dp else 420.dp, tween(320), label = "scrim")
                    Box(Modifier.fillMaxWidth().height(scrimHeight).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .92f)))))
                }
            }
            AnimatedVisibility(showControls, enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { -it / 3 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(260)) { -it / 3 }) {
                TitleBlock(ui)
            }
            AnimatedVisibility(showControls, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(200)) + slideInVertically(tween(280)) { it / 4 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(280)) { it / 4 }) {
                BottomControls(ui, actions, scrubFocus)
            }
            // 隐藏态：快进快退提示 + 底部细进度条
            SeekFeedback(ui, visible = bareVideo)
            AnimatedVisibility(ui.boosting, modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp), enter = fadeIn() + scaleIn(initialScale = .8f), exit = fadeOut()) {
                Pill { Icon(PlayerIcons.FastForward, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("2 倍速播放中 · 松开恢复", color = Color.White, style = MaterialTheme.typography.labelLarge) }
            }
            if (ui.phase == PlayerPhase.LOADING || ui.phase == PlayerPhase.BUFFERING) LoadingIndicator(ui)
            if (ui.phase == PlayerPhase.ERROR && ui.error != null) ErrorCard(ui, actions, dialogFocus)
            if (ui.upNextSeconds != null) UpNextCard(ui, actions, dialogFocus)
            if (ui.finished && ui.upNextSeconds == null) FinishedCard(actions, dialogFocus)
            AnimatedVisibility(ui.menus.isNotEmpty(), modifier = Modifier.align(Alignment.CenterEnd), enter = slideInHorizontally(tween(240)) { it } + fadeIn(), exit = slideOutHorizontally(tween(200)) { it } + fadeOut()) {
                ui.menus.lastOrNull()?.let { SidePanel(it, actions, panelFocus) }
            }
            ToastHost(toaster)
            PhoneFormDialog(phoneForm)
        }
    }
}

/** 隐藏态按键：确定暂停并呼出、左右快进快退（右键长按临时 2 倍速）、上下呼出。 */
private fun hiddenKeys(event: androidx.compose.ui.input.key.KeyEvent, ui: PlayerUi, actions: PlayerActions, rightDown: () -> Long, setRightDown: (Long) -> Unit): Boolean {
    val repeat = event.nativeKeyEvent.repeatCount
    return when (event.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (event.type == KeyEventType.KeyDown && repeat == 0) { actions.toggle(); actions.showControls(true) }; true }
        Key.DirectionUp, Key.DirectionDown -> { if (event.type == KeyEventType.KeyDown) actions.showControls(true); true }
        Key.DirectionLeft -> { if (event.type == KeyEventType.KeyDown && repeat % 3 == 0) actions.seekBy(-PlayerUiRules.SEEK_STEP_MS); true }
        Key.DirectionRight -> {
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (repeat == 0) setRightDown(System.currentTimeMillis())
                    else if (!ui.boosting && ui.playing && (event.nativeKeyEvent.isLongPress || System.currentTimeMillis() - rightDown() > 450)) actions.boost(true)
                }
                KeyEventType.KeyUp -> if (ui.boosting) actions.boost(false) else actions.seekBy(PlayerUiRules.SEEK_STEP_MS)
            }
            true
        }
        else -> false
    }
}

@Composable
private fun TitleBlock(ui: PlayerUi) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 40.dp)) {
        Text(ui.title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (ui.detail.isNotBlank()) Text(ui.detail, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .72f), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BottomControls(ui: PlayerUi, actions: PlayerActions, scrubFocus: FocusRequester) {
    var shelfFocused by remember { mutableStateOf(false) }
    val shelfFocus = remember { FocusRequester() }
    val hasShelf = ui.episodes.size > 1
    // 选集栏平时只露出标题；焦点进入后整块控制区上移，让卡片完整显示（YouTube 的"向下看更多"）。
    val lift by animateDpAsState(if (shelfFocused || !hasShelf) 0.dp else 196.dp, tween(320), label = "lift")
    val playFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().offset(y = lift).padding(horizontal = 56.dp).padding(bottom = 28.dp)) {
        // 进度条向下固定落到播放/暂停键，而不是几何上最近的按钮。
        Scrubber(ui, actions, scrubFocus, Modifier.focusProperties { down = playFocus })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${PlayerUiRules.formatTime(ui.position)} / ${if (ui.duration > 0) PlayerUiRules.formatTime(ui.duration) else "--:--"}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            val meta = listOfNotNull(ui.networkLine, ui.engineLabel, if (ui.speed != 1f) "${trimSpeed(ui.speed)}×" else null, if (ui.danmakuCount >= 0 && ui.danmakuOn) "弹幕 ${ui.danmakuCount}" else null)
            Text(meta.joinToString(" · "), color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
            ControlButton(PlayerIcons.SkipPrevious, "上一集", enabled = ui.hasPrevious) { actions.playEpisode(ui.currentIndex - 1) }
            Spacer(Modifier.width(18.dp))
            ControlButton(if (ui.playing) PlayerIcons.Pause else PlayerIcons.Play, if (ui.playing) "暂停" else "播放", large = true, modifier = Modifier.focusRequester(playFocus)) { actions.toggle() }
            Spacer(Modifier.width(18.dp))
            ControlButton(PlayerIcons.SkipNext, "下一集", enabled = ui.hasNext) { actions.playEpisode(ui.currentIndex + 1) }
            Spacer(Modifier.weight(1f))
            if (hasShelf) { ControlButton(PlayerIcons.Episodes, "选集") { runCatching { shelfFocus.requestFocus() } }; Spacer(Modifier.width(18.dp)) }
            ControlButton(PlayerIcons.Subtitles, "字幕", active = ui.subtitleOn) { actions.subtitleMenu() }
            Spacer(Modifier.width(18.dp))
            ControlButton(PlayerIcons.Danmaku, if (ui.danmakuOn) "弹幕" else "弹幕已关", active = ui.danmakuOn) { actions.danmakuMenu() }
            Spacer(Modifier.width(18.dp))
            ControlButton(PlayerIcons.Speed, "倍速 ${trimSpeed(ui.speed)}×") { actions.speedMenu() }
            Spacer(Modifier.width(18.dp))
            ControlButton(Icons.Default.Settings, "更多设置") { actions.settingsMenu() }
        }
        if (hasShelf) EpisodeShelf(ui, actions, shelfFocus) { shelfFocused = it; ui.shelfOpen = it }
    }
}

private fun trimSpeed(speed: Float): String = if (speed % 1f == 0f) "%.0f".format(speed) else "%s".format(speed.toString().trimEnd('0'))

/** 进度条：获得焦点后变粗并出现拖动点；左右键移动预览位置（连续按加速），停手 0.6 秒后跳转；确定键暂停/继续。 */
@Composable
private fun Scrubber(ui: PlayerUi, actions: PlayerActions, focus: FocusRequester, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Long?>(null) }
    var repeat by remember { mutableIntStateOf(0) }
    var lastPress by remember { mutableLongStateOf(0L) }
    var serial by remember { mutableIntStateOf(0) }
    LaunchedEffect(serial) {
        val pending = target ?: return@LaunchedEffect
        delay(600); actions.seekTo(pending)
        delay(700); if (target == pending) target = null
    }
    val shown = target ?: ui.position
    val fraction = if (ui.duration > 0) (shown.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f
    val thickness by animateDpAsState(if (focused) 8.dp else 4.dp, tween(160), label = "track")
    val knob by animateDpAsState(if (focused) 18.dp else 0.dp, tween(160), label = "knob")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(56.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) repeat = 0 }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val now = System.currentTimeMillis()
                        repeat = if (now - lastPress < 700) repeat + 1 else 0
                        lastPress = now
                        val step = PlayerUiRules.scrubStep(ui.duration, repeat)
                        target = PlayerUiRules.clampTarget((target ?: ui.position) + if (event.key == Key.DirectionRight) step else -step, ui.duration)
                        serial++
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> { if (event.nativeKeyEvent.repeatCount == 0) actions.toggle(); true }
                    Key.DirectionUp -> true
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.BottomStart,
    ) {
        val width = maxWidth
        val density = LocalDensity.current
        // 拖动或聚焦时在拖动点上方显示目标时间
        if (focused || target != null) {
            val bubbleWidth = 96.dp
            val x = (width * fraction - bubbleWidth / 2).coerceIn(0.dp, width - bubbleWidth)
            Box(Modifier.offset(x = x, y = (-22).dp).width(bubbleWidth), contentAlignment = Alignment.Center) {
                Text(PlayerUiRules.formatTime(shown), color = if (target != null) TvColors.onAccent else Color.Black, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (target != null) TvColors.accent else Color.White).padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val h = with(density) { thickness.toPx() }
            val y = size.height / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Track, Offset(0f, y - h / 2), Size(size.width, h), r)
            drawRoundRect(Buffered, Offset(0f, y - h / 2), Size(size.width * (ui.bufferedPercent / 100f).coerceIn(0f, 1f), h), r)
            if (target != null) drawRoundRect(Color.White.copy(alpha = .9f), Offset(0f, y - h / 2), Size(size.width * (if (ui.duration > 0) ui.position.toFloat() / ui.duration else 0f).coerceIn(0f, 1f), h), r)
            drawRoundRect(TvColors.accent, Offset(0f, y - h / 2), Size(size.width * fraction, h), r)
            val k = with(density) { knob.toPx() }
            if (k > 0) drawCircle(TvColors.accent, k / 2, Offset(size.width * fraction, y))
            if (k > 0) drawCircle(Color.White, k / 2, Offset(size.width * fraction, y), style = Stroke(width = 3f))
        }
    }
}

/** 圆形控制按钮：聚焦时白底黑图标并放大，按钮下方淡入文字说明；[active] 用底部小圆点表示开启。 */
@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, large: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val size = if (large) 64.dp else 54.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(maxOf(size, 76.dp))) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = .14f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black, disabledContainerColor = Color.White.copy(alpha = .06f), disabledContentColor = Color.White.copy(alpha = .3f)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(if (large) 34.dp else 28.dp)) }
        }
        Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(if (active) TvColors.accent else Color.Transparent))
        val alpha by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "label")
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp).graphicsLayer { this.alpha = alpha })
    }
}

/** 选集栏：当前集高亮"正在播放"，看过的集显示进度条；焦点进入时默认落在当前集。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeShelf(ui: PlayerUi, actions: PlayerActions, focus: FocusRequester, onFocus: (Boolean) -> Unit) {
    val state = rememberLazyListState()
    val current = remember { FocusRequester() }
    LaunchedEffect(ui.currentIndex, ui.episodes.size) { state.scrollToItem((ui.currentIndex - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).onFocusChanged { onFocus(it.hasFocus) }) {
        Text("选集 · 共 ${ui.episodes.size} 个", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            modifier = Modifier.focusRequester(focus).focusRestorer(current)) {
            itemsIndexed(ui.episodes) { index, episode ->
                EpisodeCard(episode, playing = index == ui.currentIndex, modifier = if (index == ui.currentIndex) Modifier.focusRequester(current) else Modifier) {
                    if (index != ui.currentIndex) actions.playEpisode(index)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: EpisodeItem, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.width(248.dp).height(140.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xCC1C2736), focusedContainerColor = Color(0xFF2A3A50), contentColor = Color.White, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TvColors.accent), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Text(episode.label, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = if (playing) TvColors.accent else Color.White.copy(alpha = .85f))
            if (playing) Text("正在播放", style = MaterialTheme.typography.labelMedium, color = TvColors.onAccent, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(6.dp)).background(TvColors.accent).padding(horizontal = 8.dp, vertical = 2.dp))
            else if (episode.watched >= 1f) Text("已看完", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .7f), modifier = Modifier.align(Alignment.TopEnd))
            Text(episode.title, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp))
        }
        if (episode.watched > 0f) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Box(Modifier.fillMaxWidth(episode.watched).height(4.dp).background(TvColors.amber))
        }
    }
}

/** 隐藏态的快进快退反馈：屏幕一侧的圆形提示 + 底部细进度条，约 1 秒后淡出。 */
@Composable
private fun SeekFeedback(ui: PlayerUi, visible: Boolean) {
    val hint = ui.seekHint
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hint) { if (hint != null) { shown = true; delay(1100); shown = false } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible && shown && hint != null, modifier = Modifier.align(if ((hint?.deltaMs ?: 0) < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 120.dp), enter = fadeIn(tween(120)) + scaleIn(initialScale = .85f), exit = fadeOut(tween(300))) {
            val delta = hint?.deltaMs ?: 0
            Column(Modifier.size(150.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (delta < 0) PlayerIcons.FastRewind else PlayerIcons.FastForward, null, tint = Color.White, modifier = Modifier.size(44.dp))
                Text("${abs(delta) / 1000} 秒", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        AnimatedVisibility(visible && shown, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(120)), exit = fadeOut(tween(300))) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))).padding(horizontal = 56.dp, vertical = 28.dp)) {
                Text("${PlayerUiRules.formatTime(ui.position)} / ${PlayerUiRules.formatTime(ui.duration)}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Track)) {
                    Box(Modifier.fillMaxWidth(if (ui.duration > 0) (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(TvColors.accent))
                }
            }
        }
    }
}

@Composable
private fun LoadingIndicator(ui: PlayerUi) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val full = ui.phase == PlayerPhase.LOADING
    Box(Modifier.fillMaxSize().background(if (full) Color.Black.copy(alpha = .55f) else Color.Transparent), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(64.dp).graphicsLayer { rotationZ = angle }) {
                drawArc(Color.White.copy(alpha = .2f), 0f, 360f, false, style = Stroke(6.dp.toPx()))
                drawArc(TvColors.accent, 0f, 100f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
            if (full) Text(ui.loadingText, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 22.dp))
            ui.networkLine?.let { Text(it, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            ui.networkHint?.let { Text(it, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            if (full) Text("返回键退出", color = Color.White.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.width(620.dp).clip(RoundedCornerShape(20.dp)).background(Panel).padding(36.dp)) { content() }
}

@Composable
private fun DialogButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(26.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (primary) Color.White.copy(alpha = .22f) else Color.White.copy(alpha = .1f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ErrorCard(ui: PlayerUi, actions: PlayerActions, focus: FocusRequester) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
        Card {
            Column {
                Text("无法播放", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(ui.error.orEmpty(), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp, bottom = 26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DialogButton("重试", Icons.Default.Refresh, Modifier.focusRequester(focus), primary = true) { actions.retry() }
                    if (ui.canSwitchEngine) DialogButton("换内核重试", PlayerIcons.Tune) { actions.chooseEngine() }
                    DialogButton("退出", null) { actions.exit() }
                }
            }
        }
    }
}

/** 下一集倒计时：环形倒计时包住"立即播放"，确定键立刻播放，"取消"留在本集结尾。 */
@Composable
private fun UpNextCard(ui: PlayerUi, actions: PlayerActions, focus: FocusRequester) {
    val armed = rememberArmed()
    val next = ui.episodes.getOrNull(ui.currentIndex + 1)
    val seconds = ui.upNextSeconds ?: 0
    val progress by animateFloatAsState(seconds.toFloat() / PlayerUiRules.UP_NEXT_SECONDS, tween(950, easing = LinearEasing), label = "countdown")
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .25f), .45f to Color.Black.copy(alpha = .7f), 1f to Color.Black.copy(alpha = .92f))), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(560.dp).padding(end = 72.dp)) {
            Text("接下来播放", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.titleMedium)
            Text(next?.let { "${it.label} · ${it.title}" }.orEmpty(), color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(96.dp)) {
                        drawArc(Color.White.copy(alpha = .2f), -90f, 360f, false, style = Stroke(5.dp.toPx()))
                        drawArc(TvColors.accent, -90f, 360f * progress, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Surface(onClick = { if (armed()) actions.playUpNext() }, modifier = Modifier.size(76.dp).focusRequester(focus), shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = .2f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(PlayerIcons.Play, "立即播放", modifier = Modifier.size(40.dp)) }
                    }
                }
                Column {
                    Text("$seconds 秒后自动播放", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    DialogButton("取消", null) { if (armed()) actions.cancelUpNext() }
                }
            }
        }
    }
}

@Composable
private fun FinishedCard(actions: PlayerActions, focus: FocusRequester) {
    val armed = rememberArmed()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("已经播放完毕", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text("播放列表中没有下一项", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogButton("重新播放", PlayerIcons.Replay, Modifier.focusRequester(focus), primary = true) { if (armed()) actions.replay() }
                DialogButton("退出播放", null) { if (armed()) actions.exit() }
            }
        }
    }
}

/** 右侧设置面板：一页一组选项，当前值带勾；返回键回到上一页或关闭面板。 */
@Composable
private fun SidePanel(menu: PlayerMenu, actions: PlayerActions, focus: FocusRequester) {
    val state = rememberLazyListState()
    val start = menu.checked.coerceAtLeast(0)
    LaunchedEffect(menu) { state.scrollToItem((start - 2).coerceAtLeast(0)) }
    Column(Modifier.width(480.dp).fillMaxHeight().background(Panel).padding(top = 44.dp, start = 28.dp, end = 28.dp)) {
        Text(menu.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
        if (menu.subtitle.isNotBlank()) Text(menu.subtitle, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(menu.options) { index, option ->
                val shape = RoundedCornerShape(10.dp)
                Surface(
                    onClick = { actions.menuSelect(index) },
                    modifier = Modifier.fillMaxWidth().then(if (index == start) Modifier.focusRequester(focus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(shape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(32.dp)) { if (index == menu.checked) Icon(Icons.Default.Check, null, modifier = Modifier.size(22.dp)) }
                        Text(option, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * 卡片出现后的 0.6 秒内忽略点击：遥控器确定键在按下时触发上一个界面的动作、抬起时才触发点击，
 * 这段保护避免同一次按键"穿透"到新出现的卡片（例如刚切到下一集就被连播卡片的"立即播放"再跳一集）。
 */
@Composable
private fun rememberArmed(): () -> Boolean {
    val shownAt = remember { System.currentTimeMillis() }
    return { System.currentTimeMillis() - shownAt > 600 }
}
