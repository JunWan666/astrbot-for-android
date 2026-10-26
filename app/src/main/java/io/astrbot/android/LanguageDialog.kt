package io.astrbot.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable internal fun LanguageDialog(onDismiss: () -> Unit, onSelected: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val selected = AppLanguage.selected(context)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.language_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(Modifier.fillMaxWidth().selectable(selected == language, role = Role.RadioButton,
                        onClick = { onSelected(language) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == language, onClick = null)
                        Text(if (language == AppLanguage.SYSTEM) AppText.get(R.string.language_system) else language.displayName,
                            Modifier.padding(start = 12.dp))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.action_cancel)) } })
}
