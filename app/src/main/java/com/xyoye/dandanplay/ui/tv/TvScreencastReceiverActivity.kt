@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.storage.helper.ScreencastConstants
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.storage_component.services.ScreencastReceiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

/**
 * 原生 TV 投屏接收端：启停接收服务 + 展示本机 IP/端口 + 可选密码 + 接收确认/自启开关。
 * 手机投屏端通过 UDP 组播自动发现本机，或手动输入下方 IP/端口/密码连接（TV 版省略二维码）。
 */
class TvScreencastReceiverActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvScreencastReceiverActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvScreencastReceiverScreen(onExit = { finish() })
                }
            }
        }
    }
}

class TvScreencastReceiverViewModel : ViewModel() {
    var ipText by mutableStateOf("获取中…")
        private set

    fun loadIp() {
        viewModelScope.launch {
            ipText = withContext(Dispatchers.IO) {
                val addresses = mutableListOf<String>()
                runCatching {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val addrs = interfaces.nextElement().inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                            val host = addr.hostAddress ?: continue
                            if (host.isEmpty()) continue
                            if (addr is Inet4Address) addresses.add(0, host) else addresses.add(host)
                        }
                    }
                }
                addresses.joinToString("\n").ifEmpty { "未获取到局域网地址" }
            }
        }
    }

    fun randomPwd(): String = UUID.randomUUID().toString().substring(0, 8)
}

/**
 * 投屏接收端屏。可独立作为 Activity，也可内嵌进 TV 主框架内容区。
 * 服务启停由按钮控制、运行状态读 [ServiceLifecycleBridge] 全局 LiveData，与本屏宿主生命周期解耦，
 * 因此内嵌进 shell 时离开本页服务照常运行（预期行为）。
 *
 * @param onExit 非空时挂 BackHandler 并在返回时回调（独立 Activity 用 finish）；内嵌进 shell 时传 null。
 */
@Composable
internal fun TvScreencastReceiverScreen(
    modifier: Modifier = Modifier,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val viewModel: TvScreencastReceiverViewModel = viewModel()

    val observedRunning by ServiceLifecycleBridge.getScreencastReceiveObserver().observeAsState()
    val running = observedRunning ?: ScreencastReceiveService.isRunning(context)

    var port by remember {
        mutableStateOf(ScreencastConfig.getReceiverPort().takeIf { it != 0 } ?: Random.nextInt(20000, 30000))
    }
    var usePassword by remember { mutableStateOf(ScreencastConfig.isUseReceiverPassword()) }
    var password by remember { mutableStateOf(ScreencastConfig.getReceiverPassword() ?: "") }
    var needConfirm by remember { mutableStateOf(ScreencastConfig.isReceiveNeedConfirm()) }
    var autoStart by remember { mutableStateOf(ScreencastConfig.isStartReceiveWhenLaunch()) }

    LaunchedEffect(Unit) {
        viewModel.loadIp()
        if (ScreencastConfig.getReceiverPort() == 0) ScreencastConfig.putReceiverPort(port)
        if (usePassword && password.isEmpty()) password = viewModel.randomPwd()
    }
    BackHandler(enabled = onExit != null) { onExit?.invoke() }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "投屏接收端")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = if (running) "服务状态：已启动" else "服务状态：未启动",
                color = if (running) Color(0xFF3F8CFF) else Color(0xFFE5736B)
            )
            Button(onClick = {
                if (running) {
                    ScreencastReceiveService.stop(context)
                } else {
                    val pwd = if (usePassword) password else null
                    if (usePassword && pwd?.length != 8) {
                        ToastCenter.showWarning("密码需为 8 位")
                        return@Button
                    }
                    ScreencastConfig.putReceiverPassword(pwd ?: "")
                    ScreencastConfig.putUseReceiverPassword(usePassword)
                    ScreencastReceiveService.start(context, port, pwd)
                }
            }) { Text(text = if (running) "停止服务" else "启动服务") }
            Text(text = "v${ScreencastConstants.version}", color = Color(0xFF8A8A8A))
        }

        InfoBlock(title = "本机地址（手机投屏端连接用）", body = viewModel.ipText)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(text = "端口：$port")
            if (!running) {
                Button(onClick = {
                    port = Random.nextInt(20000, 30000)
                    ScreencastConfig.putReceiverPort(port)
                }) { Text(text = "更换端口") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Button(
                enabled = !running,
                onClick = {
                    usePassword = !usePassword
                    password = if (usePassword) viewModel.randomPwd() else ""
                }
            ) { Text(text = "连接密码：" + if (usePassword) "开" else "关") }
            if (usePassword) {
                Text(text = "密码：$password")
                if (!running) {
                    Button(onClick = { password = viewModel.randomPwd() }) { Text(text = "换一个") }
                }
            }
        }

        Button(onClick = {
            needConfirm = !needConfirm
            ScreencastConfig.putReceiveNeedConfirm(needConfirm)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "接收投屏时需手动确认：" + if (needConfirm) "开" else "关")
        }
        Button(onClick = {
            autoStart = !autoStart
            ScreencastConfig.putStartReceiveWhenLaunch(autoStart)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "应用启动时自动开启接收：" + if (autoStart) "开" else "关")
        }
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, color = Color(0xFF8A8A8A))
            Text(text = body)
        }
    }
}
