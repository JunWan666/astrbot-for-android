package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.PersonaLibrary

@Composable internal fun AddPersonaDialog(saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("趣味") }
    var intro by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    val valid = name.trim().length in 1..30 && name.trim().none { it.isISOControl() } &&
        prompt.trim().length in 1..12_000 && intro.trim().length <= 120 && greeting.trim().length <= 1000
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_add_persona)) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppText.get(R.string.ui_paste_a_prompt_from_the_persona_marketplace_save_it), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.ui_persona_name)) },
                singleLine = true, enabled = !saving, isError = name.length > 30, supportingText = { Text("${name.length}/30") })
            Row(Modifier.fillMaxWidth()) { PersonaLibrary.categories.forEach { value ->
                TextButton(enabled = !saving, onClick = { category = value }, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp), colors = ButtonDefaults.textButtonColors(
                        contentColor = if (category == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text((if (category == value) "✓ " else "") + personaCategoryLabel(value))
                }
            } }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.ui_persona_prompt)) },
                minLines = 5, maxLines = 8, enabled = !saving, isError = prompt.length > 12_000,
                supportingText = { Text("${prompt.length}/12000") })
            OutlinedTextField(intro, { intro = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.ui_description_optional)) },
                maxLines = 2, enabled = !saving, isError = intro.length > 120, supportingText = { Text(AppText.get(R.string.ui_up_to_120_characters)) })
            OutlinedTextField(greeting, { greeting = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.ui_greeting_example_optional)) },
                maxLines = 3, enabled = !saving, isError = greeting.length > 1000, supportingText = { Text(AppText.get(R.string.ui_up_to_1_000_characters)) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && valid, onClick = { onSave(name, category, intro, prompt, greeting) }) {
        Text(if (saving) AppText.get(R.string.ui_saving) else AppText.get(R.string.ui_save_persona))
    } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(AppText.get(R.string.ui_cancel)) } })
}
