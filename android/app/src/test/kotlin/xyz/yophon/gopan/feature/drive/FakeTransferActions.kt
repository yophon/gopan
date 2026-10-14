package xyz.yophon.gopan.feature.drive

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.transfer.EnqueueResult
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import xyz.yophon.gopan.core.transfer.PackResult
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.transfer.TransferEvent
import java.io.File

/** ViewModel 单测用:记录调用,事件流可手动发 */
class FakeTransferActions : TransferActions {
    override val events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    override val uploadTasks = MutableStateFlow<List<UploadTaskEntity>>(emptyList())
    override val uploadProgress = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val uploadedUris = mutableListOf<Triple<List<Uri>, String?, Boolean>>()
    val uploadedTexts = mutableListOf<Triple<String, String?, Boolean>>()
    val uploadedFiles = mutableListOf<Triple<List<File>, String?, Boolean>>()
    val retried = mutableListOf<Long>()
    val canceled = mutableListOf<Long>()
    val removed = mutableListOf<Long>()
    val downloaded = mutableListOf<List<Node>>()
    val packed = mutableListOf<List<Node>>()
    val opened = mutableListOf<Node>()
    val sent = mutableListOf<Node>()
    var packResult: PackResult = PackResult.Queued
    var needsPermission = false

    override suspend fun uploadUris(uris: List<Uri>, parentId: String?, toChat: Boolean): EnqueueResult {
        uploadedUris += Triple(uris, parentId, toChat)
        return EnqueueResult(uris.size, 0, 0)
    }

    override suspend fun uploadOwnedFiles(files: List<File>, parentId: String?, toChat: Boolean): EnqueueResult {
        uploadedFiles += Triple(files, parentId, toChat)
        return EnqueueResult(files.size, 0, 0)
    }

    override suspend fun uploadText(text: String, parentId: String?, toChat: Boolean): EnqueueResult {
        uploadedTexts += Triple(text, parentId, toChat)
        return EnqueueResult(1, 0, 0)
    }

    override suspend fun retryUpload(id: Long) {
        retried += id
    }

    override suspend fun cancelUpload(id: Long) {
        canceled += id
    }

    override suspend fun removeUpload(id: Long) {
        removed += id
    }

    override suspend fun download(nodes: List<Node>): Int {
        downloaded += nodes
        return nodes.size
    }

    override suspend fun pack(nodes: List<Node>): PackResult {
        packed += nodes
        return packResult
    }

    override suspend fun open(node: Node): Boolean {
        opened += node
        return true
    }

    override suspend fun sendToChat(node: Node) {
        sent += node
    }

    override suspend fun needsStoragePermission(): Boolean = needsPermission
}
