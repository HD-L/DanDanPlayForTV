@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 是否为本原生表单支持的存储类型（其余仍路由现有 StoragePlus） */
fun MediaType.tvFormSupported(): Boolean = this == MediaType.SMB_SERVER ||
        this == MediaType.FTP_SERVER || this == MediaType.WEBDAV_SERVER ||
        this == MediaType.ALSIT_STORAGE || this == MediaType.REMOTE_STORAGE ||
        this == MediaType.SCREEN_CAST

/**
 * 原生存储源 添加/编辑 表单（SMB/FTP/WebDav/Alist），替换对应的 StoragePlus 对话框。
 * 直接构建 [MediaLibraryEntity] 写库；测试经 StorageFactory.createStorage(entity).test()。
 */
@Composable
fun TvStorageEditDialog(
    mediaType: MediaType,
    editData: MediaLibraryEntity?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(editData?.displayName ?: "") }
    var host by remember {
        mutableStateOf(
            when (mediaType) {
                MediaType.SCREEN_CAST -> editData?.screencastAddress ?: ""
                else -> editData?.url ?: editData?.ftpAddress ?: ""
            }
        )
    }
    var port by remember {
        mutableStateOf(
            editData?.port?.takeIf { it != 0 }?.toString()
                ?: if (mediaType == MediaType.SMB_SERVER) "445" else if (mediaType == MediaType.FTP_SERVER) "21" else ""
        )
    }
    var account by remember { mutableStateOf(editData?.account ?: "") }
    var password by remember { mutableStateOf(editData?.password ?: "") }
    var encoding by remember { mutableStateOf(editData?.ftpEncoding ?: "UTF-8") }
    var anonymous by remember { mutableStateOf(editData?.isAnonymous ?: false) }
    var smbV2 by remember { mutableStateOf(editData?.smbV2 ?: true) }
    var activeFtp by remember { mutableStateOf(editData?.isActiveFTP ?: false) }
    var strict by remember { mutableStateOf(editData?.webDavStrict ?: true) }
    var secret by remember { mutableStateOf(editData?.remoteSecret ?: "") }
    var grouping by remember { mutableStateOf(editData?.remoteAnimeGrouping ?: false) }

    var testing by remember { mutableStateOf(false) }

    fun normUrl(u: String) = if (u.endsWith("/")) u else "$u/"

    fun buildEntity(): MediaLibraryEntity = when (mediaType) {
        MediaType.SMB_SERVER -> MediaLibraryEntity(
            displayName = name.ifBlank { "SMB媒体库" },
            url = host,
            mediaType = MediaType.SMB_SERVER,
            account = account.ifBlank { null },
            password = password.ifBlank { null },
            isAnonymous = anonymous,
            port = port.toIntOrNull() ?: 445,
            describe = "smb://$host",
            smbV2 = smbV2
        )
        MediaType.FTP_SERVER -> MediaLibraryEntity(
            displayName = name.ifBlank { "FTP媒体库" },
            url = "ftp://$host:${port.toIntOrNull() ?: 21}",
            mediaType = MediaType.FTP_SERVER,
            account = account.ifBlank { null },
            password = password.ifBlank { null },
            isAnonymous = anonymous,
            port = port.toIntOrNull() ?: 21,
            ftpAddress = host,
            ftpEncoding = encoding.ifBlank { "UTF-8" },
            isActiveFTP = activeFtp
        )
        MediaType.WEBDAV_SERVER -> MediaLibraryEntity(
            displayName = name.ifBlank { "WebDav媒体库" },
            url = normUrl(host),
            mediaType = MediaType.WEBDAV_SERVER,
            account = account.ifBlank { null },
            password = password.ifBlank { null },
            isAnonymous = anonymous,
            describe = normUrl(host),
            webDavStrict = strict
        )
        MediaType.REMOTE_STORAGE -> MediaLibraryEntity(
            displayName = name.ifBlank { "PC端媒体库" },
            url = normUrl(host),
            mediaType = MediaType.REMOTE_STORAGE,
            port = 80,
            remoteSecret = secret.ifBlank { null },
            remoteAnimeGrouping = grouping,
            describe = normUrl(host)
        )
        MediaType.SCREEN_CAST -> MediaLibraryEntity(
            displayName = name.ifBlank { "未知投屏设备" },
            url = "http://$host:${port.toIntOrNull() ?: 0}",
            mediaType = MediaType.SCREEN_CAST,
            screencastAddress = host,
            port = port.toIntOrNull() ?: 0,
            password = password.ifBlank { null }
        )
        else -> MediaLibraryEntity(
            displayName = name.ifBlank { "Alist媒体库" },
            url = normUrl(host),
            mediaType = MediaType.ALSIT_STORAGE,
            account = account.ifBlank { null },
            password = password.ifBlank { null },
            describe = normUrl(host)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(620.dp)) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = (if (editData != null) "编辑 " else "添加 ") + mediaType.storageName)

                when (mediaType) {
                    MediaType.REMOTE_STORAGE -> {
                        TvField("库名称（可空）", name) { name = it }
                        TvField("服务器地址（http://...)", host) { host = it }
                        TvField("密钥（可空）", secret) { secret = it }
                        TvToggle("远程分组", "按番剧", "按文件夹", grouping) { grouping = it }
                    }
                    MediaType.SCREEN_CAST -> {
                        TvField("库名称（可空）", name) { name = it }
                        TvField("投屏源 IP", host) { host = it }
                        TvField("端口", port) { port = it }
                        TvField("密码（可空）", password, isPassword = true) { password = it }
                    }
                    else -> {
                        TvField("库名称（可空）", name) { name = it }
                        TvField(if (mediaType == MediaType.SMB_SERVER) "IP / 主机名" else "服务器地址", host) { host = it }
                        if (mediaType == MediaType.SMB_SERVER || mediaType == MediaType.FTP_SERVER) {
                            TvField("端口", port) { port = it }
                        }
                        if (mediaType == MediaType.FTP_SERVER) {
                            TvField("编码格式", encoding) { encoding = it }
                        }
                        if (mediaType != MediaType.ALSIT_STORAGE) {
                            TvToggle("认证方式", "匿名", "账号", anonymous) { anonymous = it }
                        }
                        if (mediaType == MediaType.ALSIT_STORAGE || !anonymous) {
                            TvField("帐号", account) { account = it }
                            TvField("密码", password, isPassword = true) { password = it }
                        }
                        when (mediaType) {
                            MediaType.SMB_SERVER -> TvToggle("SMB 版本", "SMB V2", "SMB V1", smbV2) { smbV2 = it }
                            MediaType.FTP_SERVER -> TvToggle("传输模式", "主动", "被动", activeFtp) { activeFtp = it }
                            MediaType.WEBDAV_SERVER -> TvToggle("解析模式", "严格", "普通", strict) { strict = it }
                            else -> Unit
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            testing = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { StorageFactory.createStorage(buildEntity())?.test() == true }
                                        .getOrDefault(false)
                                }
                                testing = false
                                if (ok) ToastCenter.showSuccess("连接成功") else ToastCenter.showError("连接失败")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(text = if (testing) "测试中…" else "测试连接") }

                    Button(
                        onClick = {
                            if (host.isBlank()) {
                                ToastCenter.showError("请填写地址")
                                return@Button
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val dao = DatabaseManager.instance.getMediaLibraryDao()
                                    editData?.let { dao.delete(it.url, it.mediaType) }
                                    dao.insert(buildEntity())
                                }
                                onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(text = "保存") }

                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(text = "取消") }
                }
            }
        }
    }
}

@Composable
private fun TvField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    var showInput by remember { mutableStateOf(false) }
    val display = when {
        value.isEmpty() -> "—"
        isPassword -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
    Surface(onClick = { showInput = true }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f))
            Text(
                text = display,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (showInput) {
        TvFieldInputDialog(label, value, onConfirm = { onValueChange(it); showInput = false }, onDismiss = { showInput = false })
    }
}

@Composable
private fun TvToggle(
    label: String,
    optionTrue: String,
    optionFalse: String,
    value: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f))
            Button(onClick = { onChange(true) }) { Text(text = if (value) "● $optionTrue" else optionTrue) }
            Box(modifier = Modifier.width(10.dp))
            Button(onClick = { onChange(false) }) { Text(text = if (!value) "● $optionFalse" else optionFalse) }
        }
    }
}

@Composable
private fun TvFieldInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(520.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = title)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, MaterialTheme.colorScheme.primary)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) { Text("确定") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}
