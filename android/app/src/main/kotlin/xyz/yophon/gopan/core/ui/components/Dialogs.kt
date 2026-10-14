package xyz.yophon.gopan.core.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import xyz.yophon.gopan.R

/** 确认对话框;destructive 时确认按钮用错误色(删除、清空回收站) */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.action_confirm),
    dismissText: String = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    busy: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissText) }
        },
    )
}

/**
 * 单行文本输入对话框(新建文件夹、重命名)。初始内容全选、自动聚焦;
 * [validate] 返回非空字符串即显示为错误并禁止确认。
 */
@Composable
fun TextInputDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    label: String? = null,
    placeholder: String? = null,
    confirmText: String = stringResource(R.string.action_confirm),
    dismissText: String = stringResource(R.string.action_cancel),
    busy: Boolean = false,
    validate: (String) -> String? = { null },
) {
    var value by rememberSaveable(initialValue, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, selection = TextRange(0, initialValue.length)))
    }
    var touched by remember { mutableStateOf(false) }
    val error = validate(value.text)
    val canConfirm = error == null && value.text.isNotBlank() && !busy
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; touched = true },
                modifier = Modifier.focusRequester(focusRequester),
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                isError = touched && error != null,
                supportingText = if (touched && error != null) ({ Text(error) }) else null,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text.trim()) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = canConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissText) }
        },
    )
}
