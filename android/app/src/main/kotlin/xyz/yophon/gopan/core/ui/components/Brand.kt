package xyz.yophon.gopan.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.yophon.gopan.core.ui.theme.LocalGopanColors

/*
 * 品牌图形:文件夹记号、「gopan」字标、两片的文件夹图标、带标签页的文件夹面板。
 * 形状与网页版(BrandLogo.vue、NodeIcon.vue、.gp-folder)同一套路径。
 */

/** 文件夹剪影,24×20(与网页版 favicon、启动图标同一条路径) */
private const val MARK_PATH =
    "M0 3a3 3 0 0 1 3-3h5.2a2 2 0 0 1 1.5.7L11.6 3H21a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H3a3 3 0 0 1-3-3z"

val GopanMarkVector: ImageVector by lazy {
    ImageVector.Builder(name = "GopanMark", defaultWidth = 24.dp, defaultHeight = 20.dp, viewportWidth = 24f, viewportHeight = 20f)
        .addPath(pathData = addPathNodes(MARK_PATH), fill = SolidColor(Color.Black))
        .build()
}

/** 文件夹记号;颜色跟着 [color],默认当前内容色 */
@Composable
fun GopanMark(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Icon(GopanMarkVector, contentDescription = null, tint = color, modifier = modifier.aspectRatio(24f / 20f))
}

/** 记号 + 「gopan」字标;大小跟着 [fontSize] 走 */
@Composable
fun GopanWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp,
    color: Color = LocalContentColor.current,
) {
    val density = LocalDensity.current
    val gap = with(density) { (fontSize * 0.42f).toDp() }
    val markHeight = with(density) { (fontSize * 0.86f).toDp() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        GopanMark(Modifier.height(markHeight), color = color)
        Text(
            text = "gopan",
            color = color,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, lineHeight = fontSize),
        )
    }
}

// 两片的文件夹(34×29):后片带标签,前片盖住下面大半
private val folderBack: Path by lazy {
    PathParser().parsePathString("M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z").toPath()
}
private val folderFront: Path by lazy {
    PathParser().parsePathString("M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z").toPath()
}

/** 文件夹图标:实心两片,颜色来自配色(墨黑下是暖灰) */
@Composable
fun FolderGlyph(modifier: Modifier = Modifier) {
    val colors = LocalGopanColors.current
    Canvas(modifier.aspectRatio(34f / 29f)) {
        scale(size.width / 34f, size.height / 29f, pivot = Offset.Zero) {
            drawPath(folderBack, colors.folderBack)
            drawPath(folderFront, colors.folderFront)
        }
    }
}

/** 页面级的粗体大标题(底部 tab 的根页面顶栏用) */
@Composable
fun TabTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
        maxLines = 1,
    )
}

/**
 * 文件夹面板:左上角一块标签页,下面是正文。标签页宽度跟着 [tab] 的内容走,
 * 标签与正文之间用一段 S 形曲线接上(网页版 .gp-folder 的形状)。
 * 可点时按下颜色变深,不用涟漪(涟漪会画出文件夹轮廓之外)。
 */
@Composable
fun FolderPanel(
    modifier: Modifier = Modifier,
    color: Color = LocalGopanColors.current.panel,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tabHeight: Dp = 40.dp,
    corner: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
    onClick: (() -> Unit)? = null,
    tab: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (pressed) lerp(color, contentColor, 0.06f) else color,
        label = "folderPanelFill",
    )
    // 标签页宽度在测量时写入、绘制时读取(同一帧内先测量后绘制,不需要再重组一次)
    val tabWidth = remember { mutableFloatStateOf(0f) }
    val clickable = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Layout(
            modifier = modifier
                .then(clickable)
                .drawBehind {
                    drawPath(folderOutline(size, tabWidth.floatValue, tabHeight.toPx(), corner.toPx()), fill)
                },
            content = {
                Row(
                    modifier = Modifier.height(tabHeight).padding(start = corner, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = tab,
                )
                Column(modifier = Modifier.padding(contentPadding), content = content)
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val tabPlaceable = measurables[0].measure(loose.copy(maxWidth = (constraints.maxWidth * 0.72f).toInt()))
            val bodyPlaceable = measurables[1].measure(if (constraints.hasBoundedWidth) loose.copy(minWidth = constraints.maxWidth) else loose)
            tabWidth.floatValue = tabPlaceable.width.toFloat()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(tabPlaceable.width, bodyPlaceable.width)
            val height = (tabPlaceable.height + bodyPlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                tabPlaceable.place(0, 0)
                bodyPlaceable.place(0, tabPlaceable.height)
            }
        }
    }
}

/** 文件夹轮廓:标签页 [0, tabWidth] × [0, tabHeight],右边一段 S 形下到正文顶边 */
private fun folderOutline(size: Size, tabWidth: Float, tabHeight: Float, corner: Float): Path {
    val w = size.width
    val h = size.height
    val r = corner.coerceAtMost(tabHeight)
    val slope = tabHeight * 1.1f
    val tabEnd = tabWidth.coerceIn(r * 2, (w - slope - r * 2).coerceAtLeast(r * 2))
    return Path().apply {
        moveTo(0f, r)
        arcTo(Rect(0f, 0f, r * 2, r * 2), 180f, 90f, false)
        lineTo(tabEnd - r * 0.3f, 0f)
        cubicTo(tabEnd + slope * 0.45f, 0f, tabEnd + slope * 0.45f, tabHeight, tabEnd + slope, tabHeight)
        lineTo(w - r, tabHeight)
        arcTo(Rect(w - r * 2, tabHeight, w, tabHeight + r * 2), 270f, 90f, false)
        lineTo(w, h - r)
        arcTo(Rect(w - r * 2, h - r * 2, w, h), 0f, 90f, false)
        lineTo(r, h)
        arcTo(Rect(0f, h - r * 2, r * 2, h), 90f, 90f, false)
        close()
    }
}
