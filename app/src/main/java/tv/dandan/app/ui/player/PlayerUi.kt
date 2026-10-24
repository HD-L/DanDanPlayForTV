package tv.dandan.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 选集栏里的一集。[watched] 为 -1 表示没有记录，1 表示已看完。 */
data class EpisodeItem(val label: String, val title: String, val watched: Float)

/** 侧边面板中的一页选项；[checked] 为当前生效项，没有时为 -1。 */
class PlayerMenu(val title: String, val options: List<String>, val checked: Int, val subtitle: String, val onSelect: (Int) -> Unit)

/** 隐藏态快进快退的提示；[serial] 让同值的连续提示也能重新触发动画。 */
data class SeekHint(val deltaMs: Long, val serial: Int)

enum class PlayerPhase { LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * 播放器叠加层的全部可观察状态。由 PlayerActivity 在主线程写入，Compose 只读并通过 [PlayerActions] 回调。
 */
class PlayerUi {
    var title by mutableStateOf("")
    var detail by mutableStateOf("")
    var phase by mutableStateOf(PlayerPhase.LOADING)
    var loadingText by mutableStateOf("正在打开媒体…")
    var networkLine by mutableStateOf<String?>(null)
    /** 网络跟不上码率时缓冲画面下方的说明。 */
    var networkHint by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var bufferedPercent by mutableIntStateOf(0)
    var speed by mutableFloatStateOf(1f)
    var engineLabel by mutableStateOf("Media3")
    var danmakuOn by mutableStateOf(true)
    var danmakuCount by mutableIntStateOf(-1)
    var subtitleOn by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var canSwitchEngine by mutableStateOf(false)

    var episodes by mutableStateOf<List<EpisodeItem>>(emptyList())
    var currentIndex by mutableIntStateOf(0)
    val hasPrevious get() = currentIndex > 0
    val hasNext get() = currentIndex + 1 < episodes.size

    var controlsVisible by mutableStateOf(true)
    /** 选集栏展开（焦点在选集卡片上）时为 true；播放器据此暂时隐藏字幕，避免与卡片重叠。 */
    var shelfOpen by mutableStateOf(false)
    /** 每次需要把焦点放回进度条时递增（显示控制栏、关闭面板）。 */
    var focusSerial by mutableIntStateOf(0)
    var seekHint by mutableStateOf<SeekHint?>(null)
    var boosting by mutableStateOf(false)
    /** 自动连播倒计时剩余秒数；null 表示没有倒计时。 */
    var upNextSeconds by mutableStateOf<Int?>(null)
    /** 播放结束且没有下一集时显示"播放结束"卡片。 */
    var finished by mutableStateOf(false)
    val menus = mutableStateListOf<PlayerMenu>()

    val playing get() = phase == PlayerPhase.PLAYING
    val blocking get() = phase == PlayerPhase.LOADING || phase == PlayerPhase.ERROR
}

/** 叠加层发出的用户意图，由 PlayerActivity 执行。 */
interface PlayerActions {
    fun toggle()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun playEpisode(index: Int)
    fun subtitleMenu()
    fun danmakuMenu()
    fun speedMenu()
    fun settingsMenu()
    fun retry()
    fun chooseEngine()
    fun exit()
    fun playUpNext()
    fun cancelUpNext()
    fun replay()
    fun menuSelect(index: Int)
    fun boost(active: Boolean)
    /** 任意按键都会调用，用于推迟自动隐藏。 */
    fun interacted()
    fun showControls(show: Boolean)
}
