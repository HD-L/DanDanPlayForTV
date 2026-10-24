package tv.dandan.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * 内容区是否应接管初始焦点。左侧导航因用户操作获得焦点时为 false：
 * 此时切换栏目只换内容，不把焦点从导航上抢走；用户按右键进入内容后恢复为 true。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
object FocusGate {
    /** 内容区容器；向左离开时保存聚焦项，从导航栏按右键时恢复。 */
    val contentGroup = FocusRequester()
    /** 向左离开内容区时所在页面的 key；只有回到同一页面时才恢复当时的聚焦项。 */
    private var savedRouteKey: String? = null
    /** 导航栏当前是否持有焦点（不受 [pendingContentFocus] 影响），用于确认焦点真的进入了内容区。 */
    var railFocused = false
        private set

    fun leaveContent(routeKey: String) {
        savedRouteKey = if (runCatching { contentGroup.saveFocusedChild() }.getOrDefault(false)) routeKey else null
    }

    /** 每个已组合页面的焦点组（含退场动画中的旧页面）。 */
    val routeGroups = mutableMapOf<String, FocusRequester>()
    /** 焦点当前所在页面的 key；导航栏获得焦点时清空。 */
    var focusedRouteKey: String? = null

    /**
     * 从导航栏进入内容区：同一页面优先回到离开时的项，其次页面声明的初始项，最后交给页面焦点组按默认顺序进入。
     * 每一步都确认焦点确实落在当前页面：切栏目后旧页面保存的项、旧页面迟到登记的初始项，
     * 都可能"请求成功"却落在正在退场的旧页面上，随后旧页面被移除，焦点又掉回导航栏。
     */
    fun enterContent(routeKey: String): Boolean {
        contentWanted = true
        fun landed() = !railFocused && focusedRouteKey == routeKey
        if (savedRouteKey == routeKey && runCatching { contentGroup.restoreFocusedChild() }.getOrDefault(false) && landed()) return true
        pageInitial?.let { if (runCatching { it.requestFocus() }.getOrDefault(false) && landed()) return true }
        routeGroups[routeKey]?.let { if (runCatching { it.requestFocus() }.getOrDefault(false) && landed()) return true }
        return false
    }
    var contentWanted by mutableStateOf(true)
    /** 内容区是否已经成功获得过焦点；启动时系统先把焦点给导航栏，这不算用户意图。 */
    var contentEverFocused = false
    /** 返回页面时用于恢复焦点的容器；由页面骨架在重新进入时设置，被下一次初始焦点请求消费。 */
    var restoreTarget: FocusRequester? = null
    /** 当前页面声明的初始焦点；从导航栏按右键进入内容区且没有可恢复项时以它为准，而不是几何最近的项。 */
    var pageInitial by mutableStateOf<FocusRequester?>(null)
    /**
     * 从内容区发起的页面切换进行中：旧页面被移除时系统会把焦点临时交给导航栏，
     * 这段时间内导航栏的焦点变化不代表用户意图，不切换栏目，新页面的初始焦点照常生效。
     */
    var pendingContentFocus by mutableStateOf(false)
    fun railFocusChanged(hasFocus: Boolean) {
        railFocused = hasFocus
        if (hasFocus) focusedRouteKey = null
        if (pendingContentFocus) return
        if (hasFocus) { if (contentEverFocused) contentWanted = false } else contentWanted = true
    }
}

/**
 * 页面请求初始焦点的统一入口；导航栏持有焦点（用户在浏览栏目）时忽略。
 * 返回到已访问过的页面时，页面骨架会登记 [FocusGate.restoreTarget]（带 focusRestorer 的容器），
 * 此时把请求转给容器，由它恢复到上次聚焦的行，而不是跳回第一项。
 * 目标可能要等数据加载完才出现在界面上，所以在约 2 秒内逐帧重试，页面离开时随协程一起取消。
 */
suspend fun requestContentFocus(requester: FocusRequester) {
    FocusGate.pageInitial = requester
    repeat(40) {
        if (!FocusGate.contentWanted && !FocusGate.pendingContentFocus) return
        val restore = FocusGate.restoreTarget
        val ok = (restore != null && runCatching { restore.requestFocus() }.getOrDefault(false)) || runCatching { requester.requestFocus() }.getOrDefault(false)
        if (ok) {
            if (restore != null) FocusGate.restoreTarget = null
            FocusGate.contentEverFocused = true; FocusGate.pendingContentFocus = false; FocusGate.contentWanted = true
            return
        }
        kotlinx.coroutines.delay(50)
    }
}
