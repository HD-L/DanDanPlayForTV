package tv.dandan.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import tv.dandan.app.remote.PhoneFormPresenter
import tv.dandan.app.ui.theme.TvColors

/** 手机表单弹窗的可观察状态；由 [PhoneInputBroker] 通过 [PhoneFormPresenter] 驱动。 */
class PhoneFormUiState : PhoneFormPresenter {
    var title by mutableStateOf<String?>(null)
        private set
    var url by mutableStateOf("")
        private set
    var qr by mutableStateOf<Bitmap?>(null)
        private set
    var error by mutableStateOf("")
        private set
    private var onCancel: () -> Unit = {}
    override fun show(title: String, onCancel: () -> Unit) { this.title = title; url = ""; qr = null; error = ""; this.onCancel = onCancel }
    override fun ready(url: String, qr: Bitmap) { this.url = url; this.qr = qr }
    override fun failed(message: String) { error = message }
    override fun dismiss() { title = null; qr = null; url = ""; error = "" }
    fun cancel() { val callback = onCancel; dismiss(); callback() }
}

/** 二维码弹窗：左侧说明与地址，右侧二维码；返回或“取消输入”结束本次表单。 */
@Composable
fun PhoneFormDialog(state: PhoneFormUiState) {
    val title = state.title ?: return
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { cancelFocus.requestFocus() } }
    Dialog(onDismissRequest = state::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, colors = SurfaceDefaults.colors(containerColor = TvColors.surface), modifier = Modifier.width(820.dp)) {
            Row(Modifier.padding(32.dp, 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("在手机填写", style = MaterialTheme.typography.labelLarge, color = TvColors.accent)
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = TvColors.onSurface)
                    Spacer(Modifier.height(14.dp))
                    Text("手机与电视连接同一局域网，用相机扫描右侧二维码填写。提交后电视自动继续；此地址只用于本次操作，十分钟内有效。", style = MaterialTheme.typography.bodyMedium, color = TvColors.muted)
                    Spacer(Modifier.height(12.dp))
                    Text(when { state.error.isNotBlank() -> state.error; state.url.isBlank() -> "正在准备手机输入…"; else -> state.url }, style = MaterialTheme.typography.labelMedium, color = if (state.error.isNotBlank()) TvColors.amber else TvColors.muted)
                    Spacer(Modifier.height(22.dp))
                    TvButton("取消输入", onClick = state::cancel, filled = false, modifier = Modifier.focusRequester(cancelFocus))
                }
                Spacer(Modifier.width(28.dp))
                Box(Modifier.size(250.dp).background(Color.White, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                    state.qr?.let { Image(it.asImageBitmap(), contentDescription = "手机扫描二维码输入", modifier = Modifier.size(234.dp)) }
                        ?: Text(if (state.error.isNotBlank()) "服务未就绪" else "生成中…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF444444))
                }
            }
        }
    }
}
