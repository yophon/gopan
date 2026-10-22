package xyz.yophon.gopan.feature.clips

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.MainViewModel
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.jobs.jobPercent
import xyz.yophon.gopan.core.data.jobs.jobStatusText
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.theme.GopanTheme

/**
 * 系统分享菜单里的「剪藏到 gopan」(manifest 里的 activity-alias `.ClipShareTarget` 指到这里,只接 text/plain):
 * 浏览器分享网址时出现。半透明的底部卡片,压在浏览器上面:提交 → 进度 → 打开 / 完成,结束就回浏览器。
 * 「打开」交给主界面进阅读页。发到传输助手的分享入口(MainActivity)不受影响。
 */
@AndroidEntryPoint
class ClipShareActivity : ComponentActivity() {

    private val viewModel: ClipShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.start(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        setContent {
            val main: MainViewModel = hiltViewModel()
            val appearance by main.appearance.collectAsStateWithLifecycle()
            val look = appearance ?: return@setContent
            GopanTheme(themeMode = look.theme, dynamicColor = look.dynamicColor) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ClipShareSheet(
                    state = state,
                    onClose = ::finish,
                    onOpen = ::openClip,
                    onOpenApp = ::openApp,
                    onReclip = viewModel::reclip,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    /** 主界面进阅读页:App 已经开着就回到它那个任务(不叠在浏览器上) */
    private fun openClip(nodeId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_CLIP)
                .putExtra(MainActivity.EXTRA_NODE_ID, nodeId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

@Composable
internal fun ClipShareSheet(
    state: ClipShareUiState,
    onClose: () -> Unit,
    onOpen: (nodeId: String) -> Unit,
    onOpenApp: () -> Unit,
    onReclip: () -> Unit,
    onRetry: () -> Unit,
) {
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = outside, indication = null, onClick = onClose),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // 点卡片本身不关
                .clickable(interactionSource = inside, indication = null) {}
                .testTag("clip_share_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.clip_share_label), style = MaterialTheme.typography.titleLarge)
                }
                state.url?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("clip_share_url"),
                    )
                }
                StepBody(state.step)
                StepButtons(state.step, onClose, onOpen, onOpenApp, onReclip, onRetry)
            }
        }
    }
}

@Composable
private fun StepBody(step: ClipShareStep) {
    when (step) {
        ClipShareStep.Submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.clip_share_submitting), style = MaterialTheme.typography.bodyLarge)
        }
        ClipShareStep.NoUrl -> Message(Icons.Outlined.LinkOff, stringResource(R.string.clip_share_no_url), stringResource(R.string.clip_share_no_url_hint))
        ClipShareStep.NeedLogin -> Message(Icons.AutoMirrored.Outlined.Login, stringResource(R.string.clip_share_need_login), stringResource(R.string.clip_share_need_login_hint))
        is ClipShareStep.Existing -> Message(
            Icons.Outlined.TaskAlt,
            stringResource(R.string.clip_share_existing),
            listOf(
                step.clip.title,
                stringResource(R.string.clip_share_existing_hint, formatRelativeTime(step.clip.createdAt)),
            ).filter { it.isNotBlank() }.joinToString("\n"),
            tag = "clip_share_existing",
        )
        is ClipShareStep.Running -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("clip_share_running")) {
            Text(stringResource(R.string.clip_share_running), style = MaterialTheme.typography.titleMedium)
            val percent = jobPercent(step.job)
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                listOf(step.job.title, jobStatusText(step.job)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (step.slow) R.string.clip_share_slow else R.string.clip_share_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ClipShareStep.Done -> Message(
            Icons.Outlined.CheckCircle,
            stringResource(R.string.clip_share_done),
            listOf(step.job.title, jobStatusText(step.job)).filter { it.isNotBlank() }.joinToString("\n"),
            tag = "clip_share_done",
        )
        is ClipShareStep.Failed -> Message(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.clip_share_failed),
            step.message,
            tint = MaterialTheme.colorScheme.error,
            tag = "clip_share_failed",
        )
    }
}

@Composable
private fun Message(
    icon: ImageVector,
    title: String,
    detail: String?,
    tint: Color = MaterialTheme.colorScheme.primary,
    tag: String? = null,
) {
    Row(verticalAlignment = Alignment.Top, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepButtons(
    step: ClipShareStep,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenApp: () -> Unit,
    onReclip: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        when (step) {
            is ClipShareStep.Existing -> {
                TextButton(onClick = onClose) { Text(stringResource(R.string.clip_share_finish)) }
                OutlinedButton(onClick = onReclip, modifier = Modifier.testTag("clip_share_reclip")) { Text(stringResource(R.string.clip_share_reclip)) }
                step.clip.nodeId?.let { id ->
                    Button(onClick = { onOpen(id) }, modifier = Modifier.testTag("clip_share_open")) { Text(stringResource(R.string.clip_share_open)) }
                }
            }
            is ClipShareStep.Done -> {
                TextButton(onClick = onClose, modifier = Modifier.testTag("clip_share_finish")) { Text(stringResource(R.string.clip_share_finish)) }
                step.nodeId?.let { id ->
                    Button(onClick = { onOpen(id) }, modifier = Modifier.testTag("clip_share_open")) { Text(stringResource(R.string.clip_share_open)) }
                }
            }
            is ClipShareStep.Failed -> {
                TextButton(onClick = onClose) { Text(stringResource(R.string.clip_share_close)) }
                Button(onClick = onRetry, modifier = Modifier.testTag("clip_share_retry")) { Text(stringResource(R.string.action_retry)) }
            }
            ClipShareStep.NeedLogin -> {
                TextButton(onClick = onClose) { Text(stringResource(R.string.clip_share_close)) }
                Button(onClick = onOpenApp) { Text(stringResource(R.string.clip_share_open_app)) }
            }
            ClipShareStep.NoUrl, ClipShareStep.Submitting, is ClipShareStep.Running ->
                TextButton(onClick = onClose, modifier = Modifier.testTag("clip_share_close")) { Text(stringResource(R.string.clip_share_close)) }
        }
    }
}
