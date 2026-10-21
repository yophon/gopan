package xyz.yophon.gopan.feature.transfers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.jobs.JobKind
import xyz.yophon.gopan.core.data.jobs.JobStatus
import xyz.yophon.gopan.core.data.jobs.isActive
import xyz.yophon.gopan.core.data.jobs.isRetryable
import xyz.yophon.gopan.core.data.jobs.isWarning
import xyz.yophon.gopan.core.data.jobs.jobLocation
import xyz.yophon.gopan.core.data.jobs.jobPercent
import xyz.yophon.gopan.core.data.jobs.jobStatusText
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.feature.drive.FileCategory
import xyz.yophon.gopan.feature.drive.fileCategory
import xyz.yophon.gopan.feature.drive.icon
import xyz.yophon.gopan.feature.drive.tint

/** 「后台任务」栏:离线下载与解压(服务端执行,关掉 App 也继续) */
@Composable
internal fun JobsTab(state: JobsUiState, viewModel: JobsViewModel) {
    when {
        !state.loaded -> LoadingState()
        state.error != null && state.rows.isEmpty() -> ErrorState(state.error, onRetry = viewModel::retryLoad)
        state.rows.isEmpty() -> EmptyState(
            title = stringResource(R.string.jobs_empty),
            description = stringResource(R.string.jobs_empty_desc),
            icon = Icons.Outlined.CloudSync,
        )
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.job.id }) { row ->
                JobItem(row, viewModel)
                HorizontalDivider()
            }
        }
    }
}

/** 一行:类型标签 + 名字 + 状态(下载中带速度)+ 进度条 + 操作;点一下展开看完整链接 */
@Composable
private fun JobItem(row: JobRow, viewModel: JobsViewModel) {
    val job = row.job
    var expanded by rememberSaveable(job.id) { mutableStateOf(false) }
    val category = when (job.kind) {
        JobKind.EXTRACT_ARCHIVE -> FileCategory.ARCHIVE
        JobKind.FETCH_URL -> fileCategory(NodeKind.FILE, job.title, null)
    }
    val statusColor = when {
        job.status == JobStatus.FAILED -> MaterialTheme.colorScheme.error
        job.isWarning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val percent = jobPercent(job)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("job_row_${job.title}")
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(category.icon, contentDescription = null, tint = category.tint(), modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindTag(stringResource(if (job.kind == JobKind.EXTRACT_ARCHIVE) R.string.jobs_kind_extract else R.string.jobs_kind_fetch))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (job.isWarning) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        jobStatusText(job, row.speedBps),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("job_status"),
                    )
                }
                val url = job.url
                if (expanded && url != null) {
                    SelectionContainer {
                        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.End) {
                when {
                    job.isActive -> ActionIcon(Icons.Outlined.Close, R.string.transfer_action_cancel) { viewModel.cancel(job) }
                    job.isRetryable -> ActionIcon(Icons.Outlined.Refresh, R.string.transfer_action_retry) { viewModel.retry(job) }
                    jobLocation(job) != null -> ActionIcon(Icons.Outlined.FolderOpen, R.string.jobs_action_locate) { viewModel.locate(job) }
                }
            }
        }
        // 进度条只在下载 / 解压中画;不知道总量时画不确定进度条
        if (job.status == JobStatus.RUNNING) {
            Spacer(Modifier.height(8.dp))
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            } else {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun KindTag(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}
