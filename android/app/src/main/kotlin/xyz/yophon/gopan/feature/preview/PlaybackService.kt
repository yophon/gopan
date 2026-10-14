package xyz.yophon.gopan.feature.preview

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import javax.inject.Inject
import javax.inject.Named

/**
 * 音频的后台播放:Media3 的会话服务,播放时自动变成前台服务并出带控制按钮的通知;离开预览页、锁屏照常播。
 * 预签名地址 15 分钟就过期:播放器拿到 403 时按 mediaId(节点 id)重新取一次地址,从原位置接着播。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var previews: PreviewRepository

    @Inject
    @Named(PartUploader.TRANSFER_CLIENT)
    lateinit var client: OkHttpClient

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshing = false

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(client)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (isExpiredUrl(error)) refreshCurrentUrl(player) else Timber.w("音频播放失败:%s", error.errorCodeName)
            }
        })
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 划掉任务时:没在播就停掉服务,在播就继续(后台播放) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun refreshCurrentUrl(player: ExoPlayer) {
        if (refreshing) return
        val item = player.currentMediaItem ?: return
        val nodeId = item.mediaId.takeIf { it.isNotEmpty() } ?: return
        val position = player.currentPosition
        refreshing = true
        scope.launch {
            try {
                val url = previews.preview(nodeId).contentUrl ?: return@launch
                player.setMediaItem(item.buildUpon().setUri(url).build(), position)
                player.prepare()
                player.play()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w("刷新音频地址失败:%s", e.javaClass.simpleName)
            } finally {
                refreshing = false
            }
        }
    }

    companion object {
        /** 预签名过期(或签名不符):对象存储回 403 */
        fun isExpiredUrl(error: PlaybackException): Boolean {
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) return true
                cause = cause.cause
            }
            return false
        }

        fun mediaItem(nodeId: String, url: String, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(nodeId)
            .setUri(url)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist("gopan").build())
            .build()
    }
}
