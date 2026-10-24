package tv.dandan.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面数据的三种状态。失败信息已经是可直接展示的中文。 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** 带重试与手动刷新的异步读取句柄。 */
class Loader<T> internal constructor(private val state: State<Load<T>>, private val bump: () -> Unit) {
    val value: Load<T> get() = state.value
    fun reload() = bump()
}

/**
 * 在 IO 线程执行 [work]；[keys] 变化或调用 reload 时重新读取。
 * 结果只在当前组合仍存活时写回，离开页面即自动取消。已有内容时的重新读取在后台进行，期间保留旧内容。
 */
@Composable
fun <T> rememberLoader(vararg keys: Any?, work: suspend () -> T): Loader<T> {
    var generation by remember { mutableIntStateOf(0) }
    val state = remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(generation, *keys) {
        // 已有内容时在后台刷新（例如从播放器返回、refreshEpoch 递增），保留旧内容直到新数据到达：
        // 页面不会闪成加载态，聚焦的条目也不会被移除，焦点不会掉到左侧导航。
        if (state.value !is Load.Ready) state.value = Load.Loading
        state.value = try { Load.Ready(withContext(Dispatchers.IO) { work() }) } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; Load.Failed(e.message ?: "操作失败，请检查连接或配置后重试") }
    }
    return remember { Loader(state) { generation++ } }
}

/** 页面内的一次性后台操作（保存、删除、下载），完成后在主线程回调。 */
class Actions(private val scope: CoroutineScope) {
    private var running: Job? = null
    val busy: Boolean get() = running?.isActive == true
    fun <T> run(work: suspend () -> T, failure: (String) -> Unit = {}, success: (T) -> Unit) {
        if (busy) return
        running = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work() } }
            result.fold(success) { failure(it.message ?: "操作失败，请检查连接或配置后重试") }
        }
    }
}

@Composable
fun rememberActions(): Actions {
    val scope = rememberCoroutineScope()
    return remember { Actions(scope) }
}
