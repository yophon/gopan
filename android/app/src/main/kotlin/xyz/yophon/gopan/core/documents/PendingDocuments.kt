package xyz.yophon.gopan.core.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.transfer.TransferEngine
import xyz.yophon.gopan.core.transfer.TransferEvent
import xyz.yophon.gopan.core.transfer.UploadSource
import xyz.yophon.gopan.di.ApplicationScope
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 别的应用往 gopan 里写的新文件(「文件」App 复制进来、「另存为」选了 gopan):先写到本地,
 * 写完(文件描述符关闭)进上传队列;传完从这里移除并通知目录刷新。列目录时一并列出,刚存的文件马上看得到。
 */
@Singleton
class PendingDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    class Entry(
        val id: String,
        /** 所在目录的文档 id(根目录是 [GopanDocumentsProvider.ROOT_DOC]) */
        val parentDocId: String,
        /** 服务端的父节点 id(根目录为 null) */
        val parentId: String?,
        val name: String,
        val mime: String,
        val file: File,
    ) {
        @Volatile
        var taskId: Long? = null

        @Volatile
        var written = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val dir = File(context.filesDir, "transfers/documents")

    init {
        // 上传结束(成功或失败)就不再作为「待上传」列出:成功的已经是真节点,失败的在传输列表里
        scope.launch {
            engine.events.collect { e ->
                val task = when (e) {
                    is TransferEvent.UploadDone -> e.task
                    is TransferEvent.UploadFailed -> e.task
                    else -> return@collect
                }
                // 小文件可能在 onWritten 记下 taskId 之前就传完了:按来源文件认
                entries.values
                    .filter { it.taskId == task.id || (it.taskId == null && it.written && Uri.fromFile(it.file).toString() == task.sourceUri) }
                    .forEach {
                        it.taskId = task.id
                        remove(it)
                    }
            }
        }
    }

    fun create(parentDocId: String, parentId: String?, name: String, mime: String): Entry {
        val id = PREFIX + UUID.randomUUID().toString()
        val folder = File(dir, id.removePrefix(PREFIX)).apply { mkdirs() }
        val file = File(folder, safeName(name)).apply { createNewFile() }
        return Entry(id, parentDocId, parentId, name, mime, file).also { entries[id] = it }
    }

    fun get(id: String): Entry? = entries[id]

    fun inParent(parentDocId: String): List<Entry> = entries.values.filter { it.parentDocId == parentDocId }

    /** 写完了:非空就进上传队列(上传引擎拥有这份副本,传完删);空文件直接丢掉 */
    fun onWritten(entry: Entry) {
        if (entry.written) return
        entry.written = true
        if (entry.file.length() == 0L) {
            remove(entry)
            return
        }
        scope.launch {
            val source = UploadSource(Uri.fromFile(entry.file), entry.name, entry.file.length(), entry.file.lastModified(), entry.mime, owns = true)
            val ids = engine.enqueueUploads(listOf(source), entry.parentId)
            if (ids.isEmpty()) remove(entry) else entry.taskId = ids.first()
        }
    }

    fun remove(entry: Entry) {
        entries.remove(entry.id)
        // 上传引擎拥有的文件由它删;这里只清空目录与没进队列的副本
        if (entry.taskId == null) entry.file.delete()
        entry.file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        notifyParent(entry.parentDocId)
    }

    fun notifyParent(parentDocId: String) {
        val authority = context.packageName + GopanDocumentsProvider.AUTHORITY_SUFFIX
        context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocId), null)
    }

    /** 登出:清掉还没写完的 */
    fun clear() {
        entries.values.filter { it.taskId == null }.forEach { it.file.delete() }
        entries.clear()
        dir.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
    }

    companion object {
        const val PREFIX = "pending:"

        fun safeName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "未命名" }.take(200)
    }
}
