package xyz.yophon.gopan.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.yophon.gopan.R

/** 整页加载态。转圈过 [LOADING_INDICATOR_DELAY_MS] 才出现:数据很快回来时不闪一下 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AfterDelay(LOADING_INDICATOR_DELAY_MS) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                if (message != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 列表首次加载的骨架:几行灰色占位(图标 + 两行字),比屏幕中间转圈更像"马上就好"。
 * 同样延迟一小会儿再淡入,数据秒回时什么都不闪。
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = SKELETON_ROWS,
    leadingSize: Dp = 44.dp,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AfterDelay(SKELETON_DELAY_MS) {
            val alpha = skeletonAlpha()
            val color = MaterialTheme.colorScheme.surfaceVariant
            Column(modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }) {
                repeat(rows) { i ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(leadingSize).clip(RoundedCornerShape(8.dp)).background(color))
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth(SKELETON_TITLE_WIDTHS[i % SKELETON_TITLE_WIDTHS.size]).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
                            Box(Modifier.fillMaxWidth(0.32f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        }
                    }
                }
            }
        }
    }
}

/** 网格视图的骨架:与 [ListSkeleton] 同样的延迟与呼吸效果 */
@Composable
fun GridSkeleton(
    modifier: Modifier = Modifier,
    minCellSize: Dp = 140.dp,
    cells: Int = SKELETON_GRID_CELLS,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AfterDelay(SKELETON_DELAY_MS) {
            val alpha = skeletonAlpha()
            val color = MaterialTheme.colorScheme.surfaceVariant
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minCellSize),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
            ) {
                items(cells) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(12.dp)).background(color))
                }
            }
        }
    }
}

/** [delayMillis] 之后再淡入 [content];页面在那之前就换掉了就什么都不画 */
@Composable
fun AfterDelay(delayMillis: Long, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(SKELETON_FADE_MS))) { content() }
}

@Composable
private fun skeletonAlpha(): State<Float> = rememberInfiniteTransition(label = "skeleton").animateFloat(
    initialValue = 1f,
    targetValue = 0.45f,
    animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
    label = "skeletonAlpha",
)

/** 整页转圈出现前的等待 */
const val LOADING_INDICATOR_DELAY_MS = 300L
private const val SKELETON_DELAY_MS = 150L
private const val SKELETON_FADE_MS = 200
private const val SKELETON_PULSE_MS = 800
private const val SKELETON_ROWS = 8
private const val SKELETON_GRID_CELLS = 12
private val SKELETON_TITLE_WIDTHS = floatArrayOf(0.7f, 0.5f, 0.62f, 0.44f, 0.56f)

/** 整页错误态,带可选的重试 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (onRetry != null) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** 整页空态 */
@Composable
fun EmptyState(
    title: String = stringResource(R.string.state_empty_title),
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}
