package xyz.yophon.gopan.core.data.media

import androidx.compose.runtime.Immutable
import okhttp3.HttpUrl.Companion.toHttpUrl
import xyz.yophon.gopan.core.data.Node

/**
 * 视频的外挂字幕(doc/22 二.2):同文件夹里同名的 .srt / .ass / .ssa / .vtt,服务端一律转成 WebVTT。
 * [url] 是 `{base}/sub/{nodeId}`,要登录态(媒体客户端只给这个路径加 Bearer,见 [SubtitleAuthInterceptor])。
 */
@Immutable
data class SubtitleTrack(
    val nodeId: String,
    val name: String,
    /** 显示名:简体中文 / English …;认不出时是文件名里的标记或「字幕」 */
    val label: String,
    /** BCP 47,认不出为空串 */
    val lang: String,
    /** 默认选中的一条(中文优先,其次第一条) */
    val default: Boolean,
    val url: String,
)

/**
 * 播放进度(doc/22 二.3):音视频的 [position] / [duration] 是秒,PDF 是页码(从 1 起)/ 总页数。
 * [finished]:看完了(剩余不到 3% 或不到 20 秒,PDF 翻到最后一页),下次从头开始。
 */
@Immutable
data class PlayProgress(
    val position: Double,
    val duration: Double,
    val finished: Boolean,
    val updatedAt: String? = null,
)

/** 「最近播放」的一条:没看完的,最近的在前 */
@Immutable
data class RecentPlay(val node: Node, val progress: PlayProgress)

/** 字幕地址:{base}/sub/{nodeId}(凭证走 Authorization 头,不进 URL) */
fun subtitleUrl(baseUrl: String, nodeId: String): String =
    baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        .encodedPath("/")
        .addPathSegment("sub")
        .addPathSegment(nodeId)
        .build()
        .toString()
