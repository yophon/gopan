package xyz.yophon.gopan.core.data.jobs

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.parseTime

/*
 * 后台任务(离线下载、解压)的展示口径,与网页端 web/src/utils/jobs.ts 一致。纯函数,单测直接引。
 * 状态文案与网页端逐字相同(同 ChatLogic.dayLabel 的做法,直接写中文,方便两端对照)。
 */

/** 一次最多提交的链接数(服务端同样限制) */
const val MAX_FETCH_URLS = 10

/** 打开传输列表时抽屉里放的最近结束的任务:10 分钟内结束的、24 小时内失败的 */
private const val RECENT_MS = 10 * 60 * 1000L
private const val FAILED_MS = 24 * 60 * 60 * 1000L

val JobStatus.isActive: Boolean get() = this == JobStatus.QUEUED || this == JobStatus.RUNNING

val BackgroundJob.isActive: Boolean get() = status.isActive

/** 已完成但有提示(GOT_HTML):界面用警告色,不算失败 */
val BackgroundJob.isWarning: Boolean get() = status == JobStatus.DONE && !errorCode.isNullOrEmpty()

val BackgroundJob.isRetryable: Boolean get() = status == JobStatus.FAILED || status == JobStatus.CANCELED

/** 列表里要不要列出来:进行中的、10 分钟内结束的、24 小时内失败的(失败了得让人看见) */
fun jobWorthShowing(job: BackgroundJob, nowMillis: Long): Boolean {
    if (job.isActive) return true
    val at = parseTime(job.finishedAt)?.toEpochMilli() ?: return false
    return nowMillis - at < if (job.status == JobStatus.FAILED) FAILED_MS else RECENT_MS
}

/** 进度百分比;总量未知时返回 null(进度条显示为不确定) */
fun jobPercent(job: BackgroundJob): Int? {
    if (job.status == JobStatus.DONE) return 100
    val filesTotal = job.filesTotal
    if (job.kind == JobKind.EXTRACT_ARCHIVE && filesTotal != null && filesTotal > 0) {
        return minOf(100, (job.filesDone.toLong() * 100 / filesTotal).toInt())
    }
    val bytesTotal = job.bytesTotal
    if (bytesTotal != null && bytesTotal > 0) return minOf(100L, job.bytesDone * 100 / bytesTotal).toInt()
    return null
}

/** 状态一行字。[speedBps] 由调用方按两次轮询的差算出,没有就不显示速度。 */
fun jobStatusText(job: BackgroundJob, speedBps: Long? = null): String = when (job.status) {
    JobStatus.QUEUED -> job.errorMessage?.takeIf { it.isNotEmpty() }?.let { "等待重试:$it" } ?: "排队中"
    JobStatus.RUNNING -> if (job.kind == JobKind.EXTRACT_ARCHIVE) {
        val total = job.filesTotal?.takeIf { it > 0 }?.let { " / $it" }.orEmpty()
        "解压中 ${job.filesDone}$total 个文件"
    } else {
        val total = job.bytesTotal?.takeIf { it > 0 }?.let { " / ${formatBytes(it)}" }.orEmpty()
        val speed = speedBps?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}/s" }.orEmpty()
        "${formatBytes(job.bytesDone)}$total$speed"
    }
    JobStatus.DONE -> when {
        job.errorCode == GOT_HTML -> "已完成,但拿到的是网页,不是文件"
        job.kind == JobKind.EXTRACT_ARCHIVE -> {
            val skipped = if (job.filesSkipped > 0) ",跳过 ${job.filesSkipped} 个" else ""
            "已解压 ${job.filesDone} 个文件$skipped"
        }
        else -> "已完成 · ${formatBytes(job.bytesDone)}"
    }
    JobStatus.FAILED -> job.errorMessage?.takeIf { it.isNotEmpty() } ?: "失败"
    JobStatus.CANCELED -> "已取消"
}

/** 「打开所在位置」去哪个文件夹;[folderId] 为 null 是根目录 */
@Immutable
data class FolderTarget(val folderId: String?)

/** 下载的文件进它所在的文件夹,解压出来的进新文件夹;没完成、产物已删的没有位置 */
fun jobLocation(job: BackgroundJob): FolderTarget? {
    if (job.status != JobStatus.DONE) return null
    val r = job.result ?: return null
    return FolderTarget(if (r.isFolder) r.id else r.parentId)
}

private val URL_PATTERN = Regex(
    // 链接里不收空白(含全角空格)、引号、尖括号、中英文括号与书名号、中文标点:「【标题】https://…」「链接:https://…,」这类粘着中文的也能切开。
    // 英文的 , ; ! ? 只有后面紧跟着 ASCII 可见字符时才算链接的一部分(?a=1,2 保留),「…/a.pdf,还有」在逗号处断开
    "https?://(?:[^\\s\\u00a0\\u3000<>\"'\\u300c\\u300d\\u300e\\u300f\\u3010\\u3011\\u300a\\u300b()\\uff08\\uff09\\uff0c\\u3002\\uff1b\\uff01\\uff1f\\u3001\\uff1a,;!?]" +
        "|[,;!?](?=[\\x21-\\x7e]))+",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_PUNCT = Regex("[,.;:!?\\]}\\u2026]+$")

/**
 * 从粘贴(或别的 App 分享来)的一段文字里挑出链接:只要 http(s),去重,保持顺序。
 * 典型输入是一行一个,也兼容「【标题】链接」这种链接和中文粘在一起的;结尾的中英文标点不算进链接。
 */
fun parseUrls(text: String): List<String> {
    if (!text.contains("http", ignoreCase = true)) return emptyList()
    val out = LinkedHashSet<String>()
    for (m in URL_PATTERN.findAll(text)) {
        val url = m.value.replace(TRAILING_PUNCT, "")
        // 只剩 "https://" 的不算
        if (url.length > url.indexOf("://") + 3) out += url
    }
    return out.toList()
}

/** 上一次轮询时某个任务的样子(算速度、发现刚完成的) */
@Immutable
data class JobSample(val status: JobStatus, val bytes: Long, val atMillis: Long)

/** 速度:两次轮询都在下载中时的字节差 / 时间差(字节每秒) */
fun jobSpeeds(previous: Map<String, JobSample>, jobs: List<BackgroundJob>, nowMillis: Long): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (j in jobs) {
        val prev = previous[j.id] ?: continue
        if (j.status == JobStatus.RUNNING && prev.status == JobStatus.RUNNING && nowMillis > prev.atMillis) {
            out[j.id] = maxOf(0L, (j.bytesDone - prev.bytes) * 1000 / (nowMillis - prev.atMillis))
        }
    }
    return out
}

/** 上次还没完成、这次变成已完成的任务(要刷新它们的目标文件夹) */
fun justFinished(previous: Map<String, JobSample>, jobs: List<BackgroundJob>): List<BackgroundJob> =
    jobs.filter { j ->
        val prev = previous[j.id]
        prev != null && prev.status != JobStatus.DONE && j.status == JobStatus.DONE
    }

fun jobSamples(jobs: List<BackgroundJob>, nowMillis: Long): Map<String, JobSample> =
    jobs.associate { it.id to JobSample(it.status, it.bytesDone, nowMillis) }

const val GOT_HTML = "GOT_HTML"

/** 提交 / 取消 / 重试的错误码 → 中文(与网页端 api/messages.ts 同一套文案) */
object JobErrors {
    private val messages = mapOf(
        "FETCH_DISABLED" to "本站没有开启离线下载",
        "INVALID_URL" to "链接格式不对,只支持 http / https",
        "INVALID_INPUT" to "链接无效或一次超过 $MAX_FETCH_URLS 条",
        "TOO_MANY_JOBS" to "排队中的后台任务过多,等前面的完成再来",
        "JOB_FINISHED" to "任务已经结束",
        "JOB_NOT_RETRYABLE" to "只有失败或已取消的任务能重试",
        "NOT_A_FOLDER" to "目标不是文件夹",
    )

    /** 服务端 message 是中文时用它(带了具体是哪条链接、上限多少);否则按错误码;都没有用 GopanException 自己的文案 */
    fun messageFor(e: Throwable): String {
        val ge = e as? GopanException ?: e.toGopanException()
        val server = ge.serverMessage?.trim().orEmpty()
        if (server.isNotEmpty() && server.any { it.code > 0x7f }) return server
        return messages[ge.code] ?: ge.message ?: "请求失败"
    }
}
