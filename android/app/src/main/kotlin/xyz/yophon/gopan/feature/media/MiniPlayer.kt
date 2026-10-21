package xyz.yophon.gopan.feature.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.NowPlayingStore
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import xyz.yophon.gopan.feature.preview.rememberMediaController
import javax.inject.Inject

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    store: NowPlayingStore,
    private val sessions: PreviewSessionStore,
) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying?> = store.state

    /** 点播放条打开预览前:左右翻的是整个播放队列 */
    fun prepareOpen(now: NowPlaying) = sessions.openItems(queueSession(now))
}

/**
 * 主界面底部(导航栏上面)的迷你播放条:后台播放服务在放东西时出现 —— 曲名、播放 / 暂停、下一首、停止,
 * 顶上一条细进度;点一下打开那一首的预览。状态来自 [NowPlayingStore](不为了显示去拉起服务),
 * 按钮经 MediaController 控制,只在出现时才连。切 tab 不受影响(挂在 MainScaffold 上)。
 * [insetBottom]:下面没有导航栏(宽屏用侧边导航)时自己让出系统导航栏。
 */
@Composable
fun MiniPlayerBar(
    onOpenPreview: (nodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    insetBottom: Boolean = false,
    viewModel: MiniPlayerViewModel = hiltViewModel(),
) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val now = nowPlaying ?: return
    val controller by rememberMediaController()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .testTag("mini_player"),
    ) {
        Column(if (insetBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier) {
            LinearProgressIndicator(progress = { now.fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable {
                        viewModel.prepareOpen(now)
                        onOpenPreview(now.nodeId)
                    }
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    now.title,
                    modifier = Modifier.weight(1f).testTag("mini_player_title"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = {
                        val c = controller ?: return@IconButton
                        if (c.isPlaying) {
                            c.pause()
                        } else {
                            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                            if (c.playbackState == Player.STATE_IDLE) c.prepare()
                            c.play()
                        }
                    },
                    enabled = controller != null,
                    modifier = Modifier.testTag("mini_player_toggle"),
                ) {
                    Icon(
                        if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (now.isPlaying) R.string.preview_audio_pause else R.string.preview_audio_play),
                    )
                }
                IconButton(
                    onClick = { controller?.seekToNextMediaItem() },
                    enabled = controller != null && now.hasNext,
                    modifier = Modifier.testTag("mini_player_next"),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.media_next))
                }
                IconButton(
                    onClick = {
                        controller?.run {
                            stop()
                            clearMediaItems()
                        }
                    },
                    enabled = controller != null,
                    modifier = Modifier.testTag("mini_player_close"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_mini_close))
                }
            }
        }
    }
}
