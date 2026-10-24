package xyz.yophon.gopan.core.ui.nav

import kotlinx.serialization.Serializable

/**
 * 全部导航路由(Navigation Compose 类型安全路由)。
 * 根 NavHost 按 AuthState 切换:SignedOut 用 [Login]/[Register];SignedIn 进 MainScaffold,
 * 四个底部 tab 各是一个嵌套图([DriveTab] 等),起点是各自的首页。
 * 新路由加在这里(共享文件,由 Integrate 阶段统一改)。
 */
object Routes {
    // ---- 根 NavHost:启动页 / 登录图 / 主界面,登录态一变整栈换掉 ----
    @Serializable
    data object Startup

    /** 登录后的主界面(MainScaffold,里面是带底部导航的嵌套 NavHost) */
    @Serializable
    data object Main

    // ---- 未登录 ----
    @Serializable
    data object Login

    @Serializable
    data object Register

    // ---- 底部 tab(嵌套图的根) ----
    @Serializable
    data object DriveTab

    @Serializable
    data object ChatTab

    @Serializable
    data object SharesTab

    @Serializable
    data object AccountTab

    // ---- 文件 ----
    /** 根目录 */
    @Serializable
    data object Drive

    @Serializable
    data class Folder(val folderId: String)

    @Serializable
    data object Trash

    @Serializable
    data object Search

    @Serializable
    data class Preview(val nodeId: String)

    /** 收藏(recent = false)与最近打开(recent = true),doc/24:网盘首页顶上的两个入口进来 */
    @Serializable
    data class Quick(val recent: Boolean)

    /** 最近播放(doc/22 二.3):文件页菜单进来 */
    @Serializable
    data object RecentPlays

    /**
     * 压缩包浏览(doc/21 四):根导航上的全屏页,从预览页的压缩包卡片进来。
     * [parentId]:压缩包所在的文件夹(「解压到这里」的目标),null = 根目录
     */
    @Serializable
    data class Archive(val nodeId: String, val name: String = "", val parentId: String? = null)

    /** 在线编辑文本(doc/25):根导航上的全屏页,从预览的「编辑」或文件页的「新建文本文件」进来 */
    @Serializable
    data class TextEdit(val nodeId: String, val name: String = "")

    // ---- 传输助手 ----
    @Serializable
    data object Chat

    /** 上传 / 下载队列(全局页,不属于哪个 tab) */
    @Serializable
    data object Transfers

    // ---- 分享 ----
    @Serializable
    data object Shares

    @Serializable
    data class ShareVisits(val shareId: String, val name: String = "")

    /** 收集链接的收件记录;[newCount] > 0 时打开即标为已读 */
    @Serializable
    data class FileRequestUploads(val requestId: String, val title: String = "", val newCount: Int = 0)

    // ---- 我的 ----
    @Serializable
    data object Account

    @Serializable
    data object Sessions

    @Serializable
    data object AppPasswords

    @Serializable
    data object AgentCredentials

    @Serializable
    data object Admin

    /** 站内通知列表(doc/22 三);系统通知点按、底栏角标都进这里 */
    @Serializable
    data object Notifications

    /**
     * 动态(doc/23 二):「我的」进来不带参数;大量删除提醒点进来时按来源([actor] = kind:ref)
     * 和时间([since],RFC 3339)筛好
     */
    @Serializable
    data class Activity(val actor: String? = null, val since: String? = null)

    /** 空间分析(doc/23 三):「我的」的入口与用量条进来 */
    @Serializable
    data object Space

    @Serializable
    data object CrashLogs

    @Serializable
    data object Licenses

    // ---- 订阅与剪藏(doc/23 四、五):「我的」里进 ----
    @Serializable
    data object Feeds

    /** 贴地址添加订阅 */
    @Serializable
    data object FeedAdd

    /** 一个订阅的条目;[title] 只是数据回来前的顶栏 */
    @Serializable
    data class FeedDetail(val feedId: String, val title: String = "")

    @Serializable
    data object Clips

    /**
     * 剪藏的阅读页:渲染那篇 .md([nodeId]);[clipId] 为空时按节点查是哪篇剪藏(系统分享剪藏后点「打开」)。
     * [title] 只是数据回来前的顶栏
     */
    @Serializable
    data class ClipReader(val nodeId: String, val clipId: String? = null, val title: String = "")
}
