package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun DataRetentionDialog(onPortableBackup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_data_and_uninstallation)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(R.string.ui_uninstalling_the_app_or_selecting_clear_data_deletes_the))
            Text(AppText.get(R.string.ui_before_uninstalling_clearing_data_or_moving_to_another_phone))
            Text(AppText.get(R.string.ui_portable_backups_contain_configuration_and_credentials_but_exclude_qq),
                style = MaterialTheme.typography.bodySmall)
            Text(AppText.get(R.string.ui_for_normal_updates_install_an_apk_with_the_same),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onPortableBackup) { Text(AppText.get(R.string.ui_open_portable_backups)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) } })
}
