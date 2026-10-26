package xyz.yophon.gopan.feature.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.SearchFilters
import xyz.yophon.gopan.core.data.SearchKind
import xyz.yophon.gopan.core.data.SearchSize
import xyz.yophon.gopan.core.data.SearchSort
import xyz.yophon.gopan.core.data.SearchTime

/**
 * 搜索筛选(doc/30):搜索框下面一排小标签,点开选;横着能滑。
 * 「只在某个文件夹里」从文件夹点搜索进来时才有,点 × 搜全部。内容标签不显示排序(内容搜索只按修改时间)。
 */
@Composable
fun SearchFilterBar(filters: SearchFilters, tab: SearchTab, onChange: (SearchFilters) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.scope?.let { scope ->
            InputChip(
                selected = true,
                onClick = { onChange(filters.copy(scope = null)) },
                label = { Text(stringResource(R.string.search_scope, scope.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_scope_clear), modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("search_scope"),
            )
        }
        KindChip(filters, tab, onChange)
        ChoiceChip(
            label = if (filters.time == SearchTime.ANY) stringResource(R.string.search_filter_time) else stringResource(timeLabel(filters.time)),
            selected = filters.time != SearchTime.ANY,
            options = SearchTime.entries,
            current = filters.time,
            optionLabel = { timeLabel(it) },
            onPick = { onChange(filters.copy(time = it)) },
            tag = "search_filter_time",
        )
        ChoiceChip(
            label = if (filters.size == SearchSize.ANY) stringResource(R.string.search_filter_size) else stringResource(sizeLabel(filters.size)),
            selected = filters.size != SearchSize.ANY,
            options = SearchSize.entries,
            current = filters.size,
            optionLabel = { sizeLabel(it) },
            onPick = { onChange(filters.copy(size = it)) },
            tag = "search_filter_size",
        )
        if (tab == SearchTab.NAME) {
            ChoiceChip(
                label = stringResource(sortLabel(filters.sort)),
                selected = filters.sort != SearchSort.UPDATED,
                options = SearchSort.entries,
                current = filters.sort,
                optionLabel = { sortLabel(it) },
                onPick = { onChange(filters.copy(sort = it)) },
                tag = "search_filter_sort",
            )
        }
        if (!filters.isDefault) {
            TextButton(onClick = { onChange(SearchFilters()) }) { Text(stringResource(R.string.search_filter_clear)) }
        }
    }
}

@Composable
private fun KindChip(filters: SearchFilters, tab: SearchTab, onChange: (SearchFilters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val kinds = filters.kinds
    val first = kinds.minByOrNull { it.ordinal }
    val label = when {
        first == null -> stringResource(R.string.search_filter_type)
        kinds.size == 1 -> stringResource(kindLabel(first))
        else -> stringResource(R.string.search_filter_types_many, stringResource(kindLabel(first)), kinds.size)
    }
    Box {
        FilterChip(
            selected = kinds.isNotEmpty(),
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("search_filter_type"),
        )
        // 多选:点一项只勾上 / 去掉,菜单不关
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SearchKind.entries.forEach { kind ->
                val disabled = tab == SearchTab.CONTENT && kind == SearchKind.FOLDER
                DropdownMenuItem(
                    text = { Text(stringResource(kindLabel(kind))) },
                    enabled = !disabled,
                    leadingIcon = {
                        if (kind in kinds) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp))
                    },
                    onClick = { onChange(filters.copy(kinds = if (kind in kinds) kinds - kind else kinds + kind)) },
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceChip(
    label: String,
    selected: Boolean,
    options: List<T>,
    current: T,
    optionLabel: (T) -> Int,
    onPick: (T) -> Unit,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(stringResource(optionLabel(o))) },
                    leadingIcon = { if (o == current) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onPick(o)
                    },
                )
            }
        }
    }
}

private fun kindLabel(k: SearchKind): Int = when (k) {
    SearchKind.FOLDER -> R.string.search_kind_folder
    SearchKind.IMAGE -> R.string.search_kind_image
    SearchKind.VIDEO -> R.string.search_kind_video
    SearchKind.AUDIO -> R.string.search_kind_audio
    SearchKind.DOCUMENT -> R.string.search_kind_document
    SearchKind.TEXT -> R.string.search_kind_text
    SearchKind.ARCHIVE -> R.string.search_kind_archive
    SearchKind.OTHER -> R.string.search_kind_other
}

private fun timeLabel(t: SearchTime): Int = when (t) {
    SearchTime.ANY -> R.string.search_time_any
    SearchTime.TODAY -> R.string.search_time_today
    SearchTime.WEEK -> R.string.search_time_week
    SearchTime.MONTH -> R.string.search_time_month
    SearchTime.YEAR -> R.string.search_time_year
    SearchTime.OLDER -> R.string.search_time_older
}

private fun sizeLabel(s: SearchSize): Int = when (s) {
    SearchSize.ANY -> R.string.search_size_any
    SearchSize.UNDER_1M -> R.string.search_size_under_1m
    SearchSize.M1_TO_100M -> R.string.search_size_1m_100m
    SearchSize.M100_TO_1G -> R.string.search_size_100m_1g
    SearchSize.OVER_1G -> R.string.search_size_over_1g
}

private fun sortLabel(s: SearchSort): Int = when (s) {
    SearchSort.UPDATED -> R.string.search_sort_updated
    SearchSort.NAME -> R.string.search_sort_name
    SearchSort.SIZE -> R.string.search_sort_size
}
