package xyz.yophon.gopan.feature.quick

import androidx.annotation.StringRes
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.PreviewKind

/** 「最近」按类型筛(doc/31 五):影音、文档(PDF / Office / 文本)、图片 */
enum class RecentFilter(@StringRes val label: Int) {
    ALL(R.string.quick_filter_all),
    MEDIA(R.string.quick_filter_media),
    DOC(R.string.quick_filter_doc),
    IMAGE(R.string.quick_filter_image),
    ;

    fun matches(kind: PreviewKind): Boolean = when (this) {
        ALL -> true
        MEDIA -> kind == PreviewKind.VIDEO || kind == PreviewKind.AUDIO
        DOC -> kind == PreviewKind.PDF || kind == PreviewKind.NATIVE_PDF || kind == PreviewKind.OFFICE || kind == PreviewKind.TEXT
        IMAGE -> kind == PreviewKind.IMAGE
    }
}
