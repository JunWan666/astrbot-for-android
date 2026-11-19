package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.PendingUpdateArchive
import io.astrbot.android.runtime.UpdateInfo

@Composable
internal fun LocalUpdateImportDialog(
    pending: PendingUpdateArchive?,
    ready: UpdateInfo?,
    progress: String?,
    error: String?,
    busy: Boolean,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val missingSource = pending?.kind == "dashboard"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppText.get(R.string.ui_import_update_package)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ready != null) {
                    Text(AppText.get(R.string.upstream_import_ready, ready.version), style = MaterialTheme.typography.titleSmall)
                    Text(AppText.get(R.string.upstream_import_install_notice))
                } else {
                    Text(AppText.get(R.string.upstream_import_intro))
                    for ((kind, name) in listOf("source" to R.string.upstream_import_program, "dashboard" to R.string.upstream_import_web)) {
                        val selected = pending?.takeIf { it.kind == kind }
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(AppText.get(name), style = MaterialTheme.typography.titleSmall)
                                Text(if (selected != null) AppText.get(R.string.upstream_import_selected, selected.version)
                                    else AppText.get(R.string.upstream_import_not_selected),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (pending != null) Text(AppText.get(
                        if (missingSource) R.string.upstream_import_need_program else R.string.upstream_import_need_web,
                        pending.version))
                }
                if (progress != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (pending != null) TextButton(enabled = !busy, onClick = onClear) {
                    Text(AppText.get(R.string.upstream_import_remove))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = if (ready != null) onInstall else onChoose) {
                Text(AppText.get(when {
                    ready != null -> R.string.upstream_import_install
                    pending == null -> R.string.upstream_import_choose
                    missingSource -> R.string.upstream_import_add_program
                    else -> R.string.upstream_import_add_web
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) } }
    )
}
