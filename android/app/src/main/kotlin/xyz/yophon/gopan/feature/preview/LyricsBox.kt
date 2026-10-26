package xyz.yophon.gopan.feature.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.core.data.media.Lyrics

/**
 * 歌词(doc/31 二):带时间的跟着唱到的那行滚,当前行亮;点一行跳过去。手指碰过之后 4 秒内不自动滚。
 * 纯文本歌词只是一列字。
 */
@Composable
internal fun LyricsBox(
    lyrics: Lyrics,
    current: Int,
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val touchedAt = remember { longArrayOf(0L) }
    LaunchedEffect(current, lyrics) {
        if (!lyrics.synced || current < 0) return@LaunchedEffect
        if (System.currentTimeMillis() - touchedAt[0] < 4_000) return@LaunchedEffect
        state.animateScrollToItem(current)
    }
    LazyColumn(
        state = state,
        // 上下留白:滚到某行时它停在靠中间的位置
        contentPadding = PaddingValues(vertical = 96.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touchedAt[0] = System.currentTimeMillis()
                }
            }
            .testTag("preview_audio_lyrics"),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val on = i == current
            Text(
                text = line.text.ifEmpty { " " },
                color = if (on) OnPreview else OnPreviewDim,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSeek != null && lyrics.synced && line.t >= 0) {
                            Modifier.clickable {
                                touchedAt[0] = 0L
                                onSeek((line.t * 1000).toLong())
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 6.dp, horizontal = 8.dp),
            )
        }
    }
}
