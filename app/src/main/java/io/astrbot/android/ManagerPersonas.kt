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
    var category by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    val scrollPosition = rememberPersonaScrollPosition()
    val scroll = scrollPosition.state
    val scope = rememberCoroutineScope()
    fun load() { scope.launch {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { PersonaLibrary.read(context) } }
        result.onSuccess { presets = it; libraryError = null }.onFailure { libraryError = it.message ?: "无法读取人设库" }
        loading = false
    } }
    LaunchedEffect(context) {
        val result = withContext(Dispatchers.IO) { runCatching { PersonaLibrary.read(context) } }
        result.onSuccess { presets = it }.onFailure { libraryError = it.message ?: "无法读取人设库" }
        loading = false
    }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<PersonaPreset?, String>?>(null) }
    val installation = state.personas
    val available = state.componentsReady && !state.busy && installation.unavailable == null && !saving && libraryError == null
    val filtered = presets.filter { (category == "全部" || it.category == category) &&
        (query.isBlank() || "${it.name}${it.intro}${it.category}".contains(query.trim(), ignoreCase = true)) }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "人设库内容" }) {
        val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.25f
        val showDefault = maxHeight >= 330.dp
        val columns = if (maxWidth >= 330.dp) 2 else 1
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("给聊天换个性格", style = MaterialTheme.typography.titleMedium)
                    if (!compact) Text("${presets.size} 款本地人设 · 可添加、删除和切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (installation.canUndo) TextButton(enabled = available, onClick = { confirmation = null to "persona-undo" }) { Text("恢复上次") }
            }
            if (showDefault) Text("默认：${installation.currentId?.removePrefix("安卓预设·") ?: "尚未读取"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerActionButton("人设市场 ↗", { onExternal(PersonaLibrary.MARKET_URL) }, Modifier.weight(1f), ActionTone.NEUTRAL,
                    description = "打开外部人设市场")
                ManagerActionButton("新增人设", { actionError = null; showAdd = true }, Modifier.weight(1f),
                    enabled = !loading && !saving && libraryError == null)
            }
            OutlinedTextField(query, { scrollPosition.interacted(); query = it.take(50); scope.launch { scroll.scrollTo(0) } }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索人设，例如 大肥鱼") }, shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                (listOf("全部") + PersonaLibrary.categories).forEach { item ->
                    TextButton(onClick = { scrollPosition.interacted(); category = item; scope.launch { scroll.scrollTo(0) } }, Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.textButtonColors(
                            contentColor = if (category == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(item, fontWeight = if (category == item) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            if (loading || libraryError != null || filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when { loading -> "正在读取人设…"; libraryError != null -> libraryError!!
                        presets.isEmpty() -> "还没有人设，点击“新增人设”添加"; else -> "没有找到匹配的人设" })
                    if (!loading && libraryError != null) TextButton(onClick = ::load) { Text("重试") }
                }
            }
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                .semantics { contentDescription = "人设卡片列表" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(if (compact) 100.dp else 138.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(columns) { column ->
                            val preset = row.getOrNull(column)
                            if (preset == null) Spacer(Modifier.weight(1f))
                            else Surface(Modifier.weight(1f).fillMaxHeight().clickable { previewId = preset.id }
                                .semantics { contentDescription = "预览人设 ${preset.name}" },
                                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(preset.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!compact) Text(preset.intro, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(when (preset.databaseId) {
                                        installation.currentId -> "正在使用 · ${preset.category}"
                                        in installation.imported -> "已添加 · ${preset.category}"
                                        else -> "${preset.category} · 查看人设 ›"
                                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Text("${filtered.size} 款人设 · 上下滑动浏览", style = MaterialTheme.typography.labelSmall,
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
                            if (preset.greeting.isNotBlank()) Text("开场示例：${preset.greeting}", style = MaterialTheme.typography.bodySmall)
                            Text(preset.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    installation.unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ManagerActionButton("仅添加", { confirmation = preset to "persona-add" }, Modifier.weight(1f), ActionTone.NEUTRAL, available)
                        ManagerActionButton("设为默认", { confirmation = preset to "persona-apply" }, Modifier.weight(1f), enabled = available)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onCopy(preset.prompt) }, contentPadding = PaddingValues(4.dp)) { Text("复制提示词") }
                        if (preset.sourceUrl.isNotBlank()) TextButton(onClick = { onExternal(preset.sourceUrl) }, contentPadding = PaddingValues(4.dp)) { Text("参考来源") }
                        TextButton(enabled = !saving, onClick = { actionError = null; deleteId = preset.id },
                            modifier = Modifier.semantics { contentDescription = "删除人设 ${preset.name}" },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(4.dp)) { Text("删除") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { previewId = null }) { Text("完成") } },
            dismissButton = { TextButton(enabled = state.services.getValue(ServiceId.ASTRBOT).canOpenWebUi, onClick = onBackend) { Text("AstrBot 后台") } })
    }
    confirmation?.let { (preset, action) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (action == "persona-undo") "恢复上次默认人设" else "应用 ${preset?.name}") },
            text = { Text("将${if (action == "persona-add") "添加到 AstrBot 人设列表，保持当前默认人设" else if (action == "persona-undo") "恢复上一次默认人设" else "添加并设为默认人设"}。操作期间 AstrBot 会短暂停止，完成后恢复原运行状态，NapCat 继续运行。\n\n已有同名人设及后台修改会保留。会话单独指定的人设不变，建议新建会话体验。") },
            confirmButton = { TextButton(enabled = available, onClick = { confirmation = null; previewId = null; onApply(preset, action) }) { Text("确认应用") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
    if (showAdd) AddPersonaDialog(saving, actionError, onDismiss = { if (!saving) showAdd = false },
        onSave = { name, chosenCategory, intro, prompt, greeting -> scope.launch {
            saving = true; actionError = null
            val result = withContext(Dispatchers.IO) { runCatching {
                PersonaLibrary.add(context, name, chosenCategory, intro, prompt, greeting)
                PersonaLibrary.read(context)
            } }
            result.onSuccess {
                presets = it; category = "全部"; query = ""; showAdd = false
                scrollPosition.interacted(); scroll.scrollTo(0)
            }.onFailure { actionError = it.message ?: "保存失败，请重试" }
            saving = false
        } })
    presets.firstOrNull { it.id == deleteId }?.let { preset ->
        AlertDialog(onDismissRequest = { if (!saving) deleteId = null }, title = { Text("删除人设？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将从本地列表删除“${preset.name}”。已添加到 AstrBot 的副本和当前默认人设会保留，可在 AstrBot 后台管理。")
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(enabled = !saving, onClick = { scope.launch {
                saving = true; actionError = null
                val result = withContext(Dispatchers.IO) { runCatching {
                    PersonaLibrary.delete(context, preset.id); PersonaLibrary.read(context)
                } }
                result.onSuccess { presets = it; deleteId = null; previewId = null }
                    .onFailure { actionError = it.message ?: "删除失败，请重试" }
                saving = false
            } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (saving) "正在删除…" else "确认删除") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { deleteId = null }) { Text("取消") } })
    }
}
