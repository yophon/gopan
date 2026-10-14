package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Room 的目录缓存把整页 items 序列化成 JSON(RoomListingCache 用同样的 Json 配置)。
 * 15 分钟有效的预签名缩略图地址绝不能写进去,离线时只靠 hasThumbnail 走图片磁盘缓存。
 */
class NodeSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val image = Node(
        id = "a1",
        name = "photo.jpg",
        kind = NodeKind.FILE,
        size = 1234,
        updatedAt = "2026-10-14T00:00:00Z",
        preview = NodePreview(PreviewKind.IMAGE, hasThumbnail = true, thumbUrl = "https://s3/thumb?X-Amz-Signature=secret"),
    )

    @Test
    fun `presigned thumbnail url is never serialized`() {
        val encoded = json.encodeToString(listOf(image))
        assertThat(encoded).doesNotContain("X-Amz-Signature")
        assertThat(encoded).doesNotContain("thumbUrl")
        assertThat(encoded).contains("\"hasThumbnail\":true")
    }

    @Test
    fun `cached node keeps the thumbnail flag but no url`() {
        val decoded = json.decodeFromString<List<Node>>(json.encodeToString(listOf(image))).single()
        assertThat(decoded.preview.thumbUrl).isNull()
        assertThat(decoded.preview.hasThumbnail).isTrue()
        // 离线时仍认为有缩略图:NodeVisual 只从磁盘缓存按 imageCacheKey 读,不发网络请求
        assertThat(decoded.hasThumb).isTrue()
        assertThat(decoded.imageCacheKey).isEqualTo(image.imageCacheKey)
    }

    @Test
    fun `old cache rows written with thumbUrl still decode`() {
        val legacy = """[{"id":"a1","name":"photo.jpg","kind":"FILE","preview":{"kind":"IMAGE","thumbUrl":"https://s3/old"}}]"""
        val decoded = json.decodeFromString<List<Node>>(legacy).single()
        assertThat(decoded.preview.thumbUrl).isNull()
        assertThat(decoded.preview.hasThumbnail).isFalse()
        assertThat(decoded.hasThumb).isFalse()
    }
}
