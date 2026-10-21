package xyz.yophon.gopan.feature.preview

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * 连到 [PlaybackService] 的控制器;离开组合(或 [enabled] 变 false)时断开,在放的照常放,通知栏可控。
 * 连接会拉起服务:迷你播放条只在服务已经在放东西时才连([enabled])。
 */
@Composable
internal fun rememberMediaController(enabled: Boolean = true): State<MediaController?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { state.value = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            state.value = null
            MediaController.releaseFuture(future)
        }
    }
    return state
}

/** 队列里某个节点的位置;不在队列里为 -1 */
internal fun MediaController.indexOfMedia(nodeId: String): Int =
    (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == nodeId } ?: -1
