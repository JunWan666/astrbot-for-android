package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ColumnScope.CpolarDetails() {
    val context = LocalContext.current
    val state by CpolarStore.state.collectAsStateWithLifecycle()
    val manager by RuntimeStore.state.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<String?>(null) }
    var logs by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val enabled = !manager.busy && !state.busy && !state.paused
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val content = text
            val result = withContext(Dispatchers.IO) { runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) }
            } }
            if (result.isFailure) Toast.makeText(context, AppText.get(R.string.cpolar_export_error), Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(owner, logs, paused) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (logs && !paused) {
                text = withContext(Dispatchers.IO) { runCatching { RuntimeStore.log(context, LogCategory.CPOLAR).tail() }.getOrDefault("") }
                delay(1000)
            }
        }
    }
    if (logs) ManagerLogViewer(text, paused, { paused = !paused }, {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cpolar", text))
    }, { export.launch("cpolar.log") }, Modifier.weight(1f))
    else {
        ManagerSection(AppText.get(R.string.cpolar_name)) {
            Text(cpolarStatus(state), style = MaterialTheme.typography.titleMedium)
            Text(AppText.get(R.string.cpolar_description), style = MaterialTheme.typography.bodySmall)
            if (state.configuration.installed) Text("cpolar ${CpolarRepository.VERSION} · ${CpolarEngine.WEB_UI_URL}",
                style = MaterialTheme.typography.bodySmall)
            if (state.busy || state.running && !state.webUiReady && state.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        ManagerActionButton(AppText.get(R.string.cpolar_open_webui), {
            context.startActivity(Intent(context, CpolarWebUiActivity::class.java))
        }, Modifier.fillMaxWidth(), ActionTone.PRIMARY, enabled && state.running)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerActionButton(if (!state.configuration.installed) AppText.get(R.string.cpolar_install)
                else if (state.running) AppText.get(R.string.cpolar_stop) else AppText.get(R.string.cpolar_start), {
                if (!state.configuration.installed || state.running) confirmation = if (state.running) "stop" else "install"
                else RuntimeService.command(context, "cpolar-start")
            }, Modifier.weight(1f), if (state.running) ActionTone.STOP else ActionTone.START,
                enabled && manager.environmentInstalled)
            ManagerActionButton(AppText.get(R.string.cpolar_uninstall), { confirmation = "uninstall" }, Modifier.weight(1f), ActionTone.STOP,
                enabled && state.configuration.installed)
        }
        if (!manager.environmentInstalled) Text(AppText.get(R.string.cpolar_initialize_first), style = MaterialTheme.typography.bodySmall)
        Text(AppText.get(R.string.cpolar_login_hint), style = MaterialTheme.typography.bodyMedium)
        Text(AppText.get(R.string.cpolar_privacy), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ManagerActionButton(AppText.get(R.string.cpolar_overview), { logs = false }, Modifier.weight(1f), if (!logs) ActionTone.PRIMARY else ActionTone.NEUTRAL)
        ManagerActionButton(AppText.get(R.string.cpolar_logs), { logs = true }, Modifier.weight(1f), if (logs) ActionTone.PRIMARY else ActionTone.NEUTRAL)
    }
    confirmation?.let { action ->
        val message = when (action) {
            "install" -> R.string.cpolar_install_notice
            "uninstall" -> R.string.cpolar_uninstall_notice
            else -> R.string.cpolar_stop_notice
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(AppText.get(R.string.cpolar_name)) },
            text = { Text(AppText.get(message)) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                RuntimeService.command(context, "cpolar-$action")
            }) { Text(AppText.get(R.string.cpolar_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(AppText.get(R.string.action_cancel)) } })
    }
}

internal fun cpolarStatus(state: CpolarState): String = AppText.get(when {
    state.busy -> R.string.cpolar_working
    state.paused -> R.string.cpolar_paused
    !state.configuration.installed -> R.string.cpolar_not_installed
    state.running && state.webUiReady -> R.string.cpolar_dashboard_ready
    state.running -> R.string.cpolar_connecting
    else -> R.string.cpolar_stopped
})
