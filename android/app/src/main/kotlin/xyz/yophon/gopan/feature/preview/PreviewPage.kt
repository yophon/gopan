package xyz.yophon.gopan.feature.preview

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.archive.ArchiveRepository
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.SubtitleTrack
import xyz.yophon.gopan.core.data.preview.DerivativeStatus
import xyz.yophon.gopan.core.data.preview.PreviewInfo
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.archive.ArchiveSummary
import xyz.yophon.gopan.feature.archive.summary
import java.io.File

/** 一页预览的内容 */
sealed interface PreviewContent {
    data object Loading : PreviewContent

    data class Error(val message: String) : PreviewContent

    /** [original]:原图(可能很大,解码时限制边长);否则是 2048 的大图派生 */
    data class Image(val url: String, val cacheKey: String, val original: Boolean) : PreviewContent

    /** [subtitles]:外挂字幕;[progress]:上次看到哪(续播用) */
    data class Video(
        val url: String,
        val posterUrl: String?,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val progress: PlayProgress? = null,
    ) : PreviewContent

    data class Audio(val url: String, val durationSec: Int?, val progress: PlayProgress? = null) : PreviewContent

    /** PDF(含 Office 派生)正在下到缓存;[fraction] 未知时为 null */
    data class PdfDownloading(val fraction: Float?) : PreviewContent

    data class Pdf(val file: File) : PreviewContent

    /** Markdown:渲染好的页面(WebView 打开 /md/{id},doc/23 五.2);可切回文本 */
    data object Markdown : PreviewContent

    /** [loadingMore] 正在拉下一段;[moreError] 上次「继续加载」失败的原因 */
    data class Text(val content: TextContent, val loadingMore: Boolean = false, val moreError: String? = null) : PreviewContent

    data object OfficeConverting : PreviewContent

    /** 本站没开 Office 转换(未部署 Gotenberg) */
    data object OfficeUnavailable : PreviewContent

    data object OfficeFailed : PreviewContent

    /** 压缩包:概要卡片(格式、文件数、解压后大小),浏览与解压的入口;还在读目录时每 2 秒刷新 */
    data class Archive(val summary: ArchiveSummary) : PreviewContent

    data object Unsupported : PreviewContent
}

@Immutable
data class PreviewPageState(val info: PreviewInfo? = null, val content: PreviewContent = PreviewContent.Loading) {
    /** 正在看的是大图派生,还有原图可看 */
    val canShowOriginal: Boolean
        get() = (content as? PreviewContent.Image)?.original == false && info?.contentUrl != null

    /** Markdown:顶栏的「看源文件 / 渲染显示」切换;null = 不是能渲染的 .md(或还在加载) */
    val markdownRendered: Boolean?
        get() = when {
            info?.markdown != true -> null
            content is PreviewContent.Markdown -> true
            content is PreviewContent.Text -> false
            else -> null
        }
}

/**
 * 一个文件的预览页:按服务端给的预览类型准备内容。预签名地址 15 分钟有效,每次打开现取;
 * Office 没有派生 PDF 时触发一次转换,转换中每 2 秒查一次状态 —— 只在这页显示着([setAttached])时查;
 * 压缩包还在读目录时同样每 2 秒查一次概要。
 * 由 [PreviewViewModel] 持有:最近看过的几页留着(翻回来不重新加载),翻远了取消并释放。
 */
class PreviewPage(
    val nodeId: String,
    private val repo: PreviewRepository,
    private val scope: CoroutineScope,
    private val archives: ArchiveRepository,
) {
    private val _state = MutableStateFlow(PreviewPageState())
    val state: StateFlow<PreviewPageState> = _state.asStateFlow()

    private val attached = MutableStateFlow(false)
    private var job: Job? = null
    private var officeRequested = false

    /** Markdown 切到源文件时读出来的文本(切回来再切过去不重新读) */
    private var markdownSource: PreviewContent.Text? = null

    /** 第一次取用时开始加载 */
    fun start() {
        if (job == null) load()
    }

    fun setAttached(value: Boolean) {
        attached.value = value
    }

    fun retry() = load()

    /** Office 转换失败后重新触发 */
    fun retryConversion() {
        job?.cancel()
        officeRequested = true
        set(PreviewContent.OfficeConverting)
        job = scope.launch {
            gopanResult { repo.requestPreview(nodeId) }
            pollOffice()
        }
    }

    /** 文本:接着拉下一段(地址过期时换一次地址再试) */
    fun loadMoreText() {
        val current = _state.value.content as? PreviewContent.Text ?: return
        if (!current.content.truncated || current.loadingMore) return
        set(current.copy(loadingMore = true, moreError = null))
        scope.launch {
            val c = current.content
            var url = _state.value.info?.contentUrl
            var result = url?.let { gopanResult { repo.fetchText(it, c.nextOffset, c.charset) } }
            if (result == null || result.isFailure) {
                url = freshContentUrl()
                result = url?.let { gopanResult { repo.fetchText(it, c.nextOffset, c.charset) } } ?: result
            }
            val now = _state.value.content as? PreviewContent.Text ?: return@launch
            result?.onSuccess { next ->
                set(
                    PreviewContent.Text(
                        c.copy(text = c.text + next.text, truncated = next.truncated, nextOffset = next.nextOffset, totalBytes = next.totalBytes ?: c.totalBytes),
                    ),
                )
            }?.onFailure { e -> set(now.copy(loadingMore = false, moreError = e.message ?: "加载失败")) }
                ?: set(now.copy(loadingMore = false, moreError = "加载失败"))
        }
    }

    /** Markdown:切到源文件(第一次才读文本;地址过期时换一次地址再试) */
    fun showMarkdownSource() {
        if (_state.value.content != PreviewContent.Markdown) return
        markdownSource?.let { return set(it) }
        job?.cancel()
        set(PreviewContent.Loading)
        job = scope.launch {
            var result = _state.value.info?.contentUrl?.let { url -> gopanResult { repo.fetchText(url) } }
            if (result == null || result.isFailure) {
                result = freshContentUrl()?.let { url -> gopanResult { repo.fetchText(url) } } ?: result
            }
            result?.onSuccess { set(PreviewContent.Text(it)) }?.onFailure { set(PreviewContent.Error(it.message ?: "文本加载失败")) }
                ?: set(PreviewContent.Error("文本加载失败"))
        }
    }

    /** Markdown:从源文件切回渲染 */
    fun showMarkdownRendered() {
        val text = _state.value.content as? PreviewContent.Text ?: return
        if (_state.value.info?.markdown != true) return
        markdownSource = text
        set(PreviewContent.Markdown)
    }

    /** 图片:换成原图(网页端的「查看原图」) */
    fun showOriginal() {
        val info = _state.value.info ?: return
        val url = info.contentUrl ?: return
        set(PreviewContent.Image(url, "${info.contentKey}#original", original = true))
    }

    /** 音视频播放中途地址过期:重新取一次 contentUrl */
    suspend fun freshContentUrl(): String? {
        val info = gopanResult { repo.preview(nodeId) }.getOrNull() ?: return null
        _state.update { it.copy(info = info) }
        return info.contentUrl
    }

    fun cancel() {
        job?.cancel()
    }

    private fun load() {
        job?.cancel()
        markdownSource = null
        set(PreviewContent.Loading)
        job = scope.launch {
            val info = gopanResult { repo.preview(nodeId) }.getOrElse {
                set(PreviewContent.Error(it.message ?: "加载失败"))
                return@launch
            }
            _state.update { it.copy(info = info) }
            prepare(info)
        }
    }

    private suspend fun prepare(info: PreviewInfo) {
        when (info.kind) {
            PreviewKind.IMAGE -> set(
                when {
                    info.largeUrl != null -> PreviewContent.Image(info.largeUrl, "${info.contentKey}#large", original = false)
                    info.contentUrl != null -> PreviewContent.Image(info.contentUrl, "${info.contentKey}#original", original = true)
                    else -> PreviewContent.Unsupported
                },
            )
            PreviewKind.VIDEO -> set(
                info.contentUrl?.let { PreviewContent.Video(it, info.largeUrl, info.subtitles, info.progress) } ?: PreviewContent.Unsupported,
            )
            PreviewKind.AUDIO -> set(info.contentUrl?.let { PreviewContent.Audio(it, info.durationSec, info.progress) } ?: PreviewContent.Unsupported)
            PreviewKind.PDF, PreviewKind.NATIVE_PDF -> {
                val url = info.contentUrl ?: return set(PreviewContent.Unsupported)
                downloadPdf(url, info.contentKey)
            }
            // 能渲染的 .md 默认渲染着看,文本等切到源文件时再读
            PreviewKind.TEXT -> {
                if (info.markdown) return set(PreviewContent.Markdown)
                val url = info.contentUrl ?: return set(PreviewContent.Unsupported)
                gopanResult { repo.fetchText(url) }.fold(
                    onSuccess = { set(PreviewContent.Text(it)) },
                    onFailure = { set(PreviewContent.Error(it.message ?: "文本加载失败")) },
                )
            }
            PreviewKind.OFFICE -> office(info)
            PreviewKind.ARCHIVE -> archive()
            PreviewKind.NONE -> set(PreviewContent.Unsupported)
        }
    }

    private suspend fun office(info: PreviewInfo) {
        when (info.status) {
            DerivativeStatus.DONE -> {
                val url = info.contentUrl ?: return set(PreviewContent.OfficeFailed)
                downloadPdf(url, "${info.contentKey}.office")
            }
            DerivativeStatus.UNAVAILABLE -> set(PreviewContent.OfficeUnavailable)
            DerivativeStatus.FAILED -> set(PreviewContent.OfficeFailed)
            null -> {
                // 从没转换过:触发一次(已有产物时服务端直接回 DONE)
                set(PreviewContent.OfficeConverting)
                if (!officeRequested) {
                    officeRequested = true
                    val status = gopanResult { repo.requestPreview(nodeId) }.getOrNull()
                    if (status == DerivativeStatus.UNAVAILABLE) return set(PreviewContent.OfficeUnavailable)
                }
                pollOffice()
            }
            DerivativeStatus.PENDING, DerivativeStatus.RUNNING -> {
                set(PreviewContent.OfficeConverting)
                pollOffice()
            }
        }
    }

    private suspend fun pollOffice() {
        while (true) {
            delay(OFFICE_POLL_MS)
            // 翻走了就先不查,翻回来接着查
            attached.first { it }
            val info = gopanResult { repo.preview(nodeId) }.getOrNull() ?: continue
            _state.update { it.copy(info = info) }
            when (info.status) {
                DerivativeStatus.PENDING, DerivativeStatus.RUNNING, null -> continue
                else -> return office(info)
            }
        }
    }

    /** 压缩包:先看概要;服务端还在读目录(PENDING)就每 2 秒再问,读完换成概要卡片 */
    private suspend fun archive() {
        val info = gopanResult { archives.info(nodeId) }.getOrElse {
            set(PreviewContent.Error(it.message ?: "读取压缩包失败"))
            return
        }
        set(PreviewContent.Archive(info.summary()))
        if (info.status != ArchiveStatus.PENDING) return
        while (true) {
            delay(ARCHIVE_POLL_MS)
            attached.first { it }
            val next = gopanResult { archives.info(nodeId) }.getOrNull() ?: continue
            set(PreviewContent.Archive(next.summary()))
            if (next.status != ArchiveStatus.PENDING) return
        }
    }

    private suspend fun downloadPdf(url: String, key: String) {
        set(PreviewContent.PdfDownloading(null))
        var lastPercent = -1
        gopanResult {
            repo.pdfFile(url, key) { done, total ->
                if (total != null && total > 0) {
                    val percent = (done * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        set(PreviewContent.PdfDownloading(percent / 100f))
                    }
                }
            }
        }.fold(
            onSuccess = { set(PreviewContent.Pdf(it)) },
            onFailure = { set(PreviewContent.Error(it.message ?: "加载失败")) },
        )
    }

    private fun set(content: PreviewContent) {
        _state.update { it.copy(content = content) }
    }

    companion object {
        const val OFFICE_POLL_MS = 2_000L
        const val ARCHIVE_POLL_MS = 2_000L
    }
}

/** 预览页拿到的信息转成列表模型(下载 / 打开方式 / 发到传输助手用) */
fun PreviewInfo.toNode(): Node = Node(
    id = nodeId,
    parentId = parentId,
    name = name,
    kind = NodeKind.FILE,
    size = size,
    mime = mime,
    updatedAt = updatedAt,
)
