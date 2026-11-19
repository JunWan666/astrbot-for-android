package io.astrbot.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable internal fun UserTasksScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tasks by UserTaskStore.state.collectAsStateWithLifecycle()
    val manager by RuntimeStore.state.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<UserTaskDefinition?>(null) }
    var logs by rememberSaveable { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(loadAttempt) { withContext(Dispatchers.IO) { UserTaskStore.load(context) } }
    LaunchedEffect(tasks.active, lifecycle) {
        if (tasks.active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = System.currentTimeMillis(); delay(1000) }
        }
    }
    fun command(action: String, id: String) { RuntimeService.command(context, action, taskId = id) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(AppText.get(R.string.tasks_intro), style = MaterialTheme.typography.titleMedium)
                    Text(AppText.get(R.string.tasks_summary, tasks.tasks.size, tasks.runningCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { help = true }) {
                    Icon(painterResource(R.drawable.ic_info), AppText.get(R.string.ui_help), Modifier.size(22.dp))
                }
            }
        }
        item {
            ManagerActionButton(AppText.get(R.string.tasks_add), { editor = "new" }, Modifier.fillMaxWidth(),
                enabled = tasks.loaded && !manager.busy && tasks.tasks.size < UserTaskRepository.MAX_TASKS)
        }
        tasks.error?.let { error -> item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (!tasks.loaded) TextButton(onClick = { loadAttempt++ }) { Text(AppText.get(R.string.ui_retry)) }
                }
            }
        } }
        if (!tasks.loaded && tasks.error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (tasks.loaded && tasks.tasks.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ManagerGlyph(R.drawable.ic_tasks)
                    Text(AppText.get(R.string.tasks_empty), style = MaterialTheme.typography.titleMedium)
                    Text(AppText.get(R.string.tasks_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(AppText.get(R.string.tasks_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(tasks.tasks, key = { it.definition.id }) { task ->
            UserTaskCard(task, now, !manager.busy && !tasks.busy,
                onCommand = { command(it, task.definition.id) }, onLogs = { logs = task.definition.id },
                onEdit = { editor = task.definition.id }, onDelete = { deletion = task.definition })
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(AppText.get(R.string.tasks_title)) },
        text = { Text(AppText.get(R.string.tasks_help)) }, confirmButton = {
            TextButton(onClick = { help = false }) { Text(AppText.get(R.string.ui_close)) }
        })
    editor?.let { id ->
        val definition = tasks.tasks.firstOrNull { it.definition.id == id }?.definition
        UserTaskEditor(definition, onDismiss = { editor = null })
    }
    deletion?.let { task -> AlertDialog(onDismissRequest = { deletion = null },
        title = { Text(AppText.get(R.string.tasks_delete_title)) }, text = { Text(AppText.get(R.string.tasks_delete_hint, task.name)) },
        confirmButton = { TextButton(onClick = { deletion = null; command("task-delete", task.id) }, enabled = !manager.busy) {
            Text(AppText.get(R.string.ui_delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
    logs?.let { id -> UserTaskLogs(id, tasks.tasks.firstOrNull { it.definition.id == id }?.definition?.name.orEmpty(),
        onDismiss = { logs = null }) }
}

@Composable private fun UserTaskCard(task: UserTaskState, now: Long, enabled: Boolean,
    onCommand: (String) -> Unit, onLogs: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val phase = when (task.phase) {
        UserTaskPhase.STOPPED -> R.string.tasks_stopped
        UserTaskPhase.STARTING -> R.string.tasks_starting
        UserTaskPhase.RUNNING -> R.string.tasks_running
        UserTaskPhase.STOPPING -> R.string.tasks_stopping
        UserTaskPhase.EXITED -> R.string.tasks_exited
        UserTaskPhase.FAILED -> R.string.tasks_failed
        UserTaskPhase.INTERRUPTED -> R.string.tasks_interrupted
    }
    val color = when (task.phase) {
        UserTaskPhase.RUNNING -> MaterialTheme.colorScheme.primary
        UserTaskPhase.FAILED, UserTaskPhase.INTERRUPTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerGlyph(R.drawable.ic_terminal)
                Column(Modifier.weight(1f)) {
                    Text(task.definition.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(AppText.get(phase), style = MaterialTheme.typography.labelSmall, color = color)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more), AppText.get(R.string.tasks_more, task.definition.name), Modifier.size(22.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(AppText.get(R.string.tasks_edit)) }, enabled = enabled && !task.phase.active,
                            onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.ui_delete)) }, enabled = enabled,
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(task.definition.directory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            task.startedAt?.let { start ->
                val seconds = (((if (task.phase.active) now else task.finishedAt ?: start) - start) / 1000).coerceAtLeast(0)
                val duration = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                Text(AppText.get(R.string.tasks_duration, duration) + (task.exitCode?.let { " · ${AppText.get(R.string.tasks_exit_code, it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val detail = task.detail ?: if (task.phase == UserTaskPhase.INTERRUPTED) AppText.get(R.string.tasks_interrupted_hint) else null
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(AppText.get(if (task.phase.active) R.string.ui_stop else R.string.ui_start),
                    { onCommand(if (task.phase.active) "task-stop" else "task-start") }, Modifier.weight(1f),
                    if (task.phase.active) ActionTone.STOP else ActionTone.START, enabled && task.phase != UserTaskPhase.STOPPING)
                ManagerActionButton(AppText.get(R.string.ui_restart), { onCommand("task-restart") }, Modifier.weight(1f),
                    ActionTone.RESTART, enabled && task.phase == UserTaskPhase.RUNNING)
                ManagerActionButton(AppText.get(R.string.home_logs), onLogs, Modifier.weight(1f), ActionTone.NEUTRAL)
            }
        }
    }
}

@Composable private fun UserTaskEditor(task: UserTaskDefinition?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tasks by UserTaskStore.state.collectAsStateWithLifecycle()
    val manager by RuntimeStore.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(task?.name.orEmpty()) }
    var directory by remember { mutableStateOf(task?.directory ?: "/persistent") }
    var command by remember { mutableStateOf(task?.command.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manager.completedOperation, manager.pendingRequests, pending) {
        val request = pending ?: return@LaunchedEffect
        if (manager.completedOperation == request) {
            pending = null
            if (tasks.error == null) onDismiss() else error = tasks.error
        }
    }
    AlertDialog(onDismissRequest = { if (pending == null) onDismiss() },
        title = { Text(AppText.get(if (task == null) R.string.tasks_add else R.string.tasks_edit)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.tasks_name)) },
                    singleLine = true, enabled = pending == null)
                OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.tasks_directory)) },
                    singleLine = true, enabled = pending == null)
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text(AppText.get(R.string.tasks_command)) },
                    minLines = 4, maxLines = 7, enabled = pending == null, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text(AppText.get(R.string.tasks_command_hint), style = MaterialTheme.typography.bodySmall)
                Text(AppText.get(R.string.tasks_save_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (pending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = pending == null && !manager.busy, onClick = {
                val definition = UserTaskDefinition(task?.id ?: UserTaskRepository.newId(), name.trim(), directory.trim(), command)
                val failure = runCatching { UserTaskRepository.validate(definition) }.exceptionOrNull()
                if (failure != null) error = failure.message
                else {
                    error = null
                    pending = RuntimeService.command(context, "task-save", taskId = task?.id, taskName = definition.name,
                        taskDirectory = definition.directory, taskCommand = definition.command)
                }
            }) { Text(AppText.get(R.string.ui_save)) }
        }, dismissButton = {
            TextButton(onClick = onDismiss, enabled = pending == null) { Text(AppText.get(R.string.ui_cancel)) }
        })
}

@Composable private fun UserTaskLogs(id: String, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager by RuntimeStore.state.collectAsStateWithLifecycle()
    val repository = remember { UserTaskRepository(context) }
    val lifecycle = LocalLifecycleOwner.current
    val passwords by UserTaskStore.initialPasswords.collectAsStateWithLifecycle()
    val password = passwords[id]
    var showPassword by remember(id, password) { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, id, password) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) showPassword = false }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                output = withContext(Dispatchers.IO) { runCatching { repository.tail(id) }.getOrDefault("") }
                    .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
                delay(1000)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(AppText.get(R.string.tasks_logs), style = MaterialTheme.typography.titleLarge)
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) }
                }
                Text(AppText.get(R.string.tasks_log_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (password != null) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppText.get(R.string.tasks_initial_password), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(AppText.get(if (showPassword) R.string.tasks_hide_password else R.string.tasks_show_password))
                            }
                            TextButton(onClick = {
                                val label = AppText.get(R.string.tasks_initial_password)
                                val clip = ClipData.newPlainText(label, password)
                                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                Toast.makeText(context, AppText.get(R.string.ui_copied, label), Toast.LENGTH_SHORT).show()
                            }) { Text(AppText.get(R.string.ui_copy_password)) }
                        }
                        if (showPassword) Text(password, Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManagerActionButton(AppText.get(R.string.ui_copy), {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(name, output))
                    }, Modifier.weight(1f), enabled = output.isNotEmpty())
                    ManagerActionButton(AppText.get(R.string.tasks_clear_log), { confirmClear = true }, Modifier.weight(1f),
                        ActionTone.NEUTRAL, enabled = !manager.busy)
                }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(output.ifEmpty { AppText.get(R.string.tasks_no_logs) }, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(AppText.get(R.string.tasks_clear_log)) },
        text = { Text(AppText.get(R.string.tasks_clear_log_confirm)) }, confirmButton = {
            TextButton(onClick = { RuntimeService.command(context, "task-clear-log", taskId = id); confirmClear = false; output = "" }, enabled = !manager.busy) {
                Text(AppText.get(R.string.tasks_clear_log))
            }
        }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(AppText.get(R.string.ui_cancel)) } })
}
