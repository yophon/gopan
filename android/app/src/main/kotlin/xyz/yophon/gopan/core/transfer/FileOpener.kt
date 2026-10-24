package xyz.yophon.gopan.core.transfer

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.download.AndroidDownloadPublisher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 把下载好的文件交给其它应用:content:// 原样,私有 / 公共文件路径经 FileProvider 转成 content:// */
@Singleton
class FileOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val authority: String get() = "${context.packageName}.files"

    fun uriFor(location: String): Uri? {
        if (location.isEmpty()) return null
        val uri = location.toUri()
        return when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> uri
            ContentResolver.SCHEME_FILE -> uri.path?.let { fileUri(File(it)) }
            else -> fileUri(File(location))
        }
    }

    fun fileUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 查看下载结果的 Intent(新任务栈,带读授权);文件夹批次与没有结果的返回 null */
    fun viewIntent(task: DownloadTaskEntity): Intent? {
        val uri = uriFor(task.finalUri ?: return null) ?: return null
        val mime = AndroidDownloadPublisher.mimeFor(task.name, task.mime)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 手机上已有的文件(离线副本,doc/26)直接弹"用其他应用打开";没有能打开的应用返回 false */
    fun openFile(file: File, name: String, mime: String?, title: String): Boolean {
        val uri = fileUri(file) ?: return false
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, AndroidDownloadPublisher.mimeFor(name, mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(Intent.createChooser(view, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 弹"用其他应用打开"选择器;没有能打开的应用返回 false */
    fun openWith(task: DownloadTaskEntity, title: String): Boolean {
        val view = viewIntent(task) ?: return false
        return start(Intent.createChooser(view, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
