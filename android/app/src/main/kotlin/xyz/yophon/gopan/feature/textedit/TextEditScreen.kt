package xyz.yophon.gopan.feature.textedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.resolve

/** 在线编辑文本(doc/25):顶栏是文件名与保存状态,右边「保存」;正文是等宽的整页输入框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditScreen(onBack: () -> Unit, viewModel: TextEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TextEditEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                TextEditEffect.Close -> currentOnBack()
            }
        }
    }
    BackHandler(onBack = viewModel::requestClose)

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                navigationIcon = {
                    IconButton(onClick = viewModel::requestClose, modifier = Modifier.testTag("textedit_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                title = {
                    Column {
                        Text(state.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            stringResource(
                                when {
                                    state.loading -> R.string.textedit_loading
                                    state.dirty -> R.string.textedit_unsaved
                                    else -> R.string.textedit_saved_state
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp).padding(4.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { viewModel.save() }, enabled = state.dirty, modifier = Modifier.testTag("textedit_save")) {
                            Text(stringResource(R.string.textedit_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val loadError = state.loadError
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                loadError != null -> ErrorState(loadError.resolve(), onRetry = viewModel::load)
                else -> Column(Modifier.fillMaxSize()) {
                    HorizontalDivider()
                    BasicTextField(
                        value = state.content,
                        onValueChange = viewModel::onChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("textedit_field"),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                }
            }
        }
    }

    if (state.conflict) {
        ConfirmDialog(
            title = stringResource(R.string.textedit_conflict_title),
            text = stringResource(R.string.textedit_conflict_text),
            confirmText = stringResource(R.string.textedit_save_anyway),
            onConfirm = { viewModel.save(force = true) },
            onDismiss = viewModel::dismissConflict,
        )
    }
    if (state.confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.textedit_leave_title),
            text = stringResource(R.string.textedit_leave_text),
            confirmText = stringResource(R.string.textedit_discard),
            destructive = true,
            onConfirm = viewModel::discardAndClose,
            onDismiss = viewModel::dismissLeave,
        )
    }
}
