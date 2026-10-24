package xyz.yophon.gopan.feature.preview

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.core.data.media.MediaUrls
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.NowPlayingStore
import xyz.yophon.gopan.core.data.media.PlayProgressReporter
import xyz.yophon.gopan.core.data.media.QueueEntry
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import xyz.yophon.gopan.feature.media.ProgressTicker
import xyz.yophon.gopan.feature.media.finishedByTransition
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

/**
 * 音频的后台播放:Media3 的会话服务,播放时自动变成前台服务并出带控制按钮的通知;离开预览页、锁屏照常播。
 * - 队列(doc/22 二.4):整个文件夹的音频,mediaId 即节点 id,地址是占位的 `gopan-node://<nodeId>`,
 *   每首开播时才由 [ResolvingDataSource] 换成预签名地址(见 [MediaUrls]),队列再长也不会拿着过期地址;
 *   通知栏与锁屏的上一首 / 下一首由 media3 按队列给出。
 * - 预签名 15 分钟就过期:播放器拿到 403 时作废这首的地址重新准备,从原位置接着播。
 * - 播放进度:播放中每 15 秒、暂停、换曲、服务销毁时报给服务端(只记 5 分钟以上的,见 ProgressRules)。
 * - 「正在播放」写进 [NowPlayingStore](迷你播放条、预览页跟随翻页用)。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    @Named(PartUploader.TRANSFER_CLIENT)
    lateinit var client: OkHttpClient

    @Inject
    lateinit var mediaUrls: MediaUrls

    @Inject
    lateinit var reporter: PlayProgressReporter

    @Inject
    lateinit var nowPlaying: NowPlayingStore

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 最近一次在放时看到的「哪首、放到哪」:暂停、换曲、销毁时报给那一首;还没真正放过的不报 */
    private class Snapshot(val nodeId: String, val positionMs: Long, val durationMs: Long)

    private var snapshot: Snapshot? = null
    private val ticker = ProgressTicker()
    private var tickJob: Job? = null
    private var queue: List<QueueEntry> = emptyList()

    /** 每首最近一次因 403 换地址的时刻:刚换过又 403(签名配置有问题)就不再死循环 */
    private val refreshedAt = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        // 地址可能是离线副本的 file://(doc/26):DefaultDataSource 按 scheme 分给 OkHttp 或直接读文件
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(client))) { spec -> resolve(spec) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(PlayerListener(player))
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(SessionCallback).build()
        // 登出:停播并清空队列
        scope.launch {
            nowPlaying.stopRequests.collect {
                snapshot = null
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 划掉任务时:没在播就停掉服务,在播就继续(后台播放) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { if (snapshot?.nodeId == it.currentMediaItem?.mediaId) capture(it) }
        reportSnapshot()
        nowPlaying.update(null)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** 占位地址 → 现取的预签名地址(在播放器的加载线程上,阻塞等结果) */
    private fun resolve(spec: DataSpec): DataSpec {
        val nodeId = MediaUrls.nodeIdOf(spec.uri.toString()) ?: return spec
        val url = try {
            runBlocking { mediaUrls.resolve(nodeId) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("取播放地址失败", e)
        }
        return spec.withUri(url.toUri())
    }

    private inner class PlayerListener(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (isExpiredUrl(error)) refreshCurrentUrl(player) else Timber.w("音频播放失败:%s", error.errorCodeName)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                capture(player)
                startTicking(player)
            } else {
                tickJob?.cancel()
                ticker.reset()
                // 暂停、缓冲、放完:按停下的位置报(换曲引起的停顿由 onMediaItemTransition 报过了)
                if (snapshot?.nodeId == player.currentMediaItem?.mediaId) capture(player)
                reportSnapshot()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = snapshot
            if (previous != null) {
                val position = if (finishedByTransition(reason)) previous.durationMs else previous.positionMs
                reporter.reportMedia(previous.nodeId, position, previous.durationMs)
            }
            snapshot = null
            ticker.reset()
            if (player.isPlaying) capture(player)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            queue = (0 until player.mediaItemCount).map { i ->
                val item = player.getMediaItemAt(i)
                QueueEntry(item.mediaId, item.mediaMetadata.title?.toString().orEmpty())
            }
        }

        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** 播放中每秒:更新「正在播放」的进度;每 15 秒报一次播放进度 */
    private fun startTicking(player: Player) {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && player.isPlaying) {
                capture(player)
                publish(player)
                if (ticker.due(SystemClock.elapsedRealtime())) reportSnapshot()
                delay(1_000)
            }
        }
    }

    private fun capture(player: Player) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId.isEmpty()) return
        snapshot = Snapshot(item.mediaId, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    private fun reportSnapshot() {
        val s = snapshot ?: return
        reporter.reportMedia(s.nodeId, s.positionMs, s.durationMs)
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        if (item == null || item.mediaId.isEmpty()) {
            nowPlaying.update(null)
            return
        }
        nowPlaying.update(
            NowPlaying(
                nodeId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                isPlaying = player.isPlaying,
                hasNext = player.hasNextMediaItem(),
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                queue = queue,
            ),
        )
    }

    /** 这首的地址过期了:作废、重新准备(从出错时的位置接着放,解析器会现取新地址) */
    private fun refreshCurrentUrl(player: ExoPlayer) {
        val nodeId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val now = SystemClock.elapsedRealtime()
        val last = refreshedAt[nodeId]
        if (last != null && now - last < REFRESH_GUARD_MS) {
            Timber.w("刚换过地址又被拒绝,不再重试")
            return
        }
        refreshedAt[nodeId] = now
        mediaUrls.invalidate(nodeId)
        player.prepare()
    }

    /** 控制器(预览页、迷你播放条、系统媒体控制)加进来的条目一律换成占位地址,开播时再现取 */
    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.playable() }.toMutableList())

        private fun MediaItem.playable(): MediaItem =
            if (mediaId.isEmpty()) this else buildUpon().setUri(MediaUrls.placeholder(mediaId)).build()
    }

    companion object {
        private const val REFRESH_GUARD_MS = 30_000L

        /** 预签名过期(或签名不符):对象存储回 403 */
        fun isExpiredUrl(error: PlaybackException): Boolean {
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) return true
                cause = cause.cause
            }
            return false
        }

        /** 队列里的一首:mediaId 是节点 id,地址是占位的,开播时才取 */
        fun queueItem(entry: QueueEntry): MediaItem = MediaItem.Builder()
            .setMediaId(entry.nodeId)
            .setUri(MediaUrls.placeholder(entry.nodeId))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist("gopan").build())
            .build()
    }
}
