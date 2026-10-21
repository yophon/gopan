package xyz.yophon.gopan.feature.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统 PdfRenderer 的包装。PdfRenderer 不是线程安全的,同一时刻只能开一页:
 * 所有操作都排在同一条单线程调度器上;关闭也排进去,等在途的渲染结束再关。
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** 每页的高宽比(打开时一次读完,列表据此先占好位置) */
    private val ratios: FloatArray,
) {
    val pageCount: Int get() = ratios.size

    @Volatile
    private var closed = false

    fun ratio(index: Int): Float = ratios[index]

    /** 按宽 [widthPx] 渲染第 [index] 页(白底);已关闭时返回 null */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        if (closed || widthPx <= 0) return@withContext null
        renderer.openPage(index).use { page ->
            val (width, height) = renderSize(widthPx, page.height.toFloat() / page.width)
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    fun close() {
        if (closed) return
        closed = true
        closer.launch(dispatcher) {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        /** 单张位图的边长上限:再大绘制会超出纹理上限 */
        const val MAX_SIDE = 4096

        /** 按宽渲染时的位图尺寸:两边都不超过 [MAX_SIDE],超了等比缩小(长条页不变形) */
        fun renderSize(widthPx: Int, ratio: Float): Pair<Int, Int> {
            var width = widthPx.coerceIn(1, MAX_SIDE).toFloat()
            var height = width * ratio
            if (height > MAX_SIDE) {
                width = width * MAX_SIDE / height
                height = MAX_SIDE.toFloat()
            }
            return width.toInt().coerceAtLeast(1) to height.toInt().coerceAtLeast(1)
        }

        /**
         * 页列表一打开就停在哪一项(从 0 起):[page] 是从 1 起的页码(内容搜索命中页),null 为第一页;
         * 超出页数的(派生 PDF 与提取时的分页对不上)停在最后一页。
         */
        fun startIndex(page: Int?, pageCount: Int): Int =
            if (page == null || pageCount <= 0) 0 else (page - 1).coerceIn(0, pageCount - 1)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val dispatcher = Dispatchers.IO.limitedParallelism(1)
        private val closer = CoroutineScope(SupervisorJob())

        /** 打不开(加密、损坏)时抛异常 */
        suspend fun open(file: File): PdfDocument = withContext(dispatcher) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                val ratios = FloatArray(renderer.pageCount) { i ->
                    renderer.openPage(i).use { page -> if (page.width > 0) page.height.toFloat() / page.width else 1.414f }
                }
                PdfDocument(fd, renderer, ratios)
            } catch (e: Throwable) {
                runCatching { fd.close() }
                throw e
            }
        }
    }
}
