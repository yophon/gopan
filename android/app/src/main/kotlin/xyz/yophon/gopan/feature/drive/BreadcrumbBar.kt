package xyz.yophon.gopan.feature.drive

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R

/**
 * 面包屑:根「文件」→ 各级文件夹,横向可滚动,新增一级时自动滚到最右。
 * 最后一级是当前位置,不可点;其余点击回调下标(调用方决定 popUpTo 还是 navigate)。
 * [crumbs] 第 0 项是根(id 为 null);名字为空的文件夹还在解析中,显示省略号。
 */
@Composable
fun BreadcrumbBar(
    crumbs: List<Crumb>,
    onClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootName = stringResource(R.string.drive_title_root)
    val loadingName = stringResource(R.string.drive_crumb_loading)
    val scrollState = rememberScrollState()
    // maxValue 在布局后才更新,所以把它也当 key:内容一变就跟到最右
    LaunchedEffect(crumbs, scrollState.maxValue) { scrollState.scrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val last = index == crumbs.lastIndex
            val name = when {
                crumb.isRoot -> rootName
                crumb.name.isEmpty() -> loadingName
                else -> crumb.name
            }
            TextButton(
                onClick = { onClick(index) },
                enabled = !last,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
