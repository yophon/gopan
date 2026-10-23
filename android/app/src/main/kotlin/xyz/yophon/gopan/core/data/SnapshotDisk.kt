package xyz.yophon.gopan.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.yophon.gopan.di.ApplicationScope
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScreenCache] 的磁盘层:带序列化器的键([ScreenKey.persist])把快照存成文件,
 * App 冷启动后第一次进页面也能先画上次的结果,不用等网络。只有几个底部 tab 的首屏用它。
 */
interface SnapshotDisk {
    suspend fun read(userId: String, key: String): String?

    /** 后台写,不等结果;[encode] 也在后台线程上执行 */
    fun write(userId: String, key: String, encode: () -> String)

    fun remove(userId: String, key: String)

    /** 登出:所有账号的快照一起删 */
    fun clear()

    /** 不落盘(测试默认用它) */
    object None : SnapshotDisk {
        override suspend fun read(userId: String, key: String): String? = null
        override fun write(userId: String, key: String, encode: () -> String) = Unit
        override fun remove(userId: String, key: String) = Unit
        override fun clear() = Unit
    }
}

/**
 * 快照文件放在 noBackupFilesDir/snapshots/<用户>/<键>.json:不进云备份,和目录缓存一样只在本机。
 * 读写删清走同一个串行调度器,按调用顺序执行(登出时的清空不会被排在它前面的写入顶回来);
 * 写入先写临时文件再改名,读到的要么是旧的完整内容,要么是新的。
 */
@Singleton
class FileSnapshotDisk internal constructor(
    private val root: File,
    private val scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) : SnapshotDisk {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ) : this(File(context.noBackupFilesDir, "snapshots"), scope, Dispatchers.IO)

    private val io = dispatcher.limitedParallelism(1)

    override suspend fun read(userId: String, key: String): String? = withContext(io) {
        runCatching { file(userId, key).takeIf { it.isFile }?.readText() }
            .onFailure { Timber.w(it, "读快照 %s 失败", key) }
            .getOrNull()
    }

    override fun write(userId: String, key: String, encode: () -> String) {
        scope.launch(io) {
            runCatching {
                val target = file(userId, key)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(encode())
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }.onFailure { Timber.w(it, "写快照 %s 失败", key) }
        }
    }

    override fun remove(userId: String, key: String) {
        scope.launch(io) { file(userId, key).delete() }
    }

    override fun clear() {
        scope.launch(io) { root.deleteRecursively() }
    }

    private fun file(userId: String, key: String) =
        File(File(root, URLEncoder.encode(userId, "UTF-8")), URLEncoder.encode(key, "UTF-8") + ".json")
}
