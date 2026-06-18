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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.xyoye.common_component.network.repository.UserRepository
import com.xyoye.common_component.utils.SecurityHelper
import com.xyoye.common_component.utils.UserInfoHelper
import com.xyoye.common_component.weight.ToastCenter
import kotlinx.coroutines.launch

/* ============================ 登录 ============================ */

class TvLoginActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvLoginActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvLoginScreen(onDone = { finish() })
                }
            }
        }
    }
}

class TvLoginViewModel : ViewModel() {
    var account by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set

    fun login(onSuccess: () -> Unit) {
        if (account.isEmpty()) { ToastCenter.showWarning("请输入帐号"); return }
        if (password.isEmpty()) { ToastCenter.showWarning("请输入密码"); return }

        val appId = SecurityHelper.getInstance().appId
        val timestamp = System.currentTimeMillis() / 1000
        val hash = SecurityHelper.getInstance().buildHash(appId + password + timestamp + account)

        viewModelScope.launch {
            loading = true
            val result = UserRepository.login(account, password, appId, timestamp.toString(), hash)
            loading = false
            if (result.isFailure) {
                ToastCenter.showError(result.exceptionOrNull()?.message ?: "登录失败")
                return@launch
            }
            val data = result.getOrNull()
            if (data != null && UserInfoHelper.login(data)) {
                ToastCenter.showSuccess("登录成功")
                onSuccess()
            } else {
                ToastCenter.showError("登录错误，请稍后再试")
            }
        }
    }
}

@Composable
private fun TvLoginScreen(onDone: () -> Unit) {
    val viewModel: TvLoginViewModel = viewModel()
    val official = remember { runCatching { SecurityHelper.getInstance().isOfficialApplication() }.getOrDefault(false) }

    BackHandler(enabled = true) { onDone() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "登录 DanDanPlay")
        if (official != true) {
            Text(
                text = "提示：自编译版本账号功能受限，需官方包或在「应用设置」配置开发者凭据，否则登录会返回 403。",
                color = Color(0xFFE5A23B)
            )
        }
        TvFormField(label = "帐号", value = viewModel.account, placeholder = "未填写") { viewModel.account = it }
        TvFormField(label = "密码", value = viewModel.password, isPassword = true, placeholder = "未填写") { viewModel.password = it }
        Button(
            onClick = { viewModel.login(onSuccess = onDone) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(text = if (viewModel.loading) "登录中…" else "登录") }
    }
}

/* ============================ 账号信息 ============================ */

class TvUserInfoActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvUserInfoActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UserInfoHelper.mLoginData == null) {
            ToastCenter.showError("尚未登录")
            finish()
            return
        }
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvUserInfoScreen(onExit = { finish() })
                }
            }
        }
    }
}

class TvUserInfoViewModel : ViewModel() {
    var account by mutableStateOf(UserInfoHelper.mLoginData?.userName ?: "")
        private set
    var screenName by mutableStateOf(UserInfoHelper.mLoginData?.screenName ?: "")
        private set
    var loading by mutableStateOf(false)
        private set

    fun updateScreenName(name: String) {
        if (name.isEmpty()) return
        viewModelScope.launch {
            loading = true
            val result = UserRepository.updateScreenName(name)
            loading = false
            if (result.isFailure) {
                ToastCenter.showError(result.exceptionOrNull()?.message ?: "修改失败")
                return@launch
            }
            UserInfoHelper.mLoginData?.screenName = name
            UserInfoHelper.updateLoginInfo()
            screenName = name
            ToastCenter.showSuccess("修改昵称成功")
        }
    }

    fun updatePassword(oldPassword: String, newPassword: String, onLoggedOut: () -> Unit) {
        if (oldPassword.isEmpty() || newPassword.isEmpty()) {
            ToastCenter.showWarning("请输入原密码与新密码")
            return
        }
        viewModelScope.launch {
            loading = true
            val result = UserRepository.updatePassword(oldPassword, newPassword)
            loading = false
            if (result.isFailure) {
                ToastCenter.showError(result.exceptionOrNull()?.message ?: "修改失败")
                return@launch
            }
            UserInfoHelper.exitLogin()
            ToastCenter.showSuccess("修改密码成功，请重新登录")
            onLoggedOut()
        }
    }

    fun logout(onDone: () -> Unit) {
        UserInfoHelper.exitLogin()
        onDone()
    }
}

@Composable
private fun TvUserInfoScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TvUserInfoViewModel = viewModel()
    var changingPwd by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onExit() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "账号信息")

        Surface(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(18.dp)) {
                Text(text = "帐号", modifier = Modifier.fillMaxWidth(0.3f))
                Text(text = viewModel.account, color = Color(0xFFCFCFCF))
            }
        }
        TvFormField(label = "昵称", value = viewModel.screenName) { viewModel.updateScreenName(it) }

        Button(onClick = { changingPwd = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "修改密码")
        }
        Button(
            onClick = {
                viewModel.logout {
                    ToastCenter.showSuccess("已退出登录")
                    TvLoginActivity.start(context)
                    onExit()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(text = "退出登录") }
    }

    if (changingPwd) {
        PasswordChangeDialog(
            onConfirm = { old, new ->
                changingPwd = false
                viewModel.updatePassword(old, new) {
                    TvLoginActivity.start(context)
                    onExit()
                }
            },
            onDismiss = { changingPwd = false }
        )
    }
}

@Composable
private fun PasswordChangeDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(0.6f)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = "修改密码")
                TvFormField(label = "原密码", value = oldPwd, isPassword = true, placeholder = "未填写") { oldPwd = it }
                TvFormField(label = "新密码", value = newPwd, isPassword = true, placeholder = "未填写") { newPwd = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onConfirm(oldPwd, newPwd) }, modifier = Modifier.fillMaxWidth(0.5f)) { Text("确定") }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }
}
