package xyz.yophon.gopan.core.data.feeds

import kotlinx.coroutines.CompletableDeferred
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/** 内存版订阅仓库:记下调用;[itemPages] 依次给出条目第一页(轮询),用完后一直给最后一页 */
class FakeFeedsRepository : FeedsRepository {
    val calls = mutableListOf<String>()
    var feeds: List<Feed> = emptyList()
    val itemPages = ArrayDeque<List<FeedItem>>()
    private var lastItems: List<FeedItem> = emptyList()
    var preview: FeedPreview? = null
    var nextError: GopanException? = null

    /** 非空时 feeds() 等它再回:测"先显示快照、联网还在路上"的中间态 */
    var feedsGate: CompletableDeferred<Unit>? = null

    /** setEnabled 收到的订阅(检查是否带上了全部设置) */
    var lastUpdated: Pair<Feed, Boolean>? = null

    private fun fail() {
        nextError?.let {
            nextError = null
            throw it
        }
    }

    override suspend fun feeds(): List<Feed> {
        calls += "feeds"
        feedsGate?.await()
        fail()
        return feeds
    }

    override suspend fun feed(id: String): Feed {
        calls += "feed($id)"
        fail()
        return feeds.firstOrNull { it.id == id } ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
    }

    override suspend fun items(feedId: String, cursor: String?): FeedItemPage {
        calls += "items($feedId,$cursor)"
        fail()
        itemPages.removeFirstOrNull()?.let { lastItems = it }
        return FeedItemPage(lastItems, null)
    }

    override suspend fun preview(url: String, assetPattern: String?): FeedPreview {
        calls += "preview($url,$assetPattern)"
        fail()
        return preview ?: throw GopanException("NOT_A_FEED", "不是 RSS / Atom")
    }

    override suspend fun create(url: String, mode: FeedMode, assetPattern: String?): Feed {
        calls += "create($url,$mode,$assetPattern)"
        fail()
        val feed = Feed(id = "new", kind = preview?.kind ?: FeedKind.RSS, url = url, title = preview?.title.orEmpty(), mode = mode)
        feeds = feeds + feed
        return feed
    }

    override suspend fun setEnabled(feed: Feed, enabled: Boolean): Feed {
        calls += "setEnabled(${feed.id},$enabled)"
        fail()
        lastUpdated = feed to enabled
        val updated = feed.copy(enabled = enabled)
        feeds = feeds.map { if (it.id == feed.id) updated else it }
        return updated
    }

    override suspend fun delete(id: String) {
        calls += "delete($id)"
        fail()
        feeds = feeds.filter { it.id != id }
    }

    override suspend fun refresh(id: String): Feed {
        calls += "refresh($id)"
        return feed(id)
    }

    override suspend fun fetchItem(itemId: String, mode: FeedMode): FeedItem {
        calls += "fetchItem($itemId,$mode)"
        fail()
        val item = lastItems.first { it.id == itemId }.copy(state = FeedItemState.QUEUED, jobId = "job-$itemId")
        lastItems = lastItems.map { if (it.id == itemId) item else it }
        return item
    }
}
