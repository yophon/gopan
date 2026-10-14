package xyz.yophon.gopan.core.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 交给系统的几个动作:复制到剪贴板、发送文本(系统分享面板)、浏览器打开链接 */
@Stable
class SystemActions(private val context: Context, private val clipboard: Clipboard, private val scope: CoroutineScope) {

    /** 复制。Android 13+ 系统自己会弹「已复制」,返回 false;更早的版本返回 true,由调用方提示 */
    fun copy(text: String, label: String = "gopan"): Boolean {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    }

    /** 系统分享面板发一段文本;没有能接的应用返回 false */
    fun sendText(text: String, chooserTitle: String? = null): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** 浏览器打开;没有浏览器返回 false */
    fun openUrl(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

@Composable
fun rememberSystemActions(): SystemActions {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(context, clipboard, scope) { SystemActions(context, clipboard, scope) }
}
