package io.astrbot.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only the card area scrolls; search, categories and the app navigation stay in place. */
@Composable internal fun ManagerPersonas(state: ManagerState, onApply: (PersonaPreset?, String) -> Unit,
    onCopy: (String) -> Unit, onExternal: (String) -> Unit, onBackend: () -> Unit) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf<List<PersonaPreset>>(emptyList()) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val scrollPosition = rememberPersonaScrollPosition()
    val scroll = scrollPosition.state
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { PersonaLibrary.read(context) } }
        result.onSuccess { presets = it; libraryError = null }.onFailure { libraryError = it.message ?: AppText.get(R.string.ui_unable_to_read_the_persona_library) }
        loading = false
    } }
    LaunchedEffect(context) {
        val result = withContext(Dispatchers.IO) { runCatching { PersonaLibrary.read(context) } }
        result.onSuccess { presets = it }.onFailure { libraryError = it.message ?: AppText.get(R.string.ui_unable_to_read_the_persona_library) }
        loading = false
    }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<PersonaPreset?, String>?>(null) }
    val installation = state.personas
    val available = state.componentsReady && !state.busy && installation.unavailable == null && !saving && libraryError == null
    val filtered = presets.filter { (category == "" || it.category == category) &&
        (query.isBlank() || "${it.name}${it.intro}${personaCategoryLabel(it.category)}".contains(query.trim(), ignoreCase = true)) }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_persona_library_content) }) {
        val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.25f
        val showDefault = maxHeight >= 330.dp
        val columns = if (maxWidth >= 330.dp) 2 else 1
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(AppText.get(R.string.ui_give_your_chats_a_new_personality), style = MaterialTheme.typography.titleMedium)
                    if (!compact) Text(AppText.get(R.string.ui_local_personas_add_delete_and_switch, presets.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (installation.canUndo) TextButton(enabled = available, onClick = { confirmation = null to "persona-undo" }) { Text(AppText.get(R.string.ui_restore_previous)) }
            }
            if (showDefault) Text(AppText.get(R.string.ui_default, installation.currentId?.removePrefix("安卓预设·") ?: AppText.get(R.string.ui_not_loaded_yet)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerActionButton(AppText.get(R.string.ui_marketplace), { onExternal(PersonaLibrary.MARKET_URL) }, Modifier.weight(1f), ActionTone.NEUTRAL,
                    description = AppText.get(R.string.ui_open_external_persona_marketplace))
                ManagerActionButton(AppText.get(R.string.ui_add_persona), { actionError = null; showAdd = true }, Modifier.weight(1f),
                    enabled = !loading && !saving && libraryError == null)
            }
            OutlinedTextField(query, { scrollPosition.interacted(); query = it.take(50); scope.launch { scroll.scrollTo(0) } }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(AppText.get(R.string.ui_search_personas)) }, shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                (listOf("") + PersonaLibrary.categories).forEach { item ->
                    TextButton(onClick = { scrollPosition.interacted(); category = item; scope.launch { scroll.scrollTo(0) } }, Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.textButtonColors(
                            contentColor = if (category == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(personaCategoryLabel(item), fontWeight = if (category == item) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (loading || libraryError != null || filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when { loading -> AppText.get(R.string.ui_loading_personas); libraryError != null -> libraryError!!
                        presets.isEmpty() -> AppText.get(R.string.ui_no_personas_yet_select_add_persona_to_create_one); else -> AppText.get(R.string.ui_no_matching_personas) })
                    if (!loading && libraryError != null) TextButton(onClick = ::load) { Text(AppText.get(R.string.ui_retry)) }
                }
            }
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                .semantics { contentDescription = AppText.get(R.string.ui_persona_card_list) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(if (compact) 100.dp else 138.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(columns) { column ->
                            val preset = row.getOrNull(column)
                            if (preset == null) Spacer(Modifier.weight(1f))
                            else Surface(Modifier.weight(1f).fillMaxHeight().clickable { previewId = preset.id }
                                .semantics { contentDescription = AppText.get(R.string.ui_preview_persona, preset.name) },
                                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(preset.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!compact) Text(preset.intro, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(when (preset.databaseId) {
                                        installation.currentId -> AppText.get(R.string.ui_in_use, personaCategoryLabel(preset.category))
                                        in installation.imported -> AppText.get(R.string.ui_added, personaCategoryLabel(preset.category))
                                        else -> AppText.get(R.string.ui_view_persona, personaCategoryLabel(preset.category))
                                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Text(AppText.get(R.string.ui_personas_scroll_to_browse, filtered.size), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    presets.firstOrNull { it.id == previewId }?.let { preset ->
        AlertDialog(onDismissRequest = { previewId = null }, title = { Text(preset.name) },
            text = {
                Column(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (preset.intro.isNotBlank()) Text(preset.intro, style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(preset.prompt, style = MaterialTheme.typography.bodyMedium)
                            if (preset.greeting.isNotBlank()) Text(AppText.get(R.string.ui_greeting_example, preset.greeting), style = MaterialTheme.typography.bodySmall)
                            Text(if (preset.id.startsWith("custom-")) AppText.get(R.string.persona_local_source) else preset.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    installation.unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ManagerActionButton(AppText.get(R.string.ui_add_only), { confirmation = preset to "persona-add" }, Modifier.weight(1f), ActionTone.NEUTRAL, available)
                        ManagerActionButton(AppText.get(R.string.ui_set_default), { confirmation = preset to "persona-apply" }, Modifier.weight(1f), enabled = available)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onCopy(preset.prompt) }, contentPadding = PaddingValues(4.dp)) { Text(AppText.get(R.string.ui_copy_prompt)) }
                        if (preset.sourceUrl.isNotBlank()) TextButton(onClick = { onExternal(preset.sourceUrl) }, contentPadding = PaddingValues(4.dp)) { Text(AppText.get(R.string.ui_source)) }
                        TextButton(enabled = !saving, onClick = { actionError = null; deleteId = preset.id },
                            modifier = Modifier.semantics { contentDescription = AppText.get(R.string.ui_delete_persona, preset.name) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(4.dp)) { Text(AppText.get(R.string.ui_delete)) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { previewId = null }) { Text(AppText.get(R.string.ui_done)) } },
            dismissButton = { TextButton(enabled = state.services.getValue(ServiceId.ASTRBOT).canOpenWebUi, onClick = onBackend) { Text(AppText.get(R.string.ui_astrbot_webui)) } })
    }
    confirmation?.let { (preset, action) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (action == "persona-undo") AppText.get(R.string.ui_restore_previous_default_persona) else AppText.get(R.string.ui_apply, preset?.name)) },
            text = { Text(AppText.get(R.string.ui_this_will_astrbot_briefly_stops_then_resumes_its_previous, if (action == "persona-add") AppText.get(R.string.ui_add_this_persona_to_astrbot_without_changing_the_default) else if (action == "persona-undo") AppText.get(R.string.ui_restore_the_previous_default_persona) else AppText.get(R.string.ui_add_this_persona_and_make_it_the_default))) },
            confirmButton = { TextButton(enabled = available, onClick = { confirmation = null; previewId = null; onApply(preset, action) }) { Text(AppText.get(R.string.ui_apply_a62df)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(AppText.get(R.string.ui_cancel)) } })
    }
    if (showAdd) AddPersonaDialog(saving, actionError, onDismiss = { if (!saving) showAdd = false },
        onSave = { name, chosenCategory, intro, prompt, greeting -> scope.launch {
            saving = true; actionError = null
            val result = withContext(Dispatchers.IO) { runCatching {
                PersonaLibrary.add(context, name, chosenCategory, intro, prompt, greeting)
                PersonaLibrary.read(context)
            } }
            result.onSuccess {
                presets = it; category = ""; query = ""; showAdd = false
                scrollPosition.interacted(); scroll.scrollTo(0)
            }.onFailure { actionError = it.message ?: AppText.get(R.string.ui_unable_to_save_please_retry) }
            saving = false
        } })
    presets.firstOrNull { it.id == deleteId }?.let { preset ->
        AlertDialog(onDismissRequest = { if (!saving) deleteId = null }, title = { Text(AppText.get(R.string.ui_delete_persona_f6f53)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppText.get(R.string.ui_remove_from_the_local_list_copies_already_added_to, preset.name))
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(enabled = !saving, onClick = { scope.launch {
                saving = true; actionError = null
                val result = withContext(Dispatchers.IO) { runCatching {
                    PersonaLibrary.delete(context, preset.id); PersonaLibrary.read(context)
                } }
                result.onSuccess { presets = it; deleteId = null; previewId = null }
                    .onFailure { actionError = it.message ?: AppText.get(R.string.ui_unable_to_delete_please_retry) }
                saving = false
            } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (saving) AppText.get(R.string.ui_deleting) else AppText.get(R.string.ui_confirm_deletion)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { deleteId = null }) { Text(AppText.get(R.string.ui_cancel)) } })
    }
}
