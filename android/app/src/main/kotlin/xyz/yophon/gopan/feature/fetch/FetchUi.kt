package xyz.yophon.gopan.feature.fetch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.jobs.MAX_FETCH_URLS
import xyz.yophon.gopan.core.ui.exposeTestTags

/**
 * 文件页「离线下载」对话框:说明 + 多行输入框;边输边数识别到几条链接,0 条或超过 10 条时不能提交。
 * 提交失败的原因显示在输入框下面(对话框盖着页面的 snackbar)。
 */
@Composable
fun FetchDialog(
    state: FetchUiState,
    folderName: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val count = state.urls.size
    val hint = when {
        state.error != null -> state.error
        count == 0 -> stringResource(R.string.fetch_count_none)
        state.tooMany -> stringResource(R.string.fetch_count_too_many, count, MAX_FETCH_URLS)
        else -> stringResource(R.string.fetch_count, count)
    }
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        modifier = Modifier.exposeTestTags(),
        title = { Text(stringResource(R.string.fetch_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.fetch_dialog_hint, MAX_FETCH_URLS, folderName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.fetch_dialog_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !state.submitting,
                    isError = state.tooMany || state.error != null,
                    supportingText = { Text(hint, modifier = Modifier.testTag("fetch_count")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("fetch_input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.testTag("fetch_submit")) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.fetch_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 传输助手草稿上方的提示条:「离线下载这个链接」,点一下存到传输助手文件夹,下完会话里出现文件卡片 */
@Composable
fun ChatFetchBanner(offer: ChatFetchOffer, busy: Boolean, onFetch: () -> Unit, onDismiss: () -> Unit) {
    val label = when {
        offer.capped -> stringResource(R.string.fetch_chat_capped, MAX_FETCH_URLS)
        offer.urls.size == 1 -> stringResource(R.string.fetch_chat_one)
        else -> stringResource(R.string.fetch_chat_many, offer.urls.size)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = !busy, onClick = onFetch)
                .heightIn(min = 40.dp)
                .padding(start = 16.dp, end = 4.dp)
                .testTag("chat_fetch_banner"),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, enabled = !busy) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.fetch_chat_dismiss), modifier = Modifier.size(18.dp))
            }
        }
    }
}
