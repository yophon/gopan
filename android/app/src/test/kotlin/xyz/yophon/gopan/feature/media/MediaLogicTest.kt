package xyz.yophon.gopan.feature.media

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.NodePreview
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.QueueEntry
import xyz.yophon.gopan.feature.preview.PreviewItem

class MediaLogicTest {

    private fun progress(position: Double, duration: Double, finished: Boolean = false) = PlayProgress(position, duration, finished)

    private fun file(id: String, kind: PreviewKind = PreviewKind.AUDIO) =
        Node(id = id, name = "$id.mp3", kind = NodeKind.FILE, preview = NodePreview(kind = kind))

    @Test
    fun `resumes past ten seconds unless finished or at the end`() {
        assertThat(resumePositionMs(null)).isNull()
        assertThat(resumePositionMs(progress(1395.5, 6120.0))).isEqualTo(1_395_500L)
        // 10 秒以内从头
        assertThat(resumePositionMs(progress(10.0, 6120.0))).isNull()
        assertThat(resumePositionMs(progress(10.5, 6120.0))).isEqualTo(10_500L)
        // 看完了从头
        assertThat(resumePositionMs(progress(6100.0, 6120.0, finished = true))).isNull()
        // 位置到了(或超过)总长:文件被换过,从头
        assertThat(resumePositionMs(progress(6120.0, 6120.0))).isNull()
        assertThat(resumePositionMs(progress(7000.0, 6120.0))).isNull()
        // 总长未知时只看位置
        assertThat(resumePositionMs(progress(30.0, 0.0))).isEqualTo(30_000L)
    }

    @Test
    fun `a pdf opens at the search hit first, then the saved page`() {
        assertThat(pdfStartPage(null, null)).isNull()
        assertThat(pdfStartPage(7, progress(12.0, 300.0))).isEqualTo(7)
        assertThat(pdfStartPage(null, progress(12.0, 300.0))).isEqualTo(12)
        // 看完了、第 1 页不用跳
        assertThat(pdfStartPage(null, progress(300.0, 300.0, finished = true))).isNull()
        assertThat(pdfStartPage(null, progress(1.0, 300.0))).isNull()
        // 非法的命中页当作没有
        assertThat(pdfStartPage(0, progress(12.0, 300.0))).isEqualTo(12)
    }

    @Test
    fun `play times are m-ss below an hour and h-mm-ss above`() {
        assertThat(formatPlayTime(0.0)).isEqualTo("0:00")
        assertThat(formatPlayTime(5.9)).isEqualTo("0:05")
        assertThat(formatPlayTime(1395.0)).isEqualTo("23:15")
        assertThat(formatPlayTime(6120.0)).isEqualTo("1:42:00")
        assertThat(formatPlayTime(-3.0)).isEqualTo("0:00")
        assertThat(formatPlayTime(Double.NaN)).isEqualTo("0:00")
        assertThat(formatPlayTimeMs(1_395_400)).isEqualTo("23:15")
    }

    @Test
    fun `recent plays show time for media and pages for documents`() {
        assertThat(progressText(PreviewKind.VIDEO, progress(1395.0, 6120.0))).isEqualTo(ProgressText.Time("23:15", "1:42:00"))
        assertThat(progressText(PreviewKind.AUDIO, progress(65.0, 600.0))).isEqualTo(ProgressText.Time("1:05", "10:00"))
        assertThat(progressText(PreviewKind.NATIVE_PDF, progress(12.0, 300.0))).isEqualTo(ProgressText.Pages(12, 300))
        assertThat(progressText(PreviewKind.PDF, progress(12.0, 300.0))).isEqualTo(ProgressText.Pages(12, 300))
        assertThat(progressText(PreviewKind.OFFICE, progress(500.0, 300.0))).isEqualTo(ProgressText.Pages(300, 300))
        assertThat(progressFraction(progress(150.0, 300.0))).isEqualTo(0.5f)
        assertThat(progressFraction(progress(400.0, 300.0))).isEqualTo(1f)
        assertThat(progressFraction(progress(10.0, 0.0))).isEqualTo(0f)
    }

    @Test
    fun `the audio queue is the whole folder starting at the opened file`() {
        val plan = audioQueue(listOf(file("a"), file("b"), file("c")), "b", "b.mp3")
        assertThat(plan.entries).containsExactly(QueueEntry("a", "a.mp3"), QueueEntry("b", "b.mp3"), QueueEntry("c", "c.mp3")).inOrder()
        assertThat(plan.startIndex).isEqualTo(1)
    }

    @Test
    fun `the queue falls back to just the file when the playlist does not have it`() {
        assertThat(audioQueue(emptyList(), "b", "b.mp3")).isEqualTo(QueuePlan(listOf(QueueEntry("b", "b.mp3")), 0))
        assertThat(audioQueue(listOf(file("a")), "b", "b.mp3")).isEqualTo(QueuePlan(listOf(QueueEntry("b", "b.mp3")), 0))
    }

    @Test
    fun `the queue drops folders and duplicates`() {
        val folder = Node(id = "f", name = "dir", kind = NodeKind.FOLDER)
        val plan = audioQueue(listOf(file("a"), folder, file("a"), file("b")), "b", "b.mp3")
        assertThat(plan.entries.map { it.nodeId }).containsExactly("a", "b").inOrder()
        assertThat(plan.startIndex).isEqualTo(1)
    }

    @Test
    fun `the next episode follows the current one in the playlist`() {
        val list = listOf(file("e1", PreviewKind.VIDEO), file("e2", PreviewKind.VIDEO), file("e10", PreviewKind.VIDEO))
        assertThat(nextInPlaylist(list, "e1")?.id).isEqualTo("e2")
        assertThat(nextInPlaylist(list, "e2")?.id).isEqualTo("e10")
        assertThat(nextInPlaylist(list, "e10")).isNull()
        assertThat(nextInPlaylist(list, "zz")).isNull()
        assertThat(nextInPlaylist(emptyList(), "e1")).isNull()
    }

    @Test
    fun `the pager follows the player only from the page that was playing`() {
        val pages = listOf("a", "img", "b", "c")
        // 正看着在放的 a,播放器换到 b:翻过去
        assertThat(followPage("a", "b", "a", pages)).isEqualTo(2)
        // 用户自己翻到了 b(随后 b 开始放):不动
        assertThat(followPage("a", "b", "b", pages)).isNull()
        // 正看着图片:不动
        assertThat(followPage("a", "b", "img", pages)).isNull()
        // 换到的那首不在翻页列表里:不动
        assertThat(followPage("a", "x", "a", pages)).isNull()
        // 没变、刚开始放、停了:不动
        assertThat(followPage("a", "a", "a", pages)).isNull()
        assertThat(followPage(null, "a", "a", pages)).isNull()
        assertThat(followPage("a", null, "a", pages)).isNull()
    }

    @Test
    fun `repeat cycles off, all, one`() {
        assertThat(nextRepeatMode(Player.REPEAT_MODE_OFF)).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(nextRepeatMode(Player.REPEAT_MODE_ALL)).isEqualTo(Player.REPEAT_MODE_ONE)
        assertThat(nextRepeatMode(Player.REPEAT_MODE_ONE)).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `only automatic and repeat transitions count as finished`() {
        assertThat(finishedByTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)).isTrue()
        assertThat(finishedByTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)).isTrue()
        assertThat(finishedByTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)).isFalse()
        assertThat(finishedByTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)).isFalse()
    }

    @Test
    fun `the ticker fires every fifteen seconds after the first beat`() {
        val ticker = ProgressTicker()
        assertThat(ticker.due(1_000)).isFalse()
        assertThat(ticker.due(15_999)).isFalse()
        assertThat(ticker.due(16_000)).isTrue()
        assertThat(ticker.due(17_000)).isFalse()
        assertThat(ticker.due(31_000)).isTrue()
        // 暂停后重新起算
        ticker.reset()
        assertThat(ticker.due(40_000)).isFalse()
        assertThat(ticker.due(54_000)).isFalse()
        assertThat(ticker.due(55_000)).isTrue()
    }

    @Test
    fun `the mini player opens a session over the whole queue`() {
        val queue = listOf(QueueEntry("a", "a.mp3"), QueueEntry("b", "b.mp3"))
        fun now(id: String, q: List<QueueEntry>) = NowPlaying(id, "$id.mp3", true, false, 0, 0, q)
        assertThat(queueSession(now("b", queue))).containsExactly(PreviewItem("a", "a.mp3", null), PreviewItem("b", "b.mp3", null)).inOrder()
        // 队列还没同步过来:只放正在放的那首
        assertThat(queueSession(now("x", queue))).containsExactly(PreviewItem("x", "x.mp3", null))
        assertThat(queueSession(now("x", emptyList()))).containsExactly(PreviewItem("x", "x.mp3", null))
    }

    @Test
    fun `now playing fraction is clamped`() {
        assertThat(NowPlaying("a", "", true, false, 50, 200, emptyList()).fraction).isEqualTo(0.25f)
        assertThat(NowPlaying("a", "", true, false, 500, 200, emptyList()).fraction).isEqualTo(1f)
        assertThat(NowPlaying("a", "", true, false, 50, 0, emptyList()).fraction).isEqualTo(0f)
    }
}
