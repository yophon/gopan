package xyz.yophon.gopan.feature.transfers

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.prefs.TransferPrefs
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.transfer.TransferEngine
import javax.inject.Inject

@HiltViewModel
class TransferSettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val engine: TransferEngine,
) : ViewModel() {
    val settings: StateFlow<TransferPrefs?> = prefs.preferences.map { it.transfer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWifiOnly(value: Boolean) = viewModelScope.launch {
        prefs.setWifiOnly(value)
        // 网络约束变了:有没跑完的任务就按新约束重排(正在跑的会被打断,稍后按新约束继续)
        if (engine.hasPending()) engine.reschedule()
    }

    fun setParts(onWifi: Int, onMobile: Int) = viewModelScope.launch { prefs.setParts(onWifi, onMobile) }

    fun setDownloadTree(uri: String?, label: String?) = viewModelScope.launch { prefs.setDownloadTree(uri, label) }
}

/** 传输设置:仅 Wi-Fi、分片并发、下载位置,以及国产 ROM 后台限制的提示 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSettingsDialog(onDismiss: () -> Unit, viewModel: TransferSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val label = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':') }.getOrNull()
            viewModel.setDownloadTree(uri.toString(), label)
        }
    }
    val s = settings ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_settings_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.transfer_settings_wifi_only), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.transfer_settings_wifi_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                }
                PartsChooser(stringResource(R.string.transfer_settings_parts_wifi), s.partsOnWifi) { viewModel.setParts(it, s.partsOnMobile) }
                PartsChooser(stringResource(R.string.transfer_settings_parts_mobile), s.partsOnMobile) { viewModel.setParts(s.partsOnWifi, it) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.transfer_settings_download_dir), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.downloadTreeUri?.let { s.downloadTreeLabel?.ifEmpty { null } ?: it } ?: stringResource(R.string.transfer_settings_download_dir_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pickTree.launch(null) }) { Text(stringResource(R.string.transfer_settings_download_dir_pick)) }
                        if (s.downloadTreeUri != null) {
                            TextButton(onClick = { viewModel.setDownloadTree(null, null) }) {
                                Text(stringResource(R.string.transfer_settings_download_dir_reset))
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.transfer_settings_background_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartsChooser(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        val choices = TransferPrefs.PART_CHOICES
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, n ->
                SegmentedButton(
                    selected = value == n,
                    onClick = { onChange(n) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = choices.size),
                ) { Text(n.toString()) }
            }
        }
    }
}
