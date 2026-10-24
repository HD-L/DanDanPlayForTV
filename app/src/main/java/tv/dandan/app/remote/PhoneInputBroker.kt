package tv.dandan.app.remote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.json.JSONArray
import org.json.JSONObject
import tv.dandan.app.data.AppRepository
import java.util.concurrent.Executors

/** 表单在电视上的呈现方式：Compose 页面与 View 播放器各自实现。 */
interface PhoneFormPresenter {
    fun show(title: String, onCancel: () -> Unit)
    fun ready(url: String, qr: Bitmap)
    fun failed(message: String)
    fun dismiss()
}

/** 输入会话只在主线程处理，配对服务和二维码准备在后台完成。 */
object PhoneInputBroker {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Pending(
        val owner: Any,
        val activity: Activity,
        val session: PhoneFormSession,
        val presenter: PhoneFormPresenter,
        val validator: (Map<String, String>) -> String?,
        val accepted: (Map<String, String>) -> Unit,
        val cancelled: () -> Unit,
        var expiry: Runnable? = null,
    )
    private var pending: Pending? = null
    fun initialize(context: Context, repository: AppRepository) {
        worker.execute { runCatching { RemoteHub.server(context, repository).start() } }
    }

    /** View 版呈现（播放器使用）：返回对话框以便调用方管理焦点。 */
    fun request(
        activity: Activity,
        repository: AppRepository,
        owner: Any,
        form: PhoneForm,
        validator: (Map<String, String>) -> String? = { null },
        onSubmit: (Map<String, String>) -> Unit,
        onCancel: () -> Unit = {},
    ): AlertDialog {
        val presenter = DialogPresenter(activity)
        request(activity, repository, owner, form, presenter, validator, onSubmit, onCancel)
        return presenter.dialog
    }

    fun request(
        activity: Activity,
        repository: AppRepository,
        owner: Any,
        form: PhoneForm,
        presenter: PhoneFormPresenter,
        validator: (Map<String, String>) -> String? = { null },
        onSubmit: (Map<String, String>) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        require(Looper.myLooper() == Looper.getMainLooper()) { "输入请求必须来自电视主线程" }
        cancelActive()
        val session = PhoneFormSession(form, nowMs = SystemClock::elapsedRealtime)
        val current = Pending(owner, activity, session, presenter, validator, onSubmit, onCancel)
        pending = current
        presenter.show(form.title) {
            if (pending === current) {
                pending = null; current.expiry?.let(main::removeCallbacks); current.session.cancel(); current.cancelled()
            }
        }
        worker.execute {
            try {
                val url = RemoteHub.server(activity.applicationContext, repository).inputUrl(session.id)
                val bits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.MARGIN to 2))
                val pixels = IntArray(320 * 320) { index -> if (bits[index % 320, index / 320]) Color.BLACK else Color.WHITE }
                val bitmap = Bitmap.createBitmap(pixels, 320, 320, Bitmap.Config.ARGB_8888)
                main.post { if (pending === current && !activity.isFinishing && !activity.isDestroyed) presenter.ready(url, bitmap) }
            } catch (_: Exception) { main.post { if (pending === current) presenter.failed("无法开启手机输入服务，请取消后重试并检查局域网连接") } }
        }
        current.expiry = Runnable { if (pending === current && !session.isOpen()) cancelActive() }
        main.postDelayed(current.expiry!!, 600_000)
    }
    fun cancelOwner(owner: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancelOwner(owner) }; return }
        if (pending?.owner === owner) cancelActive()
    }
    fun serviceStopped() { main.post { cancelActive() } }
    /** 页面宿主进入后台或用户按返回时取消当前待输入。 */
    fun cancelAll() { if (Looper.myLooper() != Looper.getMainLooper()) main.post { cancelActive() } else cancelActive() }
    private fun cancelActive() {
        val current = pending ?: return
        pending = null
        current.expiry?.let(main::removeCallbacks)
        current.session.cancel(); current.presenter.dismiss(); current.cancelled()
    }
    internal fun snapshot(requestedId: String? = null): JSONObject? {
        val current = pending ?: return null
        if (requestedId != null && current.session.id != requestedId) return null
        if (!current.session.isOpen() || current.activity.isFinishing || current.activity.isDestroyed) { cancelActive(); return null }
        return JSONObject().put("id", current.session.id).put("title", current.session.form.title).put("hint", current.session.form.hint)
            .put("fields", JSONArray(current.session.fields().map { field ->
                JSONObject().put("key", field.key).put("label", field.label).put("value", field.value).put("secret", field.secret)
                    .put("multiline", field.multiline).put("required", field.required).put("maxLength", field.maxLength).put("hasStoredValue", field.hasStoredValue)
                    .put("options", JSONArray(field.options.map { option -> JSONObject().put("value", option.value).put("label", option.label) }))
                    .put("showWhen", field.showWhen?.let { condition -> JSONObject().put("key", condition.key).put("values", JSONArray(condition.values.toList())) } ?: JSONObject.NULL)
            }))
    }
    internal fun submit(body: JSONObject): JSONObject {
        val current = pending ?: throw IllegalArgumentException("电视当前没有等待输入，请先在电视选择手机输入")
        val values = body.getJSONObject("values")
        val fields = values.keys().asSequence().associateWith { key -> values.get(key).also { require(it is String) { "表单字段必须是文字" } }.toString() }
        val clear = body.optJSONArray("clearSecrets")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
        val accepted = current.session.submit(body.getString("id"), fields, clear, current.validator)
        pending = null
        current.expiry?.let(main::removeCallbacks)
        current.presenter.dismiss()
        current.accepted(accepted)
        return JSONObject().put("success", true).put("errorCode", 0).put("accepted", true)
    }
    internal fun cancel(requestId: String): JSONObject {
        require(pending?.session?.id == requestId) { "这次输入已经结束" }
        cancelActive()
        return JSONObject().put("success", true).put("errorCode", 0)
    }

    /** 播放器沿用的系统对话框呈现。 */
    private class DialogPresenter(private val activity: Activity) : PhoneFormPresenter {
        private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        private val qr = ImageView(activity).apply { isFocusable = false; contentDescription = "手机扫描二维码输入" }
        private val address = TextView(activity).apply { text = "正在准备手机输入…"; textSize = 13f; setTextColor(0xffb5c9d5.toInt()); setPadding(0, dp(10), 0, 0) }
        val dialog: AlertDialog
        init {
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(12), dp(24), dp(8)) }
            val message = TextView(activity).apply { text = "手机扫描本次操作的专属二维码填写，提交后电视自动继续。\n此地址只用于本次操作；返回可取消输入。"; textSize = 17f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(12)) }
            layout.addView(message)
            layout.addView(qr, LinearLayout.LayoutParams(dp(210), dp(210)))
            layout.addView(address)
            dialog = AlertDialog.Builder(activity).setView(layout).setNegativeButton("取消输入", null).create()
        }
        override fun show(title: String, onCancel: () -> Unit) {
            dialog.setTitle("在手机填写 · $title")
            dialog.setOnDismissListener { onCancel() }
            dialog.show()
            dialog.window?.setLayout(minOf(dp(700), (activity.resources.displayMetrics.widthPixels * .9).toInt()), -2)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
        override fun ready(url: String, qr: Bitmap) { this.qr.setImageBitmap(qr); address.text = url }
        override fun failed(message: String) { address.text = message }
        override fun dismiss() { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
