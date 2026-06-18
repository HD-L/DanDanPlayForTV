@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 通用 TV 表单字段：一行展示「标签 + 当前值」，按下弹出输入框（含密码掩码）。
 * 供登录 / 账号信息 / 其余表单复用。
 */
@Composable
internal fun TvFormField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    placeholder: String = "—",
    onValueChange: (String) -> Unit
) {
    var showInput by remember { mutableStateOf(false) }
    val display = when {
        value.isEmpty() -> placeholder
        isPassword -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
    Surface(onClick = { showInput = true }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.width(160.dp))
            Text(
                text = display,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (showInput) {
        TvFormInputDialog(
            title = label,
            initial = value,
            isPassword = isPassword,
            onConfirm = { onValueChange(it); showInput = false },
            onDismiss = { showInput = false }
        )
    }
}

@Composable
internal fun TvFormInputDialog(
    title: String,
    initial: String,
    isPassword: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(560.dp)) {
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
                        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
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
                    Button(onClick = { onConfirm(text) }, modifier = Modifier.fillMaxWidth(0.5f)) { Text("确定") }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}
