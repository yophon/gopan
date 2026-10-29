package xyz.yophon.gopan.core.assistant

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 随对话发的图(doc/35,与网页 assistant/images.ts 同一套规矩):发之前缩到长边 1568 的 JPEG。
 * 图跟着消息存进对话记录(每条 1 MB 上限),一条消息里的图合计不超过 700 KB(按 data URL 的长度算),
 * 超了先降质量重压一遍,还超就不让发。
 */
object ChatImages {
    const val MAX = 4
    private const val MAX_PX = 1568
    private const val BUDGET = 700_000
    private const val PREFIX = "data:image/jpeg;base64,"

    /** 读一张图并缩好,编成 JPEG data URL;读不了返回 null */
    fun load(resolver: ContentResolver, uri: Uri, quality: Int = 80): String? = runCatching {
        val bitmap = decode(resolver, uri) ?: return null
        encode(bitmap, quality).also { bitmap.recycle() }
    }.getOrNull()

    /** 发之前:合计超了就整体降质量重压;还超返回 null */
    fun fitBudget(urls: List<String>): List<String>? {
        if (urls.sumOf { it.length } <= BUDGET) return urls
        val again = urls.map { url -> decodeDataUrl(url)?.let { b -> encode(b, 55).also { b.recycle() } } ?: return null }
        return again.takeIf { it.sumOf { u -> u.length } <= BUDGET }
    }

    /** data URL → Bitmap(显示用) */
    fun decodeDataUrl(url: String): Bitmap? = runCatching {
        val bytes = Base64.decode(url.substringAfter(";base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun encode(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return PREFIX + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 自己按 EXIF 转正,也认 HEIC
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val size = info.size
                val scale = MAX_PX.toFloat() / max(size.width, size.height)
                if (scale < 1f) decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // Android 8.x:先按 2 的幂缩着读,再按 EXIF 转正、缩到位
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val scale = (MAX_PX.toFloat() / max(raw.width, raw.height)).coerceAtMost(1f)
        if (rotation == 0 && scale == 1f) return raw
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
}
