package tv.dandan.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.SourceRules
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.remote.PhoneInputBroker
import tv.dandan.app.ui.Navigator
import tv.dandan.app.ui.TvApp
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.PhoneFormUiState
import tv.dandan.app.ui.components.Toaster
import tv.dandan.core.MediaEntry
import tv.dandan.core.MediaSource
import tv.dandan.core.QueueWindow
import tv.dandan.core.SourceKind
import java.util.UUID
import java.util.concurrent.Executors

/** 宿主 Activity：持有仓库与导航状态，向 Compose 页面提供系统选择器、播放与手机表单。 */
class MainActivity : ComponentActivity(), TvHost {
    companion object {
        const val INVALID_VIDEO_URL = "视频地址无效，请使用不含内嵌账号密码或片段标记的 HTTP / HTTPS 地址"
    }
    override lateinit var repository: AppRepository
        private set
    override val appContext: Context get() = applicationContext
    override lateinit var navigator: Navigator
        private set
    override var refreshEpoch by mutableIntStateOf(0)
        private set
    private val toaster = Toaster()
    private val phoneFormUi = PhoneFormUiState()
    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    private val railFocus = FocusRequester()
    private var railHasFocus = false
    private data class DocumentRequest(val callback: (Uri) -> Unit, val cancelled: () -> Unit, val persistent: Boolean)
    private var documentRequest: DocumentRequest? = null
    private var returningFromPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(applicationContext)
        navigator = Navigator(0)
        PhoneInputBroker.initialize(applicationContext, repository)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            BackHandler { back() }
            TvApp(this, toaster, railFocus, phoneFormUi, onRailFocus = { railHasFocus = it })
        }
    }

    /**
     * 返回键：先退子页面；栏目根页上，内容区有焦点时把焦点交给左侧导航，
     * 导航上再按返回回到首页，首页再按返回退出应用。
     */
    private fun back() {
        when {
            navigator.pop() -> Unit
            !railHasFocus -> if (!runCatching { railFocus.requestFocus() }.getOrDefault(false)) { if (navigator.tab == 0) finish() else navigator.switchTab(0) }
            navigator.tab == 0 -> finish()
            else -> navigator.switchTab(0, fromRail = true)
        }
    }

    override fun <T> background(work: () -> T, result: (Result<T>) -> Unit) {
        executor.execute {
            val outcome = runCatching(work)
            handler.post { if (!isFinishing && !isDestroyed) result(outcome) }
        }
    }

    override fun notify(message: String) { toaster.show(message) }
    override fun finishApp() = finish()

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun play(entry: MediaEntry, queue: List<MediaEntry>) {
        val selection = runCatching { QueueWindow.select(entry, queue) }.getOrElse { notify(it.message ?: "无法准备播放队列"); return }
        if (selection.truncated) notify("队列仅包含当前附近项目；其他视频可返回媒体库选择")
        returningFromPlayer = true
        PlayerActivity.open(this, entry, selection.entries)
    }

    override fun openHttpVideo(location: String, title: String): Boolean {
        val source = runCatching { SourceRules.validate(MediaSource("direct-video", title, SourceKind.HTTP, location)) }.getOrNull() ?: return false
        val id = UUID.nameUUIDFromBytes(source.location.toByteArray(Charsets.UTF_8))
        play(MediaEntry("url:$id", title, source.location))
        return true
    }

    override fun phoneForm(owner: Any, form: PhoneForm, validator: (Map<String, String>) -> String?, onCancel: () -> Unit, onSubmit: (Map<String, String>) -> Unit) {
        PhoneInputBroker.request(this, repository, owner, form, phoneFormUi, validator, onSubmit = { values -> if (!isFinishing && !isDestroyed) onSubmit(values) }, onCancel = onCancel)
    }
    override fun cancelPhoneForm(owner: Any) = PhoneInputBroker.cancelOwner(owner)

    override fun pickDirectory(cancelled: () -> Unit, callback: (Uri) -> Unit) = pick(Intent.ACTION_OPEN_DOCUMENT_TREE, null, cancelled = cancelled, callback = callback)
    override fun pickFile(mime: String, cancelled: () -> Unit, callback: (Uri) -> Unit) = pick(Intent.ACTION_OPEN_DOCUMENT, mime, cancelled = cancelled, callback = callback)
    override fun pickBackupFile(cancelled: () -> Unit, callback: (Uri) -> Unit) = pick(Intent.ACTION_OPEN_DOCUMENT, "*/*", persistent = false, cancelled = cancelled, callback = callback)
    override fun createBackupFile(filename: String, cancelled: () -> Unit, callback: (Uri) -> Unit) = pick(Intent.ACTION_CREATE_DOCUMENT, "application/json", filename, false, cancelled, callback)
    private fun pick(action: String, mime: String?, filename: String? = null, persistent: Boolean = true, cancelled: () -> Unit = {}, callback: (Uri) -> Unit) {
        if (documentRequest != null) { notify("请先完成电视上的系统文件选择"); return }
        documentRequest = DocumentRequest(callback, cancelled, persistent)
        val request = Intent(action).apply {
            if (mime != null) { type = mime; addCategory(Intent.CATEGORY_OPENABLE) }
            if (filename != null) putExtra(Intent.EXTRA_TITLE, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (action == Intent.ACTION_CREATE_DOCUMENT) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try { @Suppress("DEPRECATION") startActivityForResult(request, 41) }
        catch (_: Exception) { documentRequest = null; notify("此设备没有可用的系统文件选择器"); cancelled() }
    }

    @Deprecated("系统文件选择器兼容入口")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        val request = documentRequest.also { documentRequest = null } ?: return
        if (isFinishing || isDestroyed) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { request.cancelled(); return }
        try {
            if (request.persistent) contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            request.callback(uri)
            refreshEpoch++
        } catch (_: SecurityException) { notify("无法保留读取权限，请重新选择文件或目录"); request.cancelled() }
    }

    override fun onResume() {
        super.onResume()
        if (returningFromPlayer) { returningFromPlayer = false; refreshEpoch++ }
    }
    override fun onStop() { PhoneInputBroker.cancelAll(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }
}
