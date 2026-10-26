package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class ExtensionsActivity : LocalizedActivity() {
    private enum class SshPage(private val titleResource: Int) { OVERVIEW(R.string.ui_ssh_terminal), CONNECTION(R.string.ui_connection), PASSWORD(R.string.ui_login_password), HELP(R.string.ui_help_and_uninstall) ;
    val title: String get() = AppText.get(titleResource)
}
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme { ExtensionsScreen() } }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@ExtensionsActivity) } }
    }

    @Composable private fun ExtensionsScreen() {
        val ssh by SshStore.state.collectAsStateWithLifecycle()
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        var detail by rememberSaveable { mutableStateOf(false) }
        var cpolarDetail by rememberSaveable { mutableStateOf(false) }
        val cpolar by CpolarStore.state.collectAsStateWithLifecycle()
        var sshPage by rememberSaveable { mutableStateOf(SshPage.OVERVIEW) }
        val enabled = !manager.busy && !ssh.busy
        val status = when { ssh.paused -> AppText.get(R.string.ui_paused_for_maintenance); ssh.running -> AppText.get(R.string.ui_running); !ssh.configuration.installed -> AppText.get(R.string.ui_not_installed); else -> AppText.get(R.string.ui_stopped) }
        fun back() { if (cpolarDetail) cpolarDetail = false else if (detail && sshPage != SshPage.OVERVIEW) sshPage = SshPage.OVERVIEW else if (detail) detail = false else finish() }
        BackHandler(detail || cpolarDetail) { back() }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) {
                    Icon(painterResource(R.drawable.ic_chevron), AppText.get(R.string.ui_back), Modifier.size(22.dp).rotate(180f))
                }
                Text(if (cpolarDetail) AppText.get(R.string.cpolar_name) else if (detail) sshPage.title else AppText.get(R.string.ui_runtime_extensions), style = MaterialTheme.typography.titleLarge)
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cpolarDetail) CpolarDetails()
                else if (!detail) {
                    Text(AppText.get(R.string.ui_manage_optional_ubuntu_features), Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManagerSection(AppText.get(R.string.ui_available_extensions), padded = false) {
                        ManagerSettingsRow(AppText.get(R.string.ui_ssh_terminal), R.drawable.ic_settings, AppText.get(R.string.ui_remote_ubuntu_access_included_with_the_app), value = status, divider = false) { detail = true }
                        ManagerSettingsRow(AppText.get(R.string.cpolar_name), R.drawable.ic_link, AppText.get(R.string.cpolar_extension_subtitle),
                            value = cpolarStatus(cpolar), divider = false) { cpolarDetail = true }
                    }
                    Text(AppText.get(R.string.ui_manage_runtime_extensions_here_manage_bot_plugins_in_the), Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SshDetails(ssh, enabled, sshPage) { sshPage = it }
                }
            }
        }
    }

    @Composable private fun ColumnScope.SshDetails(state: SshState, enabled: Boolean, page: SshPage, onNavigate: (SshPage) -> Unit) {
        var edit by remember { mutableStateOf(false) }
        var confirmUninstall by remember { mutableStateOf(false) }
        var addresses by remember { mutableStateOf(emptyList<String>()) }
        var revealed by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var request by remember { mutableIntStateOf(0) }
        var showFingerprint by remember { mutableStateOf(false) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { request++; revealed = null; loading = false } }
            owner.lifecycle.addObserver(observer)
            onDispose { request++; revealed = null; owner.lifecycle.removeObserver(observer) }
        }
        DisposableEffect(state.configuration, state.busy, page) {
            request++; revealed = null; loading = false
            onDispose { request++; revealed = null; loading = false }
        }
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { addresses = withContext(Dispatchers.IO) { LanAccess.addresses(this@ExtensionsActivity) }; delay(5000) }
            }
        }
        fun readPassword(consume: (String) -> Unit) {
            val attempt = ++request
            loading = true
            lifecycleScope.launch {
                val value = withContext(Dispatchers.IO) { RuntimeStore.operationLock.withLock {
                    runCatching { SshRepository(this@ExtensionsActivity).password() }.getOrNull()
                } }
                if (attempt == request && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    loading = false
                    if (value != null) consume(value) else Toast.makeText(this@ExtensionsActivity, AppText.get(R.string.ui_unable_to_read_the_password_set_a_new_password), Toast.LENGTH_LONG).show()
                }
            }
        }
        SshPageContent(state, enabled, page, addresses, revealed, loading,
            onNavigate = onNavigate, onTogglePassword = { if (revealed != null) revealed = null else readPassword { revealed = it } },
            onCopyPassword = { readPassword { copy(AppText.get(R.string.ui_ssh_password), it, sensitive = true) } },
            onEdit = { edit = true }, onUninstall = { confirmUninstall = true }, onFingerprint = { showFingerprint = true })
        if (edit) SshEditDialog(state.configuration, onDismiss = { edit = false }) { port, lan, password ->
            revealed = null; edit = false
            RuntimeService.command(this@ExtensionsActivity, "ssh-save", sshPort = port, sshLan = lan, sshPassword = password)
        }
        if (confirmUninstall) AlertDialog(onDismissRequest = { confirmUninstall = false }, title = { Text(AppText.get(R.string.ui_uninstall_the_ssh_extension)) }, text = {
            Text(AppText.get(R.string.ui_ssh_will_stop_and_terminal_sessions_will_disconnect_the))
        }, confirmButton = { TextButton(onClick = { confirmUninstall = false; revealed = null; RuntimeService.command(this@ExtensionsActivity, "ssh-uninstall") }) { Text(AppText.get(R.string.ui_uninstall)) } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        if (showFingerprint) AlertDialog(onDismissRequest = { showFingerprint = false }, title = { Text(AppText.get(R.string.ui_ssh_host_fingerprint)) },
            text = { Text(state.fingerprint ?: AppText.get(R.string.ui_the_host_fingerprint_is_generated_when_ssh_starts_for)) },
            confirmButton = { TextButton(onClick = { showFingerprint = false }) { Text(AppText.get(R.string.ui_ok)) } })
    }

    @Composable private fun ColumnScope.SshPageContent(state: SshState, enabled: Boolean, page: SshPage,
        addresses: List<String>, revealed: String?, loading: Boolean, onNavigate: (SshPage) -> Unit,
        onTogglePassword: () -> Unit, onCopyPassword: () -> Unit, onEdit: () -> Unit, onUninstall: () -> Unit, onFingerprint: () -> Unit) {
        when (page) {
            SshPage.OVERVIEW -> {
                ManagerSection("Ubuntu SSH") {
                    Text(when { state.paused -> AppText.get(R.string.ui_disconnected_during_maintenance); state.running -> AppText.get(R.string.ui_service_running); state.configuration.installed -> AppText.get(R.string.ui_installed_not_started); else -> AppText.get(R.string.ui_not_installed_yet) },
                        style = MaterialTheme.typography.titleMedium)
                    Text(AppText.get(R.string.ui_remote_access_to_ubuntu_controlled_independently_of_astrbot_and), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManagerActionButton(if (!state.configuration.installed) AppText.get(R.string.ui_install_ssh) else if (state.running) AppText.get(R.string.ui_stop_ssh) else AppText.get(R.string.ui_start_ssh), {
                        if (!state.running && android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        RuntimeService.command(this@ExtensionsActivity, if (!state.configuration.installed) "ssh-install" else if (state.running) "ssh-stop" else "ssh-start")
                    }, Modifier.fillMaxWidth(), if (state.running) ActionTone.STOP else ActionTone.START, enabled && !state.paused)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (state.configuration.installed) FixedManagerMenu(listOf(
                    ManagerMenuItem(AppText.get(R.string.ui_connection), R.drawable.ic_link, AppText.get(R.string.ui_username_port_and_connection_command)) { onNavigate(SshPage.CONNECTION) },
                    ManagerMenuItem(AppText.get(R.string.ui_login_password), R.drawable.ic_visibility, AppText.get(R.string.ui_show_hide_and_copy_the_password)) { onNavigate(SshPage.PASSWORD) },
                    ManagerMenuItem(AppText.get(R.string.ui_edit_ssh_settings), R.drawable.ic_settings, AppText.get(R.string.ui_port_lan_access_and_password), enabled) { onEdit() },
                    ManagerMenuItem(AppText.get(R.string.ui_help_and_uninstall), R.drawable.ic_info, AppText.get(R.string.ui_terminal_help_and_extension_management)) { onNavigate(SshPage.HELP) }), Modifier.weight(1f))
            }
            SshPage.CONNECTION -> {
                ManagerSection(AppText.get(R.string.ui_connection)) {
                    Text(AppText.get(R.string.ui_username_root), style = MaterialTheme.typography.bodyMedium)
                    Text(AppText.get(R.string.ui_port, state.configuration.port, if (state.configuration.lanEnabled) AppText.get(R.string.ui_lan_access_enabled) else AppText.get(R.string.ui_this_device_only)), style = MaterialTheme.typography.bodySmall)
                    ManagerActionButton(AppText.get(R.string.ui_view_host_fingerprint), onFingerprint, tone = ActionTone.NEUTRAL)
                }
                val commands = listOf("ssh -p ${state.configuration.port} root@127.0.0.1") +
                    if (state.configuration.lanEnabled) addresses.map { "ssh -p ${state.configuration.port} root@$it" } else emptyList()
                FixedManagerMenu(commands.mapIndexed { index, command -> ManagerMenuItem(if (index == 0) AppText.get(R.string.ui_copy_local_ssh_command) else AppText.get(R.string.ui_copy_lan_ssh_command),
                    R.drawable.ic_document, command) { copy(AppText.get(R.string.ui_ssh_connection_command), command) } }, Modifier.weight(1f))
            }
            SshPage.PASSWORD -> {
                ManagerSection(AppText.get(R.string.ui_ssh_login_password)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(revealed ?: "••••••••••••", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onTogglePassword, enabled = enabled && !loading) {
                            Icon(painterResource(if (revealed == null) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                                if (revealed == null) AppText.get(R.string.ui_show_ssh_password) else AppText.get(R.string.ui_hide_ssh_password), Modifier.size(20.dp))
                        }
                    }
                    ManagerActionButton(AppText.get(R.string.ui_copy_password), onCopyPassword, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, enabled && !loading)
                    ManagerActionButton(AppText.get(R.string.ui_edit_ssh_settings), onEdit, Modifier.fillMaxWidth(), enabled = enabled)
                }
                Text(AppText.get(R.string.ui_hidden_automatically_when_you_leave_this_page_or_switch),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SshPage.HELP -> {
                ManagerSection(AppText.get(R.string.ui_help)) {
                    Text(AppText.get(R.string.ui_verify_the_host_fingerprint_on_first_connection_the_root),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                ManagerActionButton(AppText.get(R.string.ui_uninstall_ssh), onUninstall, Modifier.fillMaxWidth(), ActionTone.STOP, enabled)
            }
        }
    }

    @Composable private fun SshEditDialog(config: SshConfiguration, onDismiss: () -> Unit, onSave: (Int, Boolean, String?) -> Unit) {
        var port by remember { mutableStateOf(config.port.toString()) }
        var lan by remember { mutableStateOf(config.lanEnabled) }
        var password by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        val valid = port.toIntOrNull() in 1024..65535 && (password.isEmpty() || password.length in 12..128 && password.none { it.isISOControl() })
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onDismiss() }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_ssh_settings)) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it }, label = { Text(AppText.get(R.string.ui_ssh_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(AppText.get(R.string.ui_allow_lan_connections), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(lan, { lan = it })
                }
                if (lan) Text(AppText.get(R.string.ui_devices_on_the_same_network_can_access_ubuntu_using), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { password = it }, label = { Text(AppText.get(R.string.ui_new_password_leave_blank_to_keep_current)) }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
                        IconButton(onClick = { visible = !visible }) { Icon(painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility), AppText.get(R.string.ui_toggle_new_password_visibility), Modifier.size(20.dp)) }
                    })
                Text(AppText.get(R.string.ui_use_12_128_characters_saving_disconnects_existing_ssh_sessions), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { onSave(port.toInt(), lan, password.takeIf { it.isNotEmpty() }) }, enabled = valid) { Text(AppText.get(R.string.ui_save)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_cancel)) } })
    }

    private fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, AppText.get(R.string.ui_copied, label), Toast.LENGTH_SHORT).show()
    }
}
