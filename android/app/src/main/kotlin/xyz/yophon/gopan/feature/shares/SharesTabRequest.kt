package xyz.yophon.gopan.feature.shares

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 「分享」tab 上面的三个标签 */
enum class SharesTab { SHARES, COLLECT, LINKS }

/** 一次切换请求;[token] 区分两次同样的请求(点了两条指向同一直链的通知) */
data class SharesTabTarget(val tab: SharesTab, val linkId: String? = null, val token: Long = System.nanoTime())

/**
 * 下次显示「分享」tab 时切到哪个标签(通知点开:收集链接 → 「收集」,直链 → 「直链」并打开那一条)。
 * 标签是页面里的 rememberSaveable 状态,不进路由;「分享」页显示时取走一次即失效。
 */
@Singleton
class SharesTabRequest @Inject constructor() {
    private val _pending = MutableStateFlow<SharesTabTarget?>(null)
    val pending: StateFlow<SharesTabTarget?> = _pending.asStateFlow()

    fun request(tab: SharesTab, linkId: String? = null) {
        _pending.value = SharesTabTarget(tab, linkId)
    }

    fun consume(target: SharesTabTarget) {
        _pending.compareAndSet(target, null)
    }
}
