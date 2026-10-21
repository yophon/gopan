package xyz.yophon.gopan.core.data.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * 服务端记录进度的门槛(doc/22 第八节决策 7):5 分钟以上的音视频、3 页以上的 PDF。
 * 客户端先筛一遍:一首三分钟的歌每 15 秒报一次纯属白跑(服务端也只会回 null)。
 */
object ProgressRules {
    const val MIN_MEDIA_MS = 5 * 60 * 1000L
    const val MIN_PDF_PAGES = 3

    fun mediaWorthSaving(durationMs: Long): Boolean = durationMs >= MIN_MEDIA_MS

    fun pdfWorthSaving(pageCount: Int): Boolean = pageCount >= MIN_PDF_PAGES
}

/**
 * 上报播放进度(尽力而为):在进程级作用域里发,离开页面、服务销毁时也能发出去;失败只记日志。
 * 同一个文件连着报同一秒(暂停后紧跟着离开页面)只发一次。
 */
@Singleton
class PlayProgressReporter @Inject constructor(
    private val repo: MediaRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lastSent = LinkedHashMap<String, Long>()

    /** 音视频,毫秒 */
    fun reportMedia(nodeId: String, positionMs: Long, durationMs: Long) {
        if (nodeId.isEmpty() || !ProgressRules.mediaWorthSaving(durationMs)) return
        val position = positionMs.coerceIn(0, durationMs) / 1000.0
        send(nodeId, position, durationMs / 1000.0)
    }

    /** PDF:[page] 从 1 起 */
    fun reportPdf(nodeId: String, page: Int, pageCount: Int) {
        if (nodeId.isEmpty() || !ProgressRules.pdfWorthSaving(pageCount)) return
        send(nodeId, page.coerceIn(1, pageCount).toDouble(), pageCount.toDouble())
    }

    private fun send(nodeId: String, position: Double, duration: Double) {
        val second = position.roundToLong()
        synchronized(lastSent) {
            if (lastSent[nodeId] == second) return
            lastSent.remove(nodeId)
            lastSent[nodeId] = second
            if (lastSent.size > MAX_REMEMBERED) lastSent.remove(lastSent.keys.first())
        }
        scope.launch {
            gopanResult { repo.saveProgress(nodeId, position, duration) }
                .onFailure { Timber.w("上报播放进度失败:%s", it.message) }
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 64
    }
}
