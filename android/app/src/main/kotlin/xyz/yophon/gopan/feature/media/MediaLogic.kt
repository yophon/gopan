package xyz.yophon.gopan.feature.media

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.media.LyricLine
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.QueueEntry
import xyz.yophon.gopan.feature.preview.PreviewItem
import java.util.Locale

// 影音(doc/22 二)的纯逻辑:续播、进度文案、连播队列、下一集、跟随翻页、上报节奏。界面与播放服务共用,JVM 单测覆盖。

/** 进度超过这么多才自动跳过去(doc/22 二.3) */
const val RESUME_MIN_MS = 10_000L

/** 播放中每隔这么久报一次进度 */
const val REPORT_INTERVAL_MS = 15_000L

/** PDF 停在一页这么久才报 */
const val PDF_REPORT_DELAY_MS = 5_000L

/** 下一集倒计时(秒) */
const val NEXT_EPISODE_COUNTDOWN = 5

/**
 * 歌词当前该亮哪一行(doc/31 二):最后一个时间不晚于播放位置的;还没到第一行为 -1。提前 50 毫秒亮。
 */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    val t = positionMs / 1000.0 + 0.05
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].t <= t) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/**
 * 打开音视频时从哪接着放(毫秒);null = 从头。
 * 看完了、不到 10 秒、已经到了末尾(或超出总长,文件被换过)的都从头。
 */
fun resumePositionMs(progress: PlayProgress?): Long? {
    val p = progress ?: return null
    if (p.finished) return null
    val ms = (p.position * 1000).toLong()
    if (ms <= RESUME_MIN_MS) return null
    if (p.duration > 0 && ms >= (p.duration * 1000).toLong()) return null
    return ms
}

/**
 * PDF 打开时停在哪一页(从 1 起):内容搜索带来的命中页优先;其次没看完的进度(第 1 页不用跳);都没有为 null。
 * 超出页数的由 PdfDocument.startIndex 收到最后一页。
 */
fun pdfStartPage(hitPage: Int?, progress: PlayProgress?): Int? {
    if (hitPage != null && hitPage > 0) return hitPage
    val p = progress ?: return null
    if (p.finished) return null
    return p.position.toInt().takeIf { it > 1 }
}

/** 进度里的时刻:m:ss,一小时以上 h:mm:ss(「从 23:15 继续」「看到 23:15 / 1:42:00」) */
fun formatPlayTime(seconds: Double): String {
    val total = if (seconds.isNaN()) 0L else seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun formatPlayTimeMs(ms: Long): String = formatPlayTime(ms / 1000.0)

/** 按页记进度的类型(PDF 与 Office 派生的 PDF);其余按时间 */
fun isPaged(kind: PreviewKind): Boolean = kind == PreviewKind.PDF || kind == PreviewKind.NATIVE_PDF || kind == PreviewKind.OFFICE

/** 「最近播放」第二行的进度:看到 23:15 / 1:42:00,或第 12 / 300 页 */
sealed interface ProgressText {
    data class Time(val position: String, val duration: String) : ProgressText

    data class Pages(val page: Int, val count: Int) : ProgressText
}

fun progressText(kind: PreviewKind, progress: PlayProgress): ProgressText =
    if (isPaged(kind)) {
        val count = progress.duration.toInt().coerceAtLeast(1)
        ProgressText.Pages(progress.position.toInt().coerceIn(1, count), count)
    } else {
        ProgressText.Time(formatPlayTime(progress.position), formatPlayTime(progress.duration))
    }

/** 进度条的比例(0..1) */
fun progressFraction(progress: PlayProgress): Float =
    if (progress.duration > 0) (progress.position / progress.duration).toFloat().coerceIn(0f, 1f) else 0f

/** 连播队列与起点 */
@Immutable
data class QueuePlan(val entries: List<QueueEntry>, val startIndex: Int)

/**
 * 音乐连播的队列:同文件夹的音频(服务端按自然顺序排好,含它自己),从 [startId] 开始。
 * 列表里没有它(查失败、服务端没给)就只放它自己。
 */
fun audioQueue(playlist: List<Node>, startId: String, startTitle: String): QueuePlan {
    val files = playlist.filter { !it.isFolder }.distinctBy { it.id }
    val index = files.indexOfFirst { it.id == startId }
    if (index < 0) return QueuePlan(listOf(QueueEntry(startId, startTitle)), 0)
    return QueuePlan(files.map { QueueEntry(it.id, it.name) }, index)
}

/** 点迷你播放条打开的预览:左右翻的是整个播放队列(列表顺序);队列里没有正在放的那首时只放它 */
fun queueSession(now: NowPlaying): List<PreviewItem> {
    val queue = now.queue.distinctBy { it.nodeId }
    if (queue.none { it.nodeId == now.nodeId }) return listOf(PreviewItem(now.nodeId, now.title, null))
    return queue.map { PreviewItem(it.nodeId, it.title, null) }
}

/** 视频的下一集:同文件夹视频(自然顺序)里排在 [currentId] 后面的那个;它是最后一个或不在列表里为 null */
fun nextInPlaylist(playlist: List<Node>, currentId: String): Node? {
    val index = playlist.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return playlist.drop(index + 1).firstOrNull { !it.isFolder && it.id != currentId }
}

/**
 * 预览页跟着播放器翻页:正在看的就是刚才在放的那首,播放器换到了(上一首 / 下一首 / 放完自动接)
 * 翻页列表里的另一首 → 返回它的位置;否则 null(用户自己翻走了、换到的那首不在列表里)。
 */
fun followPage(previousId: String?, playingId: String?, currentPageId: String?, pageIds: List<String>): Int? {
    if (previousId == null || playingId == null || playingId == previousId) return null
    if (currentPageId != previousId) return null
    return pageIds.indexOf(playingId).takeIf { it >= 0 }
}

/** 循环方式轮换:顺序播放 → 列表循环 → 单曲循环 → 顺序播放 */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

/** 换曲时上一首算不算放完了:自动接下一首、单曲循环重来都是放完;手动切、换队列按当时的位置记 */
fun finishedByTransition(reason: Int): Boolean =
    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

/**
 * 播放中每 [intervalMs] 报一次进度:每秒心跳调 [due];第一次心跳只起算。
 * 暂停、换曲时 [reset](那时由调用方立即另报一次)。
 */
class ProgressTicker(private val intervalMs: Long = REPORT_INTERVAL_MS) {
    private var since: Long? = null

    fun due(nowMs: Long): Boolean {
        val start = since
        if (start == null) {
            since = nowMs
            return false
        }
        if (nowMs - start < intervalMs) return false
        since = nowMs
        return true
    }

    fun reset() {
        since = null
    }
}
