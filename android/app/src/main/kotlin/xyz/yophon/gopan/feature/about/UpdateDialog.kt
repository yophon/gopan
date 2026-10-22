package xyz.yophon.gopan.feature.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.BuildConfig
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.app.AppRelease
import xyz.yophon.gopan.core.app.UpdateCheck
import xyz.yophon.gopan.core.app.UpdateRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.format.formatBytes
import java.io.File
import javax.inject.Inject

/** 检查更新对话框的阶段 */
sealed interface UpdatePhase {
    data object Checking : UpdatePhase

    data class UpToDate(val versionName: String) : UpdatePhase

    data object NotConfigured : UpdatePhase

    data class Available(val release: AppRelease) : UpdatePhase

    data class Downloading(val release: AppRelease, val fraction: Float?) : UpdatePhase

    data class Ready(val release: AppRelease, val file: File) : UpdatePhase

    data class Failed(val message: String, val release: AppRelease?) : UpdatePhase
}

@Immutable
data class UpdateUiState(
    /** null = 对话框没开 */
    val phase: UpdatePhase? = null,
    /** 「我的」页那一项上的提示:有新版本时是版本名 */
    val availableVersion: String? = null,
)

/**
 * 检查更新:站点的 `/api/app/android/latest` 比本机新就提示,下载到缓存、校验 SHA-256 后交给系统安装器。
 * 「我的」页打开时静默查一次(每个进程一次),有新版本就在「检查更新」那一项上标出来。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(private val repo: UpdateRepository) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var silentChecked = false

    val changelogUrl: String get() = repo.changelogUrl

    /** 「我的」页显示时调用:静默查,失败不提示 */
    fun checkSilently() {
        if (silentChecked) return
        silentChecked = true
        viewModelScope.launch {
            val r = gopanResult { repo.check(BuildConfig.VERSION_CODE.toLong()) }.getOrNull()
            if (r is UpdateCheck.Available) _state.update { it.copy(availableVersion = r.release.versionName) }
        }
    }

    fun check() {
        job?.cancel()
        _state.update { it.copy(phase = UpdatePhase.Checking) }
        job = viewModelScope.launch {
            gopanResult { repo.check(BuildConfig.VERSION_CODE.toLong()) }
                .onSuccess { r ->
                    val phase = when (r) {
                        is UpdateCheck.Available -> UpdatePhase.Available(r.release)
                        is UpdateCheck.UpToDate -> UpdatePhase.UpToDate(BuildConfig.VERSION_NAME)
                        UpdateCheck.NotConfigured -> UpdatePhase.NotConfigured
                    }
                    _state.update { it.copy(phase = phase, availableVersion = (r as? UpdateCheck.Available)?.release?.versionName) }
                }
                .onFailure { e -> _state.update { it.copy(phase = UpdatePhase.Failed(e.message ?: "检查更新失败", null)) } }
        }
    }

    fun download(release: AppRelease) {
        job?.cancel()
        _state.update { it.copy(phase = UpdatePhase.Downloading(release, null)) }
        job = viewModelScope.launch {
            var last = -1
            gopanResult {
                repo.download(release) { done, total ->
                    val pct = total?.let { (done * 100 / it).toInt() }
                    if (pct != null && pct != last) {
                        last = pct
                        _state.update { s -> if (s.phase is UpdatePhase.Downloading) s.copy(phase = UpdatePhase.Downloading(release, pct / 100f)) else s }
                    }
                }
            }.onSuccess { file -> _state.update { it.copy(phase = UpdatePhase.Ready(release, file)) } }
                .onFailure { e -> _state.update { it.copy(phase = UpdatePhase.Failed(e.message ?: "下载失败", release)) } }
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.update { it.copy(phase = null) }
    }
}

/** 交给系统安装器;Android 8+ 没授权「安装未知应用」时先去设置页授权,返回 false */
fun installApk(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

@Composable
fun UpdateDialog(phase: UpdatePhase, viewModel: UpdateViewModel) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        modifier = Modifier.exposeTestTags().testTag("update_dialog"),
        title = {
            Text(
                when (phase) {
                    is UpdatePhase.Available, is UpdatePhase.Downloading, is UpdatePhase.Ready ->
                        stringResource(R.string.update_title_available)
                    else -> stringResource(R.string.update_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    UpdatePhase.Checking -> CircularProgressIndicator()
                    is UpdatePhase.UpToDate -> Text(stringResource(R.string.update_up_to_date, phase.versionName), modifier = Modifier.testTag("update_up_to_date"))
                    UpdatePhase.NotConfigured -> Text(stringResource(R.string.update_not_configured))
                    is UpdatePhase.Available -> ReleaseInfo(phase.release)
                    is UpdatePhase.Downloading -> {
                        ReleaseInfo(phase.release)
                        if (phase.fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { phase.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdatePhase.Ready -> {
                        ReleaseInfo(phase.release)
                        Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdatePhase.Failed -> {
                        phase.release?.let { ReleaseInfo(it) }
                        Text(phase.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                is UpdatePhase.Available -> TextButton(onClick = { viewModel.download(phase.release) }, modifier = Modifier.testTag("update_download")) {
                    Text(stringResource(R.string.update_download))
                }
                is UpdatePhase.Ready -> TextButton(onClick = { installApk(context, phase.file) }, modifier = Modifier.testTag("update_install")) {
                    Text(stringResource(R.string.update_install))
                }
                is UpdatePhase.Failed -> TextButton(onClick = { phase.release?.let(viewModel::download) ?: viewModel.check() }) {
                    Text(stringResource(R.string.action_retry))
                }
                else -> TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_close)) }
            }
        },
        dismissButton = {
            if (phase is UpdatePhase.Available || phase is UpdatePhase.Downloading || phase is UpdatePhase.Ready || phase is UpdatePhase.Failed) {
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}

@Composable
private fun ReleaseInfo(release: AppRelease) {
    Text(
        stringResource(R.string.update_version_line, release.versionName, BuildConfig.VERSION_NAME, formatBytes(release.size.takeIf { it > 0 })),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("update_version"),
    )
    if (release.notes.isNotBlank()) {
        Text(
            release.notes,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        )
    }
}
