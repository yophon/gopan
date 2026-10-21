package xyz.yophon.gopan.feature.transfers

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下次打开传输列表时直接停在「后台任务」栏:提交离线下载后点「查看」用。
 * 路由 Routes.Transfers 是没有参数的全局页(六处入口),不为这一个入口改路由;打开时取走一次即失效。
 */
@Singleton
class TransfersTabRequest @Inject constructor() {
    private val jobs = AtomicBoolean(false)

    fun requestJobs() = jobs.set(true)

    /** 取走请求:true 表示这次打开要停在「后台任务」栏 */
    fun consumeJobs(): Boolean = jobs.getAndSet(false)
}
