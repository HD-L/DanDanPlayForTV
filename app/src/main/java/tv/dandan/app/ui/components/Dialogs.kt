package tv.dandan.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.dandan.app.ui.theme.TvColors

/** 模态确认框：返回键等同取消；初始焦点在取消，避免误触破坏性操作。 */
@Composable
fun TvDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "确认",
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    busy: Boolean = false,
    extra: @Composable (() -> Unit)? = null,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, colors = SurfaceDefaults.colors(containerColor = TvColors.surface), modifier = Modifier.width(620.dp)) {
            Column(Modifier.padding(32.dp, 28.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = TvColors.onSurface)
                Spacer(Modifier.height(14.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = TvColors.muted)
                extra?.invoke()
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.End)) {
                    TvButton(dismissLabel, onClick = onDismiss, filled = false, modifier = Modifier.focusRequester(cancelFocus), enabled = !busy)
                    if (onConfirm != null) TvButton(if (busy) "处理中…" else confirmLabel, onClick = onConfirm, danger = destructive, enabled = !busy)
                }
            }
        }
    }
}

/** 应用内提示：底部浮现，3 秒后消失；不抢焦点。 */
class Toaster {
    var message by mutableStateOf<String?>(null)
        private set
    private var serial = 0
    fun show(text: String) { serial++; message = text }
    internal fun current() = serial
    internal fun clear(expected: Int) { if (serial == expected) message = null }
}

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val message = toaster.message
    LaunchedEffect(message) {
        if (message != null) { val id = toaster.current(); delay(3200); toaster.clear(id) }
    }
    Box(modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(message != null, enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 }, exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 2 }) {
            Box(Modifier.background(TvColors.surfaceBright.copy(alpha = .96f), MaterialTheme.shapes.medium).padding(horizontal = 26.dp, vertical = 14.dp)) {
                Text(message.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = TvColors.onSurface)
            }
        }
    }
}
