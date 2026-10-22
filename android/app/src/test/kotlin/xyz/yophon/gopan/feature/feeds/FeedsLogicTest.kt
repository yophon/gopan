package xyz.yophon.gopan.feature.feeds

import com.apollographql.apollo.api.Optional
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.feeds.Feed
import xyz.yophon.gopan.core.data.feeds.FeedItem
import xyz.yophon.gopan.core.data.feeds.FeedItemNode
import xyz.yophon.gopan.core.data.feeds.FeedItemState
import xyz.yophon.gopan.core.data.feeds.FeedKind
import xyz.yophon.gopan.core.data.feeds.FeedMode
import xyz.yophon.gopan.core.data.feeds.FeedPreview
import xyz.yophon.gopan.core.data.feeds.FeedUpdates
import xyz.yophon.gopan.core.data.notifications.NotificationTarget
import xyz.yophon.gopan.core.data.notifications.NotificationTargetKind
import xyz.yophon.gopan.feature.notifications.NotificationNav
import xyz.yophon.gopan.feature.notifications.NotificationsLogic
import xyz.yophon.gopan.graphql.type.FeedMode as GqlFeedMode

/** 订阅页的标签、能做的操作、轮询合并、暂停时带上的设置 */
class FeedsLogicTest {

    private val rss = Feed(id = "f1", kind = FeedKind.RSS, url = "https://example.com/feed.xml", title = "博客")

    @Test
    fun `item state labels`() {
        assertThat(FeedItemState.entries.map { FeedsLogic.stateLabel(it) }).containsExactly(
            R.string.feeds_state_new,
            R.string.feeds_state_skipped,
            R.string.feeds_state_queued,
            R.string.feeds_state_done,
            R.string.feeds_state_failed,
        ).inOrder()
    }

    @Test
    fun `mode labels and descriptions`() {
        assertThat(FeedsLogic.modeLabel(FeedMode.DOWNLOAD)).isEqualTo(R.string.feeds_mode_download)
        assertThat(FeedsLogic.modeLabel(FeedMode.CLIP)).isEqualTo(R.string.feeds_mode_clip)
        assertThat(FeedsLogic.modeLabel(FeedMode.NONE)).isEqualTo(R.string.feeds_mode_none)
        assertThat(FeedMode.entries.map { FeedsLogic.modeDescription(it) }.toSet()).hasSize(3)
    }

    @Test
    fun `kind tag - GitHub, podcast (rss that downloads), plain rss`() {
        assertThat(FeedsLogic.kindLabel(rss)).isEqualTo(R.string.feeds_kind_rss)
        assertThat(FeedsLogic.kindLabel(rss.copy(mode = FeedMode.CLIP))).isEqualTo(R.string.feeds_kind_rss)
        assertThat(FeedsLogic.kindLabel(rss.copy(mode = FeedMode.DOWNLOAD))).isEqualTo(R.string.feeds_kind_podcast)
        assertThat(FeedsLogic.kindLabel(rss.copy(kind = FeedKind.GITHUB, mode = FeedMode.DOWNLOAD))).isEqualTo(R.string.feeds_kind_github)

        val preview = FeedPreview(kind = FeedKind.RSS, url = "u", title = "t")
        assertThat(FeedsLogic.kindLabel(preview)).isEqualTo(R.string.feeds_kind_rss)
        assertThat(FeedsLogic.kindLabel(preview.copy(podcast = true))).isEqualTo(R.string.feeds_kind_podcast)
        assertThat(FeedsLogic.kindLabel(preview.copy(kind = FeedKind.GITHUB))).isEqualTo(R.string.feeds_kind_github)
    }

    @Test
    fun `GitHub can only download or record, and the suggested mode is preselected`() {
        assertThat(FeedsLogic.modesFor(FeedKind.RSS)).containsExactly(FeedMode.DOWNLOAD, FeedMode.CLIP, FeedMode.NONE).inOrder()
        assertThat(FeedsLogic.modesFor(FeedKind.GITHUB)).containsExactly(FeedMode.DOWNLOAD, FeedMode.NONE).inOrder()

        val podcast = FeedPreview(kind = FeedKind.RSS, url = "u", title = "播客", podcast = true, suggestedMode = FeedMode.DOWNLOAD)
        assertThat(FeedsLogic.defaultMode(podcast)).isEqualTo(FeedMode.DOWNLOAD)
        assertThat(FeedsLogic.defaultMode(podcast.copy(suggestedMode = FeedMode.NONE))).isEqualTo(FeedMode.NONE)
        // 服务端建议了 GitHub 不支持的方式:退到第一个能选的
        assertThat(FeedsLogic.defaultMode(podcast.copy(kind = FeedKind.GITHUB, suggestedMode = FeedMode.CLIP))).isEqualTo(FeedMode.DOWNLOAD)
    }

    @Test
    fun `item actions follow state, attachments and links`() {
        val item = FeedItem(id = "i", title = "第 1 集", url = "https://example.com/1", enclosureUrl = "https://cdn.example.com/1.mp3")
        assertThat(FeedsLogic.actionsFor(item, FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(download = true, clip = true, open = false))
        // 没附件不能下,没原文链接不能剪
        assertThat(FeedsLogic.actionsFor(item.copy(enclosureUrl = ""), FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(false, true, false))
        assertThat(FeedsLogic.actionsFor(item.copy(url = ""), FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(true, false, false))
        // GitHub 没有正文可剪
        assertThat(FeedsLogic.actionsFor(item, FeedKind.GITHUB)).isEqualTo(FeedsLogic.ItemActions(true, false, false))
        // 处理中:什么都不能点
        assertThat(FeedsLogic.actionsFor(item.copy(state = FeedItemState.QUEUED), FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(false, false, false))
        // 下好了:只给打开;文件被清掉了能再下
        val done = item.copy(state = FeedItemState.DONE, node = FeedItemNode("n1", "1.mp3", isFolder = false, parentId = "p"))
        assertThat(FeedsLogic.actionsFor(done, FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(false, false, true))
        assertThat(FeedsLogic.actionsFor(done.copy(node = null), FeedKind.RSS)).isEqualTo(FeedsLogic.ItemActions(true, true, false))
        // 失败的可以重来
        assertThat(FeedsLogic.actionsFor(item.copy(state = FeedItemState.FAILED, error = "x"), FeedKind.RSS).download).isTrue()
    }

    @Test
    fun `polling merge updates in place and puts new items first`() {
        val a = FeedItem("a", "A", state = FeedItemState.QUEUED)
        val b = FeedItem("b", "B")
        val c = FeedItem("c", "C")
        val merged = FeedsLogic.merge(listOf(a, b, c), listOf(FeedItem("n", "N"), a.copy(state = FeedItemState.DONE), b))
        assertThat(merged.map { it.id }).containsExactly("n", "a", "b", "c").inOrder()
        assertThat(merged[1].state).isEqualTo(FeedItemState.DONE)
        assertThat(FeedsLogic.hasQueued(listOf(a, b))).isTrue()
        assertThat(FeedsLogic.hasQueued(merged)).isFalse()
    }

    @Test
    fun `pause and resume send every current setting`() {
        val feed = rss.copy(
            folderId = "dir",
            mode = FeedMode.CLIP,
            includeWords = "kotlin,android",
            excludeWords = "广告",
            assetPattern = "",
            prerelease = true,
            keepLast = 3,
            intervalMinutes = 120,
            enabled = true,
        )
        val input = FeedUpdates.update(feed, enabled = false)
        assertThat(input.enabled).isEqualTo(Optional.present(false))
        assertThat(input.title).isEqualTo(Optional.present("博客"))
        assertThat(input.folderId).isEqualTo(Optional.present("dir"))
        assertThat(input.mode).isEqualTo(Optional.present(GqlFeedMode.CLIP))
        assertThat(input.includeWords).isEqualTo(Optional.present("kotlin,android"))
        assertThat(input.excludeWords).isEqualTo(Optional.present("广告"))
        assertThat(input.assetPattern).isEqualTo(Optional.present(""))
        assertThat(input.prerelease).isEqualTo(Optional.present(true))
        assertThat(input.keepLast).isEqualTo(Optional.present(3))
        assertThat(input.intervalMinutes).isEqualTo(Optional.present(120))
        // 修改时 url / initial 不用给
        assertThat(input.url).isEqualTo(Optional.Absent)
        assertThat(input.initial).isEqualTo(Optional.Absent)
        // 文件夹被删了:不传(服务端保持原样)
        assertThat(FeedUpdates.update(feed.copy(folderId = null), enabled = true).folderId).isEqualTo(Optional.Absent)
    }

    @Test
    fun `feed notifications open the feed or the list`() {
        assertThat(NotificationsLogic.navFor(NotificationTarget(NotificationTargetKind.FEED, id = "f1"), isAdmin = false))
            .isEqualTo(NotificationNav.Feed("f1"))
        assertThat(NotificationsLogic.navFor(NotificationTarget(NotificationTargetKind.FEED), isAdmin = false))
            .isEqualTo(NotificationNav.Feed(null))
    }
}
