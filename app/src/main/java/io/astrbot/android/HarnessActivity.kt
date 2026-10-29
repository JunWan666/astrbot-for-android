package io.astrbot.android

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.widget.Toast
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class HarnessActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        lifecycleScope.launch { RuntimeStore.operationLock.withLock { withContext(Dispatchers.IO) { HarnessRepository(this@HarnessActivity).refresh() } } }
        setContent { ManagerTheme {
            val state by HarnessStore.state.collectAsStateWithLifecycle()
            val manager by RuntimeStore.state.collectAsStateWithLifecycle()
            var uninstall by remember { mutableStateOf(false) }
            var logs by remember { mutableStateOf<String?>(null) }
            var lanConfirm by remember { mutableStateOf(false) }
            var addresses by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(Unit) { while (isActive) {
                addresses = withContext(Dispatchers.IO) { LanAccess.addresses(this@HarnessActivity) }; delay(5000)
            } }
            fun command(action: String) { RuntimeService.command(this@HarnessActivity,"harness-$action") }
            Scaffold(topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_back),AppText.get(R.string.ui_back),Modifier.size(22.dp)) }
                    Text(AppText.get(R.string.harness_name),style = MaterialTheme.typography.titleLarge)
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ManagerSection(AppText.get(R.string.harness_name)) {
                        Text(when {
                            state.busy || manager.busy -> manager.phase
                            state.ready -> AppText.get(R.string.ui_running)
                            state.active -> AppText.get(R.string.harness_starting)
                            state.installed -> AppText.get(R.string.ui_stopped)
                            else -> AppText.get(R.string.ui_not_installed)
                        },style = MaterialTheme.typography.titleMedium)
                        state.version?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
                        Text(AppText.get(R.string.harness_intro),style = MaterialTheme.typography.bodySmall)
                        if (state.busy) HarnessInstallationProgress(state)
                        if (!state.installed) ManagerActionButton(AppText.get(R.string.harness_install), { command("install") },Modifier.fillMaxWidth(),enabled = manager.environmentInstalled && !manager.busy)
                        else {
                            ManagerActionButton(AppText.get(R.string.harness_open), {
                                startActivity(Intent(this@HarnessActivity,WebsiteBrowserActivity::class.java).putExtra("harness",true))
                            },Modifier.fillMaxWidth(),enabled = state.ready)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(if (state.active) R.string.ui_stop else R.string.ui_start), { command(if (state.active) "stop" else "start") },
                                    Modifier.weight(1f),if (state.active) ActionTone.STOP else ActionTone.START,!manager.busy)
                                ManagerActionButton(AppText.get(R.string.harness_remove), { uninstall = true },Modifier.weight(1f),ActionTone.NEUTRAL,!manager.busy)
                            }
                        }
                        state.error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
                    }
                    ManagerSection(AppText.get(R.string.harness_network_title)) {
                        Text(if (state.active) "http://${state.host}:3080" else HarnessEngine.WEB_UI_URL, style = MaterialTheme.typography.bodyMedium)
                        Text(if (addresses.isEmpty()) AppText.get(R.string.harness_no_lan)
                            else AppText.get(R.string.harness_wifi_ip, addresses.joinToString()), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(AppText.get(R.string.harness_lan), style = MaterialTheme.typography.bodyMedium)
                                Text(AppText.get(if (state.lan) R.string.harness_lan_detail else R.string.harness_local_only), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = state.lan, onCheckedChange = { if (it) lanConfirm = true else command("lan-disable") },
                                enabled = !manager.busy && (!addresses.isEmpty() || state.lan))
                        }
                        OutlinedButton(onClick = {
                            HarnessStore.entryUrl?.let { link ->
                                val clip = ClipData.newPlainText("Harness", link)
                                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                Toast.makeText(this@HarnessActivity, AppText.get(R.string.harness_link_copied), Toast.LENGTH_LONG).show()
                            }
                        }, enabled = state.ready, modifier = Modifier.fillMaxWidth()) { Text(AppText.get(R.string.harness_copy_link)) }
                    }
                    Text(AppText.get(R.string.harness_workspace),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManagerActionButton(AppText.get(R.string.ui_logs_894b8), {
                        lifecycleScope.launch { logs = withContext(Dispatchers.IO) { RuntimeStore.log(this@HarnessActivity,LogCategory.HARNESS).tail(100) } }
                    },Modifier.fillMaxWidth(),ActionTone.NEUTRAL)
                }
            }
            if (uninstall) AlertDialog(onDismissRequest = { uninstall = false },title = { Text(AppText.get(R.string.harness_remove)) },
                text = { Text(AppText.get(R.string.harness_uninstall_notice)) },confirmButton = { TextButton(onClick = { uninstall = false; command("uninstall") }) { Text(AppText.get(R.string.ui_ok)) } },
                dismissButton = { TextButton(onClick = { uninstall = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            if (lanConfirm) AlertDialog(onDismissRequest = { lanConfirm = false }, title = { Text(AppText.get(R.string.harness_lan)) },
                text = { Text(AppText.get(R.string.harness_lan_confirm)) }, confirmButton = { TextButton(onClick = { lanConfirm = false; command("lan-enable") }) { Text(AppText.get(R.string.ui_ok)) } },
                dismissButton = { TextButton(onClick = { lanConfirm = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            logs?.let { text -> AlertDialog(onDismissRequest = { logs = null },title = { Text(AppText.get(R.string.ui_logs_894b8)) },
                text = { Text(text,Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { logs = null }) { Text(AppText.get(R.string.ui_ok)) } }) }
        } }
    }
}

@Composable internal fun HarnessInstallationProgress(state: HarnessState) {
    val progress = state.installation
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(progress?.receivedAt) { while (isActive) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    fun duration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
    if (progress == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val added = if (progress.stage == "finished") 0 else ((now - progress.receivedAt) / 1000).coerceAtLeast(0)
    Text(AppText.get(R.string.harness_step, progress.step, duration(progress.elapsed + added)), style = MaterialTheme.typography.bodyMedium)
    val fraction = when {
        progress.stage == "download" && state.total > 0 -> state.downloaded.toFloat() / state.total
        progress.stage == "extract" && progress.entries > 0 -> progress.completed.toFloat() / progress.entries
        else -> null
    }
    if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    if (progress.stage == "download" && state.total > 0) Text("%.1f / %.1f MiB".format(state.downloaded / 1048576.0, state.total / 1048576.0), style = MaterialTheme.typography.bodySmall)
    if (progress.stage == "extract" && progress.entries > 0) Text(AppText.get(R.string.harness_extract_entries, progress.completed, progress.entries), style = MaterialTheme.typography.bodySmall)
    if (progress.stage in setOf("system", "packages", "verify_program")) {
        val quiet = progress.quietSeconds + added
        Text(if (progress.activity == "fetch" && quiet < 10) AppText.get(R.string.harness_activity_fetch, progress.fetched)
            else AppText.get(when {
                quiet >= 10 -> R.string.harness_activity_waiting
                progress.activity == "compile" -> R.string.harness_activity_compile
                else -> R.string.harness_activity_working
            }), style = MaterialTheme.typography.bodySmall)
        Text(AppText.get(R.string.harness_stage_time, duration(progress.stageElapsed + added), quiet), style = MaterialTheme.typography.bodySmall)
        Text(AppText.get(R.string.harness_dependency_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
