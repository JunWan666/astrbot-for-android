package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class DesktopActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { ManagerTheme { DesktopScreen() } }
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@DesktopActivity) } }
    }

    @Composable private fun DesktopScreen() {
        val state by DesktopStore.state.collectAsStateWithLifecycle()
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableIntStateOf(0) }
        var confirmation by remember { mutableStateOf<String?>(null) }
        var password by remember { mutableStateOf<String?>(null) }
        var logs by remember { mutableStateOf("") }
        var paused by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val owner = LocalLifecycleOwner.current
        val repository = remember { DesktopRepository(this) }
        val enabled = !manager.busy && !state.busy && !state.paused
        fun command(action: String) { RuntimeService.command(this, "desktop-$action") }
        fun copy(label: String, value: String, sensitive: Boolean = false) {
            val clip = ClipData.newPlainText(label, value)
            if (sensitive && android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(this, AppText.get(R.string.ui_copied, label), Toast.LENGTH_SHORT).show()
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) scope.launch {
                val content = logs
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) }
                } }
                if (result.isFailure) Toast.makeText(this@DesktopActivity, AppText.get(R.string.cpolar_export_error), Toast.LENGTH_LONG).show()
            }
        }
        DisposableEffect(owner, page) {
            password = null
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) password = null }
            owner.lifecycle.addObserver(observer)
            onDispose { password = null; owner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(page, paused, owner) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { while (page == 3 && !paused) {
                logs = withContext(Dispatchers.IO) { runCatching { repository.diagnosticTail() }.getOrDefault("") }
                delay(1000)
            } }
        }
        BackHandler(page != 0) { page = 0 }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (page != 0) page = 0 else finish() }) {
                    Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(22.dp))
                }
                Text(AppText.get(R.string.desktop_name), style = MaterialTheme.typography.titleLarge)
            }
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.desktop_tab_desktop, R.string.desktop_tab_qq, R.string.desktop_tab_access, R.string.cpolar_logs).forEachIndexed { index, id ->
                    ManagerActionButton(AppText.get(id), { page = index }, Modifier.weight(1f), if (page == index) ActionTone.PRIMARY else ActionTone.NEUTRAL)
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == 3) ManagerLogViewer(logs, paused, { paused = !paused }, {
                    copy(AppText.get(R.string.desktop_name), logs)
                }, { export.launch("ubuntu-desktop.log") }, Modifier.weight(1f))
                else {
                    ManagerSection(AppText.get(if (page == 1) R.string.desktop_qq_name else R.string.desktop_name)) {
                        Text(AppText.get(when {
                            state.busy -> R.string.cpolar_working
                            state.paused -> R.string.ui_paused_for_maintenance
                            !state.configuration.installed -> R.string.ui_not_installed
                            page == 1 && state.configuration.qqUpdateAvailable -> R.string.desktop_qq_update_available
                            page == 1 && !state.configuration.qqInstalled -> R.string.ui_not_installed
                            page == 1 && state.qqRunning -> R.string.ui_running
                            page == 1 -> R.string.ui_stopped
                            state.running && state.webUiReady -> R.string.desktop_ready
                            state.running -> R.string.cpolar_connecting
                            else -> R.string.ui_stopped
                        }), style = MaterialTheme.typography.titleMedium)
                        if (manager.busy || state.busy) {
                            Text(manager.phase, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        (state.error ?: manager.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text(AppText.get(when (page) { 1 -> R.string.desktop_qq_description; 2 -> R.string.desktop_access_description; else -> R.string.desktop_description }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (page) {
                        0 -> {
                            ManagerActionButton(AppText.get(R.string.desktop_open), {
                                startActivity(Intent(this@DesktopActivity, DesktopWebUiActivity::class.java))
                            }, Modifier.fillMaxWidth(), ActionTone.PRIMARY, enabled && state.running)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(if (!state.configuration.installed) R.string.desktop_install else if (state.running) R.string.desktop_stop else R.string.desktop_start), {
                                    when { !state.configuration.installed -> confirmation = "install"; state.running -> command("stop"); else -> command("start") }
                                }, Modifier.weight(1f), if (state.running) ActionTone.STOP else ActionTone.START, enabled && manager.environmentInstalled)
                                ManagerActionButton(AppText.get(R.string.cpolar_uninstall), { confirmation = "uninstall" }, Modifier.weight(1f), ActionTone.STOP,
                                    enabled && state.configuration.installed)
                            }
                            if (!manager.environmentInstalled) Text(AppText.get(R.string.cpolar_initialize_first), style = MaterialTheme.typography.bodySmall)
                            ManagerSection(AppText.get(R.string.desktop_scope_title)) {
                                Text(AppText.get(R.string.desktop_scope), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        1 -> {
                            Text(AppText.get(R.string.desktop_qq_version, repository.qqVersion), style = MaterialTheme.typography.bodySmall)
                            ManagerActionButton(AppText.get(when {
                                state.qqRunning -> R.string.desktop_qq_stop
                                state.configuration.qqUpdateAvailable -> R.string.desktop_qq_update
                                !state.configuration.qqInstalled -> R.string.desktop_qq_install
                                else -> R.string.desktop_qq_start
                            }), {
                                if (state.qqRunning) command("qq-stop") else confirmation = if (state.configuration.qqInstalled) "qq-start" else "qq-install"
                            }, Modifier.fillMaxWidth(), if (state.qqRunning) ActionTone.STOP else ActionTone.START, enabled && state.configuration.installed)
                            ManagerActionButton(AppText.get(R.string.desktop_open), {
                                startActivity(Intent(this@DesktopActivity, DesktopWebUiActivity::class.java))
                            }, Modifier.fillMaxWidth(), ActionTone.PRIMARY, enabled && state.webUiReady)
                            Text(AppText.get(R.string.desktop_qq_isolation), style = MaterialTheme.typography.bodySmall)
                        }
                        2 -> {
                            ManagerSection(AppText.get(R.string.desktop_password)) {
                                Text(password ?: "••••••••", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ManagerActionButton(AppText.get(if (password == null) R.string.desktop_reveal else R.string.desktop_hide), {
                                        password = if (password != null) null else runCatching { repository.password() }.getOrNull()
                                    }, Modifier.weight(1f), ActionTone.NEUTRAL, enabled && state.configuration.installed)
                                    ManagerActionButton(AppText.get(R.string.desktop_copy_password), {
                                        runCatching { repository.password() }.getOrNull()?.let { copy(AppText.get(R.string.desktop_password), it, true) }
                                    }, Modifier.weight(1f), ActionTone.NEUTRAL, enabled && state.configuration.installed)
                                }
                            }
                            ManagerActionButton(AppText.get(R.string.desktop_copy_environment), {
                                copy("DISPLAY", DesktopRepository.TERMINAL_ENVIRONMENT)
                            }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, state.configuration.installed)
                            ManagerActionButton(AppText.get(R.string.terminal_name), {
                                startActivity(Intent(this@DesktopActivity, TerminalActivity::class.java))
                            }, Modifier.fillMaxWidth(), ActionTone.PRIMARY, enabled && manager.environmentInstalled)
                            Text(AppText.get(R.string.desktop_cli_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        confirmation?.let { action ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(AppText.get(R.string.desktop_name)) },
                text = { Text(AppText.get(when (action) {
                    "install" -> R.string.desktop_install_notice
                    "qq-install" -> if (state.configuration.qqUpdateAvailable) R.string.desktop_qq_update_notice else R.string.desktop_qq_install_notice
                    "qq-start" -> R.string.desktop_qq_start_notice
                    else -> R.string.desktop_uninstall_notice
                })) }, confirmButton = { TextButton(onClick = { confirmation = null; command(action) }) { Text(AppText.get(R.string.cpolar_confirm)) } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text(AppText.get(R.string.action_cancel)) } })
        }
    }
}
