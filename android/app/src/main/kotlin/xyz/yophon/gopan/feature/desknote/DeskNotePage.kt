package xyz.yophon.gopan.feature.desknote

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import java.time.LocalDateTime
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimeInput
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.desknote.DeskNote
import xyz.yophon.gopan.core.data.desknote.DeskNoteItem
import xyz.yophon.gopan.core.data.desknote.DeskNoteOp
import xyz.yophon.gopan.core.data.desknote.DeskNotes
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.preview.bottomBarSpace
import xyz.yophon.gopan.feature.preview.topBarSpace

private const val TITLE = -1
private val DueColor = Color(0xFFE55B5B)

/** 预览页里的便签清单(doc/39):用便签自己的底色和字色画成一张卡片 */
@Composable
fun DeskNotePage(nodeId: String, parentId: String?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<DeskNoteViewModel, DeskNoteViewModel.Factory>(key = "desknote-$nodeId") { it.create(nodeId, parentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it.resolve(context), Toast.LENGTH_SHORT).show() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val note = state.note
    Box(modifier.fillMaxSize().testTag("preview_desknote"), contentAlignment = Alignment.TopCenter) {
        when {
            note != null -> DeskNoteCard(note, state.pending > 0, state.failed, viewModel::run)
            state.loadError != null -> Column(Modifier.padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.loadError?.resolve().orEmpty().ifEmpty { stringResource(R.string.desknote_load_failed) })
                TextButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.desknote_retry)) }
            }
            else -> CircularProgressIndicator(Modifier.padding(top = 160.dp))
        }
    }
}

@Composable
private fun DeskNoteCard(note: DeskNote, saving: Boolean, failed: Boolean, onOp: (DeskNoteOp) -> Unit) {
    val bg = Color(0xFF000000 or note.bgColor)
    val fg = Color(0xFF000000 or note.textColor)
    val dim = fg.copy(alpha = 0.55f)
    val faint = fg.copy(alpha = 0.12f)
    val items = note.items
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var reminding by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = topBarSpace() + 8.dp, bottom = bottomBarSpace() + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(1.dp, fg.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title,
                    color = fg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { editing = TITLE }.testTag("desknote_title"),
                )
                Text(
                    stringResource(if (saving) R.string.desknote_saving else if (failed) R.string.desknote_failed else R.string.desknote_synced),
                    color = if (failed && !saving) DueColor else dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (items.isEmpty()) {
                Text(stringResource(R.string.desknote_empty), color = dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            items.forEachIndexed { i, it ->
                ItemRow(it, note.checkboxes, fg, dim, faint, bg,
                    onToggle = { onOp(DeskNoteOp.Toggle(i, it.text, !it.done)) },
                    onEdit = { editing = i },
                    onDelete = { onOp(DeskNoteOp.Remove(i, it.text)) },
                    onRemind = { reminding = i },
                )
            }
            AddRow(fg, dim, faint) { onOp(DeskNoteOp.Add(it)) }
        }
        Text(
            stringResource(R.string.desknote_foot),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
        )
    }

    val target = editing
    if (target != null && (target == TITLE || target in items.indices)) {
        val original = if (target == TITLE) note.title else items[target].text
        EditDialog(
            title = stringResource(if (target == TITLE) R.string.desknote_edit_title else R.string.desknote_edit_item),
            initial = original,
            singleLine = target == TITLE,
            onRemind = if (target == TITLE) null else ({ editing = null; reminding = target }),
            onDismiss = { editing = null },
            onConfirm = { text ->
                editing = null
                if (text.trim() != original) {
                    onOp(if (target == TITLE) DeskNoteOp.Title(text) else DeskNoteOp.Edit(target, original, text))
                }
            },
        )
    }

    val r = reminding
    if (r != null && r in items.indices) {
        val item = items[r]
        RemindDialog(
            item = item,
            onDismiss = { reminding = null },
            onConfirm = { remind, repeat ->
                reminding = null
                onOp(DeskNoteOp.Remind(r, item.text, remind, repeat))
            },
        )
    }
}

@Composable
private fun ItemRow(
    item: DeskNoteItem,
    checkboxes: Boolean,
    fg: Color,
    dim: Color,
    faint: Color,
    bg: Color,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemind: () -> Unit,
) {
    val done = checkboxes && item.done
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        if (checkboxes) {
            Box(
                Modifier
                    .padding(top = 9.dp, end = 12.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .then(if (item.done) Modifier.background(fg.copy(alpha = 0.8f)) else Modifier.border(1.5.dp, dim, RoundedCornerShape(5.dp)))
                    .clickable(onClick = onToggle)
                    .testTag("desknote_check"),
                contentAlignment = Alignment.Center,
            ) {
                if (item.done) Icon(Icons.Filled.Check, stringResource(R.string.desknote_toggle), tint = bg, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(Modifier.padding(start = 6.dp, top = 17.dp, end = 14.dp).size(5.dp).clip(CircleShape).background(dim))
        }
        val text = buildAnnotatedString {
            var last = 0
            for ((s, e, url) in DeskNotes.links(item.text)) {
                append(item.text.substring(last, s))
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                    append(item.text.substring(s, e))
                }
                last = e
            }
            append(item.text.substring(last))
        }
        Text(
            text,
            color = if (done) fg.copy(alpha = 0.45f) else fg,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp).clickable(onClick = onEdit),
        )
        if (item.remind != null) {
            val label = DeskNotes.formatRemind(item.remind, repeat = item.repeat)
            if (label.isNotEmpty()) {
                val c = if (item.due) DueColor else dim
                Row(
                    Modifier
                        .padding(start = 8.dp, top = 7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (item.due) DueColor.copy(alpha = 0.22f) else faint)
                        .clickable(onClick = onRemind)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("desknote_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(if (item.repeat != null) Icons.Outlined.Repeat else Icons.Outlined.Notifications, null, tint = c, modifier = Modifier.size(13.dp))
                    Text(label, color = c, fontSize = 12.sp, fontWeight = if (item.due) FontWeight.SemiBold else null)
                }
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).testTag("desknote_delete")) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.desknote_delete), tint = dim.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddRow(fg: Color, dim: Color, faint: Color, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Add, null, tint = dim, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = fg, fontSize = 16.sp),
            cursorBrush = SolidColor(fg),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("desknote_add"),
            decorationBox = { inner ->
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(if (text.isEmpty()) Color.Transparent else faint)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    if (text.isEmpty()) Text(stringResource(R.string.desknote_add_hint), color = dim, fontSize = 16.sp)
                    inner()
                }
            },
        )
        if (text.isNotBlank()) {
            TextButton(onClick = { submit() }) { Text(stringResource(R.string.desknote_add), color = fg) }
        }
    }
}

@Composable
private fun EditDialog(
    title: String,
    initial: String,
    singleLine: Boolean,
    onRemind: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth().testTag("desknote_edit"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.desknote_ok)) } },
        dismissButton = {
            Row {
                if (onRemind != null) TextButton(onClick = onRemind) { Text(stringResource(R.string.desknote_remind_open)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.desknote_cancel)) }
            }
        },
    )
}

// 设置提醒:日期用前一天 / 后一天切换,时间用系统的时间输入,下面选重复(和桌面端的对话框一样)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindDialog(item: DeskNoteItem, onDismiss: () -> Unit, onConfirm: (String?, String?) -> Unit) {
    val now = LocalDateTime.now()
    val init = remember(item) {
        item.remind?.let { runCatching { LocalDateTime.parse(it.take(16).replace(' ', 'T')) }.getOrNull() }
            ?: now.plusHours(1).withSecond(0).withNano(0).let { it.withMinute(0).plusMinutes(((it.minute + 4) / 5 * 5).toLong()) }
    }
    var day by remember(item) { mutableStateOf(init.toLocalDate()) }
    val time = rememberTimePickerState(initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
    var repeat by remember(item) { mutableStateOf(item.repeat) }
    val repeats = listOf(
        null to R.string.desknote_repeat_none,
        "daily" to R.string.desknote_repeat_daily,
        "weekdays" to R.string.desknote_repeat_weekdays,
        "weekly" to R.string.desknote_repeat_weekly,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.desknote_remind_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("「${item.text}」", fontWeight = FontWeight.SemiBold, maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = day.minusDays(1) }, enabled = day.isAfter(now.toLocalDate())) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null)
                    }
                    Text(
                        "${day.monthValue}月${day.dayOfMonth}日 周${"一二三四五六日"[day.dayOfWeek.value - 1]}",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { day = day.plusDays(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }
                }
                TimeInput(state = time, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((value, label) in repeats) {
                        FilterChip(selected = repeat == value, onClick = { repeat = value }, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.desknote_remind_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = day.atTime(time.hour, time.minute)
                onConfirm("%04d-%02d-%02d %02d:%02d".format(at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute), repeat)
            }) { Text(stringResource(R.string.desknote_ok)) }
        },
        dismissButton = {
            Row {
                if (item.remind != null) TextButton(onClick = { onConfirm(null, null) }) {
                    Text(stringResource(R.string.desknote_remind_remove), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.desknote_cancel)) }
            }
        },
    )
}
