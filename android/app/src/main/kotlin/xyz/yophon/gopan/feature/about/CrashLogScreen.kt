package xyz.yophon.gopan.feature.about

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.app.CrashLog
import xyz.yophon.gopan.core.app.CrashReport
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@Immutable
data class CrashLogUiState(val reports: List<CrashReport> = emptyList(), val open: CrashReport? = null, val openText: String = "")

/** 崩溃日志:列表、查看全文、分享导出、删除 / 清空。只在本地,不上报 */
@HiltViewModel
class CrashLogViewModel @Inject constructor(private val log: CrashLog) : ViewModel() {
    private val _state = MutableStateFlow(CrashLogUiState(reports = log.reports()))
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = _state.value.copy(reports = log.reports())
    }

    fun open(report: CrashReport) {
        _state.value = _state.value.copy(open = report, openText = runCatching { report.file.readText() }.getOrDefault(""))
    }

    fun close() {
        _state.value = _state.value.copy(open = null, openText = "")
    }

    fun delete(report: CrashReport) {
        log.delete(report)
        _state.value = CrashLogUiState(reports = log.reports())
    }

    fun clear() {
        log.clear()
        _state.value = CrashLogUiState()
    }
}

/** 用系统分享面板把崩溃日志发出去(存到「文件」、发给开发者……) */
fun shareCrashReports(context: Context, reports: List<CrashReport>) {
    if (reports.isEmpty()) return
    val authority = "${context.packageName}.files"
    val uris = reports.map { FileProvider.getUriForFile(context, authority, it.file) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_subject))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // 授权随 ClipData 带给接收方(只放 EXTRA_STREAM 有的应用拿不到读权限)
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.crash_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onBack: () -> Unit, viewModel: CrashLogViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back)) }
                },
                title = { Text(stringResource(R.string.crash_title)) },
                actions = {
                    if (state.reports.isNotEmpty()) {
                        IconButton(onClick = { shareCrashReports(context, state.reports) }, modifier = Modifier.testTag("crash_share_all")) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.crash_share_all))
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.crash_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.reports.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.crash_empty),
                description = stringResource(R.string.crash_empty_hint),
                icon = Icons.Outlined.BugReport,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.reports, key = { it.file.name }) { r ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.open(r) }.testTag("crash_row"),
                        leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                        headlineContent = { Text(TIME.format(Instant.ofEpochMilli(r.timeMillis).atZone(ZoneId.systemDefault()))) },
                        supportingContent = { Text(r.headline, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }
    }

    state.open?.let { r ->
        AlertDialog(
            onDismissRequest = viewModel::close,
            modifier = Modifier.exposeTestTags(),
            title = { Text(TIME.format(Instant.ofEpochMilli(r.timeMillis).atZone(ZoneId.systemDefault()))) },
            text = {
                SelectionContainer {
                    Text(
                        state.openText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .testTag("crash_text"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareCrashReports(context, listOf(r)) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.crash_share), modifier = Modifier.padding(start = 6.dp))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.close()
                    viewModel.delete(r)
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.crash_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
                }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(R.string.crash_clear)) },
            text = { Text(stringResource(R.string.crash_clear_text, state.reports.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clear()
                }) { Text(stringResource(R.string.crash_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
