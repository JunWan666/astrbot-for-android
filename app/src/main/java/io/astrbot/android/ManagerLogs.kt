package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import io.astrbot.android.runtime.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun ManagerLogViewer(text: String, paused: Boolean, onPause: () -> Unit, onCopy: () -> Unit,
    onExport: () -> Unit, modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(), onClear: (() -> Unit)? = null,
    shareCategory: LogCategory? = null) {
    var confirmClear by remember { mutableStateOf(false) }
    var exportOptions by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (exportOptions) AlertDialog(onDismissRequest = { exportOptions = false }, title = { Text(AppText.get(R.string.export_log_title)) },
        text = { Text(AppText.get(R.string.ui_only_recognized_credentials_and_login_qr_codes_are_hidden)) },
        confirmButton = { TextButton(onClick = {
            val category = shareCategory ?: return@TextButton
            exportOptions = false; sharing = true
            scope.launch {
                try { SharedExports.launch(context, SharedExports.log(context, category), "text/plain") }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { Toast.makeText(context, AppText.get(R.string.export_failed), Toast.LENGTH_LONG).show() }
                finally { sharing = false }
            }
        }) { Text(AppText.get(R.string.export_share)) } },
        dismissButton = { TextButton(onClick = { exportOptions = false; onExport() }) { Text(AppText.get(R.string.export_save)) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(AppText.get(R.string.logs_clear)) },
        text = { Text(AppText.get(R.string.logs_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear?.invoke() }) { Text(AppText.get(R.string.logs_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(AppText.get(R.string.action_cancel)) } })
    BoxWithConstraints(modifier) {
        LaunchedEffect(text, paused, scroll.maxValue) { if (!paused) scroll.scrollTo(scroll.maxValue) }
        val compact = maxHeight < 320.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(if (paused) AppText.get(R.string.ui_resume) else AppText.get(R.string.ui_pause), onPause, Modifier.weight(1f), ActionTone.NEUTRAL)
                ManagerActionButton(AppText.get(R.string.ui_copy), onCopy, Modifier.weight(1f), ActionTone.NEUTRAL, text.isNotBlank())
                ManagerActionButton(AppText.get(R.string.ui_export), { if (shareCategory == null) onExport() else exportOptions = true }, Modifier.weight(1f), enabled = !sharing)
                if (onClear != null) ManagerActionButton(AppText.get(R.string.logs_clear), { confirmClear = true }, Modifier.weight(1f), ActionTone.STOP)
            }
            if (sharing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!compact) Text(if (paused) AppText.get(R.string.ui_paused_select_text_to_copy) else AppText.get(R.string.ui_following_latest_logs_refreshing_every_2_seconds),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = AppText.get(R.string.ui_log_content) },
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                SelectionContainer {
                    Text(text.ifBlank { AppText.get(R.string.ui_no_logs_yet) }, Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (!compact) Text(AppText.get(R.string.ui_copy_the_displayed_content_or_export_all_retained_redacted),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
