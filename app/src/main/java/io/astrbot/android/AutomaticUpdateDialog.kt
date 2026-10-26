package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun AutomaticUpdateDialog(enabled: Boolean, configured: Boolean,
    onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_automatic_update_checks)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (enabled) AppText.get(R.string.ui_on) else AppText.get(R.string.ui_off), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = configured || enabled,
                    modifier = Modifier.semantics { contentDescription = AppText.get(R.string.ui_automatic_update_checks_switch) })
            }
            Text(AppText.get(R.string.ui_check_for_compatible_astrbot_updates_at_most_once_a),
                style = MaterialTheme.typography.bodySmall)
            Text(AppText.get(R.string.ui_review_release_notes_when_an_update_is_found_downloads), style = MaterialTheme.typography.bodySmall)
            if (!configured) Text(AppText.get(R.string.ui_this_build_has_no_online_update_channel_configured_you),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_done)) } })
}
