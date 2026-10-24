package tv.dandan.app.ui

import android.content.Context
import android.net.Uri
import tv.dandan.app.data.AppRepository
import tv.dandan.app.remote.PhoneForm
import tv.dandan.core.MediaEntry

/**
 * 页面与宿主 Activity 之间的边界。页面只通过这里播放视频、拉起系统选择器、
 * 请求手机表单和提示用户；协议、文件解析与持久化都在 data 层。
 */
interface TvHost {
    val appContext: Context
    val repository: AppRepository
    val navigator: Navigator
    /** 从播放器返回或系统选择器完成后递增，依赖观看记录的页面以此重新读取。 */
    val refreshEpoch: Int
    fun play(entry: MediaEntry, queue: List<MediaEntry> = listOf(entry))
    fun openHttpVideo(location: String, title: String = "网络视频"): Boolean
    fun notify(message: String)
    fun pickDirectory(cancelled: () -> Unit = {}, callback: (Uri) -> Unit)
    fun pickFile(mime: String = "*/*", cancelled: () -> Unit = {}, callback: (Uri) -> Unit)
    fun pickBackupFile(cancelled: () -> Unit = {}, callback: (Uri) -> Unit)
    fun createBackupFile(filename: String, cancelled: () -> Unit = {}, callback: (Uri) -> Unit)
    /** 发起手机表单；成功回调只在表单仍属于当前页面时触发。 */
    fun phoneForm(owner: Any, form: PhoneForm, validator: (Map<String, String>) -> String? = { null }, onCancel: () -> Unit = {}, onSubmit: (Map<String, String>) -> Unit)
    fun cancelPhoneForm(owner: Any)
    /** 在后台线程执行并把结果投递到主线程；Activity 销毁后不再回调。 */
    fun <T> background(work: () -> T, result: (Result<T>) -> Unit)
    fun finishApp()
}
