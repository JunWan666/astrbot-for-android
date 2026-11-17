package io.astrbot.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.BackupSelection

@Composable internal fun PortableBackupExportDialog(onExport: (BackupSelection) -> Unit, onDismiss: () -> Unit,
    onShare: ((BackupSelection) -> Unit)? = null) {
    var mask by rememberSaveable { mutableIntStateOf(1) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.backup_choose_contents)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(R.string.backup_contents_hint), style = MaterialTheme.typography.bodySmall)
            listOf(Triple(1, R.string.backup_scope_astrbot, R.string.backup_scope_astrbot_hint),
                Triple(4, R.string.backup_scope_qq, R.string.backup_scope_qq_hint)).forEach { (bit, title, hint) ->
                Row(Modifier.fillMaxWidth().clickable { mask = mask xor bit }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(mask and bit != 0, onCheckedChange = null)
                    Column { Text(AppText.get(title)); Text(AppText.get(hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = mask != 0, onClick = { (onShare ?: onExport)(BackupSelection.fromMask(mask)) }) {
        Text(AppText.get(if (onShare != null) R.string.export_share else R.string.ui_choose_destination))
    } }, dismissButton = { TextButton(enabled = mask != 0, onClick = { onExport(BackupSelection.fromMask(mask)) }) { Text(AppText.get(R.string.export_save)) } })
}

internal fun portableScopeLabel(scopes: List<String>): String = buildList {
    if ("astrbot" in scopes) add(AppText.get(R.string.backup_scope_astrbot))
    if ("napcat/config" in scopes) add(AppText.get(R.string.backup_scope_legacy))
    if (scopes.any { it in BackupSelection.QQ_SCOPES }) add(AppText.get(R.string.backup_scope_qq))
}.joinToString(" · ")
