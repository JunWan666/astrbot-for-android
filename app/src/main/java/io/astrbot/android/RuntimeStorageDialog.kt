package io.astrbot.android

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.RuntimeStorageAdvice

@Composable
internal fun RuntimeStorageDialog(advice: RuntimeStorageAdvice, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(AppText.get(R.string.runtime_storage_warning_title)) },
        text = { Text(advice.message, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onContinue) { Text(AppText.get(R.string.runtime_storage_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_cancel)) } })
}
