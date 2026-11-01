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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

private enum class QqPage(val title: Int) {
    HOME(R.string.qq_title), LAN(R.string.qq_lan), LAN_SETTINGS(R.string.qq_lan_settings),
    LOGS(R.string.qq_logs), MORE(R.string.qq_settings)
}

class DesktopActivity : LocalizedActivity() {
    private var installationChecked by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { ManagerTheme { QqScreen() } }
    }
    override fun onStart() {
        super.onStart()
        installationChecked = false
        lifecycleScope.launch {
            RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@DesktopActivity) }
            installationChecked = true
        }
    }

    @Composable private fun QqScreen() {
        val state by DesktopStore.state.collectAsStateWithLifecycle()
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        val transfer by DesktopInstallStore.state.collectAsStateWithLifecycle()
        val repository = remember { DesktopRepository(this) }
        val owner = LocalLifecycleOwner.current
        val lifecycleState by owner.lifecycle.currentStateAsState()
        val scope = rememberCoroutineScope()
        var page by rememberSaveable { mutableStateOf(QqPage.HOME) }
        var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
        var entryPending by rememberSaveable { mutableStateOf(intent.getBooleanExtra(EXTRA_OPEN_QQ, false)) }
        var pendingInstallOpen by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingOpen by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
        var password by remember { mutableStateOf<String?>(null) }
        var logs by remember { mutableStateOf("") }
        var paused by rememberSaveable { mutableStateOf(false) }
        var addresses by remember { mutableStateOf(emptyList<String>()) }
        var editLan by rememberSaveable { mutableStateOf(false) }
        var editPort by rememberSaveable { mutableStateOf("6082") }
        var errorDetail by remember { mutableStateOf<String?>(null) }
        val enabled = !manager.busy && !state.busy && !state.paused
        fun command(action: String) = RuntimeService.command(this, "desktop-$action")
        fun viewer() {
            startActivity(Intent(this, DesktopWebUiActivity::class.java))
            // Return directly to the caller's current guide step when leaving the viewer.
            if (intent.getBooleanExtra(EXTRA_OPEN_QQ, false)) finish()
        }
        fun back() { page = when (page) { QqPage.HOME -> { finish(); QqPage.HOME }; QqPage.LAN_SETTINGS -> QqPage.LAN; else -> QqPage.HOME } }
        fun settings() { editLan = state.lanEnabled; editPort = state.lanPort.toString(); page = QqPage.LAN_SETTINGS }
        fun copy(label: String, value: String, sensitive: Boolean = false) {
            val clip = ClipData.newPlainText(label, value)
            if (sensitive && android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(this, AppText.get(R.string.ui_copied, label), Toast.LENGTH_SHORT).show()
        }
        fun openQq() {
            if (state.qqRunning && state.webUiReady) viewer()
            else if (manager.services.getValue(ServiceId.NAPCAT).desiredRunning) confirmation = "qq-open"
            else pendingOpen = command("qq-open")
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                val content = logs
                if (runCatching { requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) } }.isFailure)
                    withContext(Dispatchers.Main) { Toast.makeText(this@DesktopActivity, AppText.get(R.string.cpolar_export_error), Toast.LENGTH_LONG).show() }
            }
        }
        BackHandler { back() }
        LaunchedEffect(entryPending, installationChecked, enabled, lifecycleState) {
            if (entryPending && installationChecked && enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                entryPending = false
                if (!manager.environmentInstalled) confirmation = "qq-initialize"
                else if (!state.configuration.qqInstalled) confirmation = "qq-install-open"
                else openQq()
            }
        }
        DisposableEffect(owner, page) {
            password = null
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) password = null }
            owner.lifecycle.addObserver(observer)
            onDispose { password = null; owner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(manager.completedOperation, manager.busy, lifecycleState, pendingOpen, pendingSave, pendingInstallOpen) {
            if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (pendingInstallOpen != null && manager.completedOperation == pendingInstallOpen && !manager.busy) {
                pendingInstallOpen = null
                if (state.configuration.qqInstalled && state.error == null && manager.error == null) openQq()
            }
            if (pendingOpen != null && manager.completedOperation == pendingOpen) {
                pendingOpen = null
                if (state.qqRunning && state.webUiReady) viewer()
            }
            if (pendingSave != null && manager.completedOperation == pendingSave) {
                pendingSave = null
                if (state.error == null && manager.error == null) page = QqPage.LAN
            }
        }
        LaunchedEffect(owner, page, paused) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive && page in setOf(QqPage.LAN, QqPage.LAN_SETTINGS, QqPage.LOGS)) {
                    withContext(Dispatchers.IO) {
                        if (page == QqPage.LOGS && !paused) logs = repository.diagnosticTail()
                        if (page != QqPage.LOGS) addresses = LanAccess.addresses(this@DesktopActivity)
                    }
                    delay(if (page == QqPage.LOGS) 1000 else 5000)
                }
            }
        }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(22.dp)) }
                Text(AppText.get(page.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (page == QqPage.HOME || page == QqPage.LAN) IconButton(onClick = { if (page == QqPage.LAN) settings() else page = QqPage.MORE }) {
                    Icon(painterResource(R.drawable.ic_settings), AppText.get(if (page == QqPage.LAN) R.string.qq_lan_settings else R.string.qq_settings), Modifier.size(22.dp))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp)
                .then(if (page != QqPage.LOGS) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    QqPage.HOME -> {
                        if (transfer.kind != null && (transfer.active || transfer.stage in setOf(DesktopInstallStage.STOPPED, DesktopInstallStage.FAILED))) {
                            DesktopDownloadPanel(transfer, canRetry = enabled, onControl = { action -> scope.launch(Dispatchers.IO) {
                                runCatching { when (action) { "pause" -> DesktopInstallStore.pause(); "resume" -> DesktopInstallStore.resume(); else -> DesktopInstallStore.stop() } }
                                    .onFailure { withContext(Dispatchers.Main) { Toast.makeText(this@DesktopActivity, AppText.get(R.string.desktop_transfer_control_failed), Toast.LENGTH_LONG).show() } }
                            } }, onRetry = {
                                val request = command(if (transfer.kind == "qq") "qq-install" else "install")
                                if (transfer.kind == "qq" && intent.getBooleanExtra(EXTRA_OPEN_QQ, false)) pendingInstallOpen = request
                            })
                        }
                        ManagerSection(AppText.get(R.string.qq_runtime)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ManagerGlyph(R.drawable.ic_link)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(AppText.get(R.string.qq_title), style = MaterialTheme.typography.titleMedium)
                                    Text(AppText.get(when {
                                        state.paused -> R.string.ui_paused_for_maintenance
                                        state.qqStarting -> R.string.qq_starting
                                        state.qqRunning -> R.string.qq_started
                                        state.configuration.qqInstalled || state.configuration.qqUpdateAvailable -> R.string.ui_stopped
                                        else -> R.string.ui_not_installed
                                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(AppText.get(R.string.qq_version, repository.qqVersion), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.busy || pendingOpen != null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(manager.phase, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!state.configuration.qqInstalled && !state.configuration.qqUpdateAvailable) {
                                ManagerActionButton(AppText.get(R.string.qq_install), {
                                    confirmation = if (intent.getBooleanExtra(EXTRA_OPEN_QQ, false)) "qq-install-open" else "qq-install"
                                }, Modifier.fillMaxWidth(), enabled = enabled && manager.environmentInstalled)
                                Text(AppText.get(R.string.qq_download_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                ManagerActionButton(AppText.get(if (state.configuration.qqUpdateAvailable) R.string.desktop_qq_update else R.string.qq_open), {
                                    if (state.configuration.qqUpdateAvailable) confirmation = if (intent.getBooleanExtra(EXTRA_OPEN_QQ, false)) "qq-install-open" else "qq-install"
                                    else openQq()
                                }, Modifier.fillMaxWidth(), enabled = enabled && manager.environmentInstalled)
                                ManagerActionButton(AppText.get(R.string.qq_stop), { command("qq-stop") }, Modifier.fillMaxWidth(), ActionTone.STOP, enabled && (state.qqRunning || state.qqStarting))
                            }
                            Text(AppText.get(R.string.qq_open_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!manager.environmentInstalled) Text(AppText.get(R.string.cpolar_initialize_first), style = MaterialTheme.typography.bodySmall)
                        (state.error ?: manager.error)?.let { message -> ManagerNotice(AppText.get(R.string.qq_operation_error), message.take(160), true) { errorDetail = message } }
                        ManagerSection(AppText.get(R.string.qq_connections), padded = false) {
                            ManagerSettingsRow(AppText.get(R.string.qq_lan), R.drawable.ic_link, AppText.get(R.string.qq_lan_subtitle),
                                AppText.get(if (state.lanReady) R.string.qq_lan_ready else if (state.lanEnabled) R.string.qq_lan_allowed else R.string.qq_lan_off)) { page = QqPage.LAN }
                            ManagerSettingsRow(AppText.get(R.string.qq_logs), R.drawable.ic_logs, divider = false) { page = QqPage.LOGS }
                        }
                    }
                    QqPage.LAN -> {
                        ManagerSection(AppText.get(R.string.qq_lan)) {
                            Text(AppText.get(if (state.lanReady) R.string.qq_lan_ready else if (!state.lanEnabled) R.string.qq_lan_off else if (!state.running) R.string.qq_lan_desktop_stopped else R.string.qq_lan_unavailable), style = MaterialTheme.typography.titleMedium)
                            Text(AppText.get(R.string.qq_lan_same_session), style = MaterialTheme.typography.bodyMedium)
                            if (state.lanReady) {
                                if (addresses.isEmpty()) Text(AppText.get(R.string.qq_no_lan), style = MaterialTheme.typography.bodySmall)
                                addresses.forEach { address ->
                                    val url = "http://$address:${state.lanPort}/vnc.html?resize=scale&autoconnect=true"
                                    Text("http://$address:${state.lanPort}", style = MaterialTheme.typography.bodyMedium)
                                    ManagerActionButton(AppText.get(R.string.qq_copy_address), { copy(AppText.get(R.string.qq_lan), url) }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                                }
                            }
                            ManagerActionButton(AppText.get(R.string.qq_lan_settings), { settings() }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, enabled)
                        }
                        if (state.lanEnabled) ManagerSection(AppText.get(R.string.desktop_password)) {
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
                        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    QqPage.LAN_SETTINGS -> {
                        ManagerSection(AppText.get(R.string.qq_lan_settings)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(AppText.get(R.string.qq_allow_lan), style = MaterialTheme.typography.bodyMedium)
                                    Text(AppText.get(R.string.qq_lan_default_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(editLan, { editLan = it }, enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = AppText.get(R.string.qq_allow_lan) })
                            }
                            OutlinedTextField(editPort, { editPort = it.filter(Char::isDigit).take(5) }, label = { Text(AppText.get(R.string.qq_lan_port)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Text(AppText.get(R.string.qq_lan_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val valid = editPort.toIntOrNull()?.let { it in 1024..65535 && it !in listOf(5920, 6081) } == true
                        if (!valid) Text(AppText.get(R.string.qq_invalid_port), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        ManagerActionButton(AppText.get(R.string.qq_save), {
                            pendingSave = RuntimeService.command(this@DesktopActivity, "desktop-lan-save", desktopLan = editLan, desktopLanPort = editPort.toInt())
                        }, Modifier.fillMaxWidth(), enabled = enabled && valid && manager.environmentInstalled)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    QqPage.LOGS -> ManagerLogViewer(logs, paused, { paused = !paused }, { copy(AppText.get(R.string.qq_logs), logs) }, { export.launch("linux-qq.log") }, Modifier.weight(1f))
                    QqPage.MORE -> {
                        ManagerSection(AppText.get(R.string.qq_management), padded = false) {
                            ManagerSettingsRow(AppText.get(R.string.desktop_qq_update), R.drawable.ic_restart, enabled = enabled && state.configuration.qqUpdateAvailable) { confirmation = "qq-install" }
                            ManagerSettingsRow(AppText.get(R.string.qq_uninstall), R.drawable.ic_settings, AppText.get(R.string.qq_uninstall_summary), enabled = enabled && (state.configuration.qqInstalled || state.configuration.qqUpdateAvailable), divider = false) { confirmation = "qq-uninstall" }
                        }
                        ManagerSection(AppText.get(R.string.qq_desktop_advanced), padded = false) {
                            ManagerSettingsRow(AppText.get(R.string.desktop_open), R.drawable.ic_terminal, AppText.get(R.string.qq_desktop_only), enabled = enabled && manager.environmentInstalled) { command("open"); viewer() }
                            ManagerSettingsRow(AppText.get(R.string.desktop_stop), R.drawable.ic_settings, AppText.get(R.string.qq_stop_desktop_summary), enabled = enabled && state.running) { confirmation = "stop" }
                            ManagerSettingsRow(AppText.get(R.string.qq_repair), R.drawable.ic_settings, enabled = enabled && manager.environmentInstalled) { confirmation = "install" }
                            ManagerSettingsRow(AppText.get(R.string.desktop_copy_environment), R.drawable.ic_terminal, enabled = state.configuration.installed, divider = false) { copy("DISPLAY", DesktopRepository.TERMINAL_ENVIRONMENT) }
                        }
                        Text(AppText.get(R.string.desktop_qq_isolation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(AppText.get(R.string.qq_title)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(when (action) {
                "qq-install", "qq-install-open" -> if (state.configuration.qqUpdateAvailable) R.string.desktop_qq_update_notice else R.string.desktop_qq_install_notice
                "qq-initialize" -> R.string.cpolar_initialize_first
                "qq-open" -> R.string.desktop_qq_start_notice
                "qq-uninstall" -> R.string.qq_uninstall_notice
                "stop" -> R.string.qq_stop_desktop_hint
                else -> R.string.qq_repair_notice
            }))
            if (action == "qq-install-open") Text(AppText.get(R.string.qq_install_open_hint))
        } }, confirmButton = { TextButton(onClick = {
            confirmation = null
            when (action) {
                "qq-install-open" -> pendingInstallOpen = command("qq-install")
                "qq-open" -> pendingOpen = command(action)
                "qq-initialize" -> {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                else -> command(action)
            }
        }) { Text(AppText.get(when (action) {
            "qq-install-open" -> if (state.configuration.qqUpdateAvailable) R.string.qq_update_open else R.string.qq_install_open
            "qq-initialize" -> R.string.qq_initialize
            else -> R.string.ui_ok
        })) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
        errorDetail?.let { message -> AlertDialog(onDismissRequest = { errorDetail = null }, title = { Text(AppText.get(R.string.qq_operation_error)) },
            text = { Text(message, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { errorDetail = null }) { Text(AppText.get(R.string.ui_ok)) } }) }
    }

    companion object { const val EXTRA_OPEN_QQ = "openQq" }
}
