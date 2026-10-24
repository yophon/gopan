package xyz.yophon.gopan.feature.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node

/**
 * 批量重命名(doc/27,全屏对话框):按规则命名(* 原名、# 序号)、替换文字、改扩展名三种,下面实时列出改名前后;
 * 有冲突、不合法、一项都没变时「重命名」点不了。[nodes] 按列表里的顺序,序号照这个编。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameDialog(
    nodes: List<Node>,
    /** 同一个文件夹里已加载的全部名字 */
    siblings: List<String>,
    busy: Boolean,
    /** 服务端拒绝(和没加载到的条目同名)的原因 */
    error: String?,
    onApply: (List<RenamePlanItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var template by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("1") }
    var find by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf("") }
    var ext by rememberSaveable { mutableStateOf("") }

    val items = remember(nodes) { nodes.map { NodeLike(it.id, it.name, it.isFolder) } }
    val rule: RenameRule? = when (mode) {
        0 -> template.takeIf { it.isNotBlank() }?.let { RenameRule.Template(it, start.toIntOrNull() ?: 1) }
        1 -> find.takeIf { it.isNotEmpty() }?.let { RenameRule.Replace(it, replace) }
        else -> RenameRule.Extension(ext)
    }
    val plan = remember(items, rule, siblings) { BatchRename.plan(items, rule, siblings) }
    val changed = plan.count { it.changed }
    val ready = BatchRename.canApply(plan) && !busy

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBarsAppearance()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    colors = pinnedHeaderTopBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.drive_picker_close))
                        }
                    },
                    title = { Text(stringResource(R.string.drive_batch_rename_title, nodes.size)) },
                    actions = {
                        TextButton(onClick = { onApply(plan) }, enabled = ready, modifier = Modifier.testTag("batch_rename_apply")) {
                            Text(if (changed > 0) stringResource(R.string.drive_batch_rename_apply_n, changed) else stringResource(R.string.drive_action_rename))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(R.string.drive_batch_rename_mode_template, R.string.drive_batch_rename_mode_replace, R.string.drive_batch_rename_mode_ext)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = mode == i,
                                onClick = { mode = i },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(label), maxLines = 1) }
                        }
                    }
                    when (mode) {
                        0 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = template,
                                    onValueChange = { template = it },
                                    label = { Text(stringResource(R.string.drive_batch_rename_template)) },
                                    placeholder = { Text(stringResource(R.string.drive_batch_rename_template_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("batch_rename_template"),
                                )
                                OutlinedTextField(
                                    value = start,
                                    onValueChange = { v -> start = v.filter(Char::isDigit).take(6) },
                                    label = { Text(stringResource(R.string.drive_batch_rename_start)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(96.dp),
                                )
                            }
                            Tip(stringResource(R.string.drive_batch_rename_template_tip))
                        }
                        1 -> {
                            OutlinedTextField(
                                value = find,
                                onValueChange = { find = it },
                                label = { Text(stringResource(R.string.drive_batch_rename_find)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = replace,
                                onValueChange = { replace = it },
                                label = { Text(stringResource(R.string.drive_batch_rename_replace)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            OutlinedTextField(
                                value = ext,
                                onValueChange = { ext = it },
                                label = { Text(stringResource(R.string.drive_batch_rename_ext)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Tip(stringResource(R.string.drive_batch_rename_ext_tip))
                        }
                    }
                    if (error != null) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(plan, key = { it.id }) { p -> PlanRow(p) }
                }
            }
        }
    }
}

@Composable
private fun Tip(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 一行:原名(灰)→ 新名;没变的新名也是灰的,有问题的标红并写原因 */
@Composable
private fun PlanRow(p: RenamePlanItem) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(p.oldName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(
            "→ " + p.newName,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                p.problem != null -> MaterialTheme.colorScheme.error
                !p.changed -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        p.problem?.let { problem ->
            Text(
                stringResource(
                    when (problem) {
                        RenameProblem.INVALID -> R.string.drive_batch_rename_invalid
                        RenameProblem.DUPLICATE -> R.string.drive_batch_rename_duplicate
                        RenameProblem.EXISTS -> R.string.drive_batch_rename_exists
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
