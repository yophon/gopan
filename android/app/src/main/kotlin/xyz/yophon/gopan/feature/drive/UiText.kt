package xyz.yophon.gopan.feature.drive

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/** ViewModel 发给界面的文案:资源 id(+ 格式参数)或现成字符串(服务端/GopanException 的中文消息) */
@Immutable
sealed interface UiText {
    data class Plain(val text: String) : UiText

    class Res(@StringRes val id: Int, vararg val args: Any) : UiText {
        val formatArgs: Array<out Any> = args
    }

    fun resolve(context: Context): String = when (this) {
        is Plain -> text
        is Res -> if (formatArgs.isEmpty()) context.getString(id) else context.getString(id, *formatArgs)
    }
}

@Composable
fun UiText.resolve(): String = resolve(LocalContext.current)
