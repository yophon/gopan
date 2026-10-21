package xyz.yophon.gopan.feature.archive

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.transfer.download.AndroidDownloadPublisher
import java.io.File

/**
 * 从压缩包里取到缓存的文件交给其他应用:[share] 为 false 弹「用其他应用打开」(ACTION_VIEW),为 true 弹系统分享面板。
 * 经 FileProvider(res/xml/file_paths.xml 的 archive-entry/)转成 content:// 并授予读权限;没有能接的应用返回 false。
 */
fun launchArchiveFile(context: Context, file: File, share: Boolean): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: IllegalArgumentException) {
        return false
    }
    val mime = AndroidDownloadPublisher.mimeFor(file.name, null)
    val target = if (share) {
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .also { it.clipData = ClipData.newRawUri(file.name, uri) }
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val title = context.getString(if (share) R.string.archive_share_chooser else R.string.archive_open_with)
    return try {
        context.startActivity(Intent.createChooser(target, title))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
