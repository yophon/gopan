package xyz.yophon.gopan.feature.account.security

/** MCP 权限的中文名,与网页端 web/src/utils/mcpScopes.ts 一致 */
object McpScopes {
    val user: List<Pair<String, String>> = listOf(
        "files:read" to "浏览与搜索",
        "files:download" to "读取与下载内容",
        "files:upload" to "上传文件",
        "files:write" to "新建、重命名、移动与复制",
        "files:delete" to "移入回收站与恢复",
        "shares:read" to "查看分享",
        "shares:write" to "创建与撤销分享",
        "chat:read" to "读取文件传输助手消息",
        "chat:write" to "发送消息与文件到文件传输助手",
        "audit:read" to "查看操作记录",
    )

    val admin: List<Pair<String, String>> = listOf(
        "admin:read" to "查看用户、任务与实例统计",
        "admin:users" to "管理账号、配额与密码",
        "admin:tasks" to "重试失败任务",
        "admin:purge" to "永久删除回收站文件",
    )

    private val labels: Map<String, String> = (user + admin).toMap()

    fun label(scope: String): String = labels[scope] ?: scope

    private val order: Map<String, Int> = (user + admin).mapIndexed { i, (scope, _) -> scope to i }.toMap()

    /** 按选项表的顺序排(服务端按字母序返回),认不出的放最后 */
    fun sorted(scopes: List<String>): List<String> = scopes.sortedBy { order[it] ?: Int.MAX_VALUE }

    /** 新建时的默认勾选 */
    fun defaultFor(admin: Boolean): Set<String> = setOf(if (admin) "admin:read" else "files:read")
}
