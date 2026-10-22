package xyz.yophon.gopan.feature.clips

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.yophon.gopan.core.data.clips.Clip
import xyz.yophon.gopan.core.data.jobs.parseUrls

/** 剪藏的纯函数(单测覆盖) */
object ClipLogic {

    /**
     * 系统分享来的文字里的第一个 http(s) 网址:浏览器分享网页时 text 是网址或「标题 网址」,subject 是标题。
     * 和中文粘在一起的(「看这篇(https://…)。」)也能切开,结尾的中英文标点不算进网址。没有网址为 null
     */
    fun sharedUrl(subject: String?, text: String?): String? =
        parseUrls(text.orEmpty()).firstOrNull() ?: parseUrls(subject.orEmpty()).firstOrNull()

    /** 网址的主机名(去掉 www.);认不出为空串 */
    fun host(url: String): String = url.trim().toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()

    /** 列表与来源条上的站点名:没认出站点名时用主机名 */
    fun siteLabel(clip: Clip): String = clip.site.trim().ifEmpty { host(clip.url) }

    /** 打开阅读页后过这么久自动标为已读 */
    const val AUTO_READ_MS = 3_000L
}
