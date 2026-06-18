package com.xyoye.player_component.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.data_component.enums.VideoScreenScale
import com.xyoye.player.DanDanVideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 控制栏功能项（顺序即左侧图标行的顺序） */
private enum class TvBarAction { PLAY_PAUSE, PREV, NEXT, DANMU, SPEED, SCALE, SETTING }

/** 覆盖层可观察状态，由 [TvPlayerController] 驱动 */
class TvPlayerUiState {
    var visible by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var bufferedPercent by mutableStateOf(0)
    var title by mutableStateOf("")
    var speed by mutableStateOf(1f)
    var selected by mutableStateOf(0)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var hint by mutableStateOf<String?>(null)
}

/**
 * TV 播放控制器：拦截 D-pad 驱动 [TvPlayerUiState] 与 [DanDanVideoPlayer]，
 * 与旧 View 版控制器互斥（消费按键后旧控制器不再弹出）。
 */
class TvPlayerController(
    private val state: TvPlayerUiState,
    private val player: DanDanVideoPlayer,
    private val scope: CoroutineScope,
    private val onSwitchEpisode: (Int) -> Unit,
    private val isSettingShowing: () -> Boolean = { false },
    private val onOpenSettings: () -> Unit = {},
    private val onToggleDanmu: () -> Unit = {}
) {
    private var danmuOn = true
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    private val scales = VideoScreenScale.values()
    private var scaleIndex = 0
    private val actionCount = TvBarAction.values().size

    private var hideJob: Job? = null
    private var hintJob: Job? = null
    private var syncJob: Job? = null

    fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                refresh()
                delay(500)
            }
        }
    }

    fun refresh() {
        // 播放器内核(mVideoPlayer)在 setSource 后才初始化，提前轮询会抛 UninitializedPropertyAccessException
        runCatching {
            state.playing = player.isPlaying()
            state.position = player.getCurrentPosition()
            state.duration = player.getDuration()
            state.bufferedPercent = player.getBufferedPercentage()
            state.speed = player.getSpeed()
        }
        runCatching { player.getVideoSource() }.getOrNull()?.let { src: BaseVideoSource ->
            state.title = src.getVideoTitle()
            state.hasPrev = src.hasPreviousSource()
            state.hasNext = src.hasNextSource()
        }
    }

    /** @return true 表示已消费该按键 */
    fun handleKey(keyCode: Int): Boolean {
        // 现有设置面板(弹幕轨/关键词屏蔽等)显示时，让出按键给它处理
        if (isSettingShowing()) {
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!state.visible) show() else activate()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (state.visible) move(-1) else seekBy(-10_000)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (state.visible) move(1) else seekBy(10_000)
            }
            KeyEvent.KEYCODE_DPAD_UP -> show()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (state.visible) hide() else show()
            KeyEvent.KEYCODE_MENU -> {
                hide()
                onOpenSettings()
            }
            else -> return false
        }
        return true
    }

    /** @return true 表示拦截了返回键（隐藏控制栏） */
    fun onBack(): Boolean {
        if (state.visible) {
            hide()
            return true
        }
        return false
    }

    private fun show() {
        if (!state.visible) {
            state.visible = true
            state.selected = 0
        }
        restartHide()
    }

    private fun hide() {
        state.visible = false
        hideJob?.cancel()
    }

    private fun restartHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(5000)
            state.visible = false
        }
    }

    private fun move(delta: Int) {
        state.selected = (state.selected + delta).coerceIn(0, actionCount - 1)
        restartHide()
    }

    private fun activate() {
        when (TvBarAction.values()[state.selected]) {
            TvBarAction.PLAY_PAUSE -> togglePlay()
            TvBarAction.PREV -> if (state.hasPrev) onSwitchEpisode(-1)
            TvBarAction.NEXT -> if (state.hasNext) onSwitchEpisode(1)
            TvBarAction.DANMU -> {
                danmuOn = !danmuOn
                onToggleDanmu()
                flashHint(if (danmuOn) "弹幕已开启" else "弹幕已关闭")
            }
            TvBarAction.SPEED -> cycleSpeed()
            TvBarAction.SCALE -> cycleScale()
            TvBarAction.SETTING -> {
                hide()
                onOpenSettings()
                return
            }
        }
        restartHide()
        refresh()
    }

    private fun togglePlay() {
        runCatching {
            if (player.isPlaying()) player.pause() else player.resume()
        }
        refresh()
    }

    private fun seekBy(deltaMs: Long) {
        runCatching {
            val duration = player.getDuration()
            val target = (player.getCurrentPosition() + deltaMs).coerceIn(0L, duration)
            player.seekTo(target)
            state.position = target
            flashHint("${formatTime(target)} / ${formatTime(duration)}")
        }
    }

    private fun cycleSpeed() {
        runCatching {
            val current = player.getSpeed()
            val idx = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }.takeIf { it >= 0 } ?: 2
            val next = speeds[(idx + 1) % speeds.size]
            player.setSpeed(next)
            PlayerConfig.putNewVideoSpeed(next)
            state.speed = next
            flashHint("倍速 ${formatSpeed(next)}")
        }
    }

    private fun cycleScale() {
        runCatching {
            scaleIndex = (scaleIndex + 1) % scales.size
            val scale = scales[scaleIndex]
            player.setScreenScale(scale)
            flashHint(scaleName(scale))
        }
    }

    private fun flashHint(text: String) {
        state.hint = text
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(1200)
            state.hint = null
        }
    }
}

@Composable
fun TvPlayerControlOverlay(state: TvPlayerUiState) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.fillMaxSize()) {
            state.hint?.let { hint ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(text = hint, color = Color.White, fontSize = 16.sp)
                }
            }

            if (state.visible) {
                ControlPanel(state, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun ControlPanel(state: TvPlayerUiState, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))))
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = state.title.ifEmpty { "正在播放" },
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        ProgressBar(position = state.position, duration = state.duration, buffered = state.bufferedPercent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvBarAction.values().forEachIndexed { index, action ->
                    ActionIcon(action = action, state = state, selected = index == state.selected)
                }
            }
            Text(
                text = "${formatTime(state.position)}  ·  ${formatTime(state.duration)}",
                color = Color.White,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ActionIcon(action: TvBarAction, state: TvPlayerUiState, selected: Boolean) {
    val enabled = when (action) {
        TvBarAction.PREV -> state.hasPrev
        TvBarAction.NEXT -> state.hasNext
        else -> true
    }
    val icon: ImageVector = when (action) {
        TvBarAction.PLAY_PAUSE -> if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
        TvBarAction.PREV -> Icons.Filled.SkipPrevious
        TvBarAction.NEXT -> Icons.Filled.SkipNext
        TvBarAction.DANMU -> Icons.Filled.Comment
        TvBarAction.SPEED -> Icons.Filled.Speed
        TvBarAction.SCALE -> Icons.Filled.AspectRatio
        TvBarAction.SETTING -> Icons.Filled.Settings
    }
    val tint = when {
        selected -> Color.Black
        !enabled -> Color(0x55FFFFFF)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        if (action == TvBarAction.SPEED && (selected || state.speed != 1f)) {
            Text(text = formatSpeed(state.speed), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long, buffered: Int) {
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = (buffered / 100f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x44FFFFFF))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(bufFrac)
                .fillMaxHeight()
                .background(Color(0x66FFFFFF))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF3F8CFF))
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatSpeed(speed: Float): String {
    return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
}

private fun scaleName(scale: VideoScreenScale): String = when (scale) {
    VideoScreenScale.SCREEN_SCALE_DEFAULT -> "默认比例"
    VideoScreenScale.SCREEN_SCALE_16_9 -> "16:9"
    VideoScreenScale.SCREEN_SCALE_4_3 -> "4:3"
    VideoScreenScale.SCREEN_SCALE_MATCH_PARENT -> "填充"
    VideoScreenScale.SCREEN_SCALE_ORIGINAL -> "原始大小"
    VideoScreenScale.SCREEN_SCALE_CENTER_CROP -> "裁剪填充"
}
