package xyz.yophon.gopan.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 缩放状态:scale ≥ 1;放大时 [offset] 限制在内容不露出边框的范围里 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set
    internal var size by mutableStateOf(IntSize.Zero)

    val magnified: Boolean get() = scale > 1.01f

    internal fun clamp(o: Offset, s: Float = scale): Offset {
        val maxX = (size.width * (s - 1f)) / 2f
        val maxY = (size.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

@Composable
fun rememberZoomState(key: Any? = null): ZoomState = remember(key) { ZoomState() }

/**
 * 双指缩放、放大后单指拖动、双击放大 / 复位。没放大时单指手势一律不消费,留给外层(左右翻页、下拉关闭)。
 */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .onSizeChanged { state.size = it }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val newScale = (state.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                    // 以双指中心为锚点缩放
                    val center = Offset(state.size.width / 2f, state.size.height / 2f)
                    val anchor = centroid - center
                    val scaled = (state.offset - anchor) * (newScale / state.scale) + anchor + pan
                    state.scale = newScale
                    state.offset = state.clamp(scaled, newScale)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (pressed == 1 && state.magnified) {
                    val pan = event.calculatePan()
                    val next = state.clamp(state.offset + pan)
                    // 拖到边了还在往外拖:不消费,让外层(翻页)接手
                    val atEdge = abs(next.x - state.offset.x) < 0.5f && abs(pan.x) > abs(pan.y)
                    if (!atEdge) {
                        state.offset = next
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (state.scale < 1.01f) state.reset()
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

/** 双击:没放大时放大到 [ZoomState.DOUBLE_TAP_SCALE](以点击处为中心),放大时复位;单击回调 [onTap] */
@Composable
fun Modifier.doubleTapZoom(state: ZoomState, onTap: (() -> Unit)? = null): Modifier {
    val scope = rememberCoroutineScope()
    val anim = remember(state) { Animatable(1f) }
    return this.pointerInput(state) {
        detectTapGestures(
            onTap = { onTap?.invoke() },
            onDoubleTap = { tap ->
                scope.launch {
                    if (state.magnified) {
                        val from = state.scale
                        val fromOffset = state.offset
                        anim.snapTo(0f)
                        anim.animateTo(1f) {
                            state.scale = from + (1f - from) * value
                            state.offset = fromOffset * (1f - value)
                        }
                        state.reset()
                    } else {
                        val center = Offset(state.size.width / 2f, state.size.height / 2f)
                        val target = ZoomState.DOUBLE_TAP_SCALE
                        val targetOffset = state.clamp((center - tap) * (target - 1f), target)
                        anim.snapTo(0f)
                        anim.animateTo(1f) {
                            state.scale = 1f + (target - 1f) * value
                            state.offset = targetOffset * value
                        }
                    }
                }
            },
        )
    }
}
