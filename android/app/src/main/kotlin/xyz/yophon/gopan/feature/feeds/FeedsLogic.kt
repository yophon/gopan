package xyz.yophon.gopan.feature.feeds

import androidx.annotation.StringRes
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.feeds.Feed
import xyz.yophon.gopan.core.data.feeds.FeedItem
import xyz.yophon.gopan.core.data.feeds.FeedItemState
import xyz.yophon.gopan.core.data.feeds.FeedKind
import xyz.yophon.gopan.core.data.feeds.FeedMode
import xyz.yophon.gopan.core.data.feeds.FeedPreview

/** 订阅页的纯函数(单测覆盖):标签文案、能选的方式、条目上能做什么、轮询后的合并 */
object FeedsLogic {

    /**
     * 类型标签:RSS / 播客 / GitHub。订阅本身没记「是不是播客」,下载附件的 RSS 订阅基本都是播客(添加时也是看出播客才建议下载),
     * 就按它算。
     */
    @StringRes
    fun kindLabel(feed: Feed): Int = when {
        feed.kind == FeedKind.GITHUB -> R.string.feeds_kind_github
        feed.mode == FeedMode.DOWNLOAD -> R.string.feeds_kind_podcast
        else -> R.string.feeds_kind_rss
    }

    @StringRes
    fun kindLabel(preview: FeedPreview): Int = when {
        preview.kind == FeedKind.GITHUB -> R.string.feeds_kind_github
        preview.podcast -> R.string.feeds_kind_podcast
        else -> R.string.feeds_kind_rss
    }

    @StringRes
    fun modeLabel(mode: FeedMode): Int = when (mode) {
        FeedMode.DOWNLOAD -> R.string.feeds_mode_download
        FeedMode.CLIP -> R.string.feeds_mode_clip
        FeedMode.NONE -> R.string.feeds_mode_none
    }

    @StringRes
    fun modeDescription(mode: FeedMode): Int = when (mode) {
        FeedMode.DOWNLOAD -> R.string.feeds_mode_download_desc
        FeedMode.CLIP -> R.string.feeds_mode_clip_desc
        FeedMode.NONE -> R.string.feeds_mode_none_desc
    }

    /** 条目状态:新 / 已跳过 / 处理中 / 已完成 / 失败 */
    @StringRes
    fun stateLabel(state: FeedItemState): Int = when (state) {
        FeedItemState.NEW -> R.string.feeds_state_new
        FeedItemState.SKIPPED -> R.string.feeds_state_skipped
        FeedItemState.QUEUED -> R.string.feeds_state_queued
        FeedItemState.DONE -> R.string.feeds_state_done
        FeedItemState.FAILED -> R.string.feeds_state_failed
    }

    /** 能选的方式:GitHub 没有正文可剪,只有下载附件与只记录 */
    fun modesFor(kind: FeedKind): List<FeedMode> =
        if (kind == FeedKind.GITHUB) listOf(FeedMode.DOWNLOAD, FeedMode.NONE) else listOf(FeedMode.DOWNLOAD, FeedMode.CLIP, FeedMode.NONE)

    /** 预览后默认选中的方式:服务端建议的(播客、GitHub 是下载附件),不在可选范围里时取第一个 */
    fun defaultMode(preview: FeedPreview): FeedMode {
        val modes = modesFor(preview.kind)
        return preview.suggestedMode.takeIf { it in modes } ?: modes.first()
    }

    /** 一个条目上能点的:下载附件、剪藏正文、打开下好的文件 */
    data class ItemActions(val download: Boolean, val clip: Boolean, val open: Boolean)

    /**
     * 处理中的不能再点;已经有文件的只给「打开」(文件被删了、清掉了才能再下);
     * 下载要有附件,剪藏要有原文链接、且不是 GitHub 订阅
     */
    fun actionsFor(item: FeedItem, feedKind: FeedKind): ItemActions {
        val open = item.node != null && !item.node.isFolder
        if (item.state == FeedItemState.QUEUED || item.node != null) return ItemActions(download = false, clip = false, open = open)
        return ItemActions(
            download = item.enclosureUrl.isNotEmpty(),
            clip = feedKind != FeedKind.GITHUB && item.url.isNotEmpty(),
            open = false,
        )
    }

    /** 有处理中的条目:页面开着时隔几秒刷新一次,等它们变成已完成 / 失败 */
    fun hasQueued(items: List<FeedItem>): Boolean = items.any { it.state == FeedItemState.QUEUED }

    /**
     * 轮询拿到的第一页并进手上的列表:同一条换成新的,第一页里新出现的放最前(新的在前),
     * 已经翻到的后面几页原样留着
     */
    fun merge(current: List<FeedItem>, firstPage: List<FeedItem>): List<FeedItem> {
        val fresh = firstPage.associateBy { it.id }
        val known = current.mapTo(HashSet()) { it.id }
        val added = firstPage.filter { it.id !in known }
        return added + current.map { fresh[it.id] ?: it }
    }

    /** 默认存放文件夹的说明里用的名字 */
    fun defaultFolderName(preview: FeedPreview): String = preview.title.trim().ifEmpty { preview.url }
}
