package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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

class ExtensionsActivity : ComponentActivity() {
    private enum class SshPage(val title: String) { OVERVIEW("SSH 终端"), CONNECTION("连接信息"), PASSWORD("登录密码"), HELP("使用说明与卸载") }
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
        var sshPage by rememberSaveable { mutableStateOf(SshPage.OVERVIEW) }
        val enabled = !manager.busy && !ssh.busy
        val status = when { ssh.paused -> "维护暂停"; ssh.running -> "运行中"; !ssh.configuration.installed -> "未安装"; else -> "已停止" }
        fun back() { if (detail && sshPage != SshPage.OVERVIEW) sshPage = SshPage.OVERVIEW else if (detail) detail = false else finish() }
        BackHandler(detail) { back() }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) {
                    Icon(painterResource(R.drawable.ic_chevron), "返回", Modifier.size(22.dp).rotate(180f))
                }
                Text(if (detail) sshPage.title else "运行扩展", style = MaterialTheme.typography.titleLarge)
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!detail) {
                    Text("管理 Ubuntu 的可选功能", Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManagerSection("已提供的扩展", padded = false) {
                        ManagerSettingsRow("SSH 终端", R.drawable.ic_settings, "远程进入 Ubuntu · 随应用内置", value = status, divider = false) { detail = true }
                    }
                    Text("此处管理运行环境扩展。机器人业务插件请在 AstrBot 后台管理。", Modifier.padding(horizontal = 4.dp),
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
                    if (value != null) consume(value) else Toast.makeText(this@ExtensionsActivity, "密码无法读取，请重新设置密码或重装扩展", Toast.LENGTH_LONG).show()
                }
            }
        }
        SshPageContent(state, enabled, page, addresses, revealed, loading,
            onNavigate = onNavigate, onTogglePassword = { if (revealed != null) revealed = null else readPassword { revealed = it } },
            onCopyPassword = { readPassword { copy("SSH 密码", it, sensitive = true) } },
            onEdit = { edit = true }, onUninstall = { confirmUninstall = true }, onFingerprint = { showFingerprint = true })
        if (edit) SshEditDialog(state.configuration, onDismiss = { edit = false }) { port, lan, password ->
            revealed = null; edit = false
            RuntimeService.command(this@ExtensionsActivity, "ssh-save", sshPort = port, sshLan = lan, sshPassword = password)
        }
        if (confirmUninstall) AlertDialog(onDismissRequest = { confirmUninstall = false }, title = { Text("卸载 SSH 扩展？") }, text = {
            Text("将停止 SSH、断开终端，并删除 SSH 密码、主机密钥和连接设置。AstrBot、NapCat 和 Ubuntu 数据会保留。内置资源随 APK 保留，可随时重新安装。")
        }, confirmButton = { TextButton(onClick = { confirmUninstall = false; revealed = null; RuntimeService.command(this@ExtensionsActivity, "ssh-uninstall") }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } })
        if (showFingerprint) AlertDialog(onDismissRequest = { showFingerprint = false }, title = { Text("SSH 主机指纹") },
            text = { Text(state.fingerprint ?: "首次启动 SSH 后生成主机指纹") },
            confirmButton = { TextButton(onClick = { showFingerprint = false }) { Text("知道了") } })
    }

    @Composable private fun ColumnScope.SshPageContent(state: SshState, enabled: Boolean, page: SshPage,
        addresses: List<String>, revealed: String?, loading: Boolean, onNavigate: (SshPage) -> Unit,
        onTogglePassword: () -> Unit, onCopyPassword: () -> Unit, onEdit: () -> Unit, onUninstall: () -> Unit, onFingerprint: () -> Unit) {
        when (page) {
            SshPage.OVERVIEW -> {
                ManagerSection("Ubuntu SSH") {
                    Text(when { state.paused -> "维护中，暂时断开"; state.running -> "服务运行中"; state.configuration.installed -> "已安装 · 未启动"; else -> "尚未安装" },
                        style = MaterialTheme.typography.titleMedium)
                    Text("远程进入 Ubuntu，与 AstrBot、NapCat 分别启停。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ManagerActionButton(if (!state.configuration.installed) "安装 SSH 扩展" else if (state.running) "停止 SSH" else "启动 SSH", {
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
                    ManagerMenuItem("连接信息", R.drawable.ic_link, "登录账号、端口与连接命令") { onNavigate(SshPage.CONNECTION) },
                    ManagerMenuItem("登录密码", R.drawable.ic_visibility, "显示、隐藏和复制密码") { onNavigate(SshPage.PASSWORD) },
                    ManagerMenuItem("修改 SSH 设置", R.drawable.ic_settings, "端口、局域网与密码", enabled) { onEdit() },
                    ManagerMenuItem("使用说明与卸载", R.drawable.ic_info, "终端使用说明和扩展管理") { onNavigate(SshPage.HELP) }), Modifier.weight(1f))
            }
            SshPage.CONNECTION -> {
                ManagerSection("连接信息") {
                    Text("登录账号：root", style = MaterialTheme.typography.bodyMedium)
                    Text("端口：${state.configuration.port} · ${if (state.configuration.lanEnabled) "局域网可访问" else "仅本机访问"}", style = MaterialTheme.typography.bodySmall)
                    ManagerActionButton("查看主机指纹", onFingerprint, tone = ActionTone.NEUTRAL)
                }
                val commands = listOf("ssh -p ${state.configuration.port} root@127.0.0.1") +
                    if (state.configuration.lanEnabled) addresses.map { "ssh -p ${state.configuration.port} root@$it" } else emptyList()
                FixedManagerMenu(commands.mapIndexed { index, command -> ManagerMenuItem(if (index == 0) "复制本机连接命令" else "复制局域网连接命令",
                    R.drawable.ic_document, command) { copy("SSH 连接命令", command) } }, Modifier.weight(1f))
            }
            SshPage.PASSWORD -> {
                ManagerSection("SSH 登录密码") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(revealed ?: "••••••••••••", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onTogglePassword, enabled = enabled && !loading) {
                            Icon(painterResource(if (revealed == null) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                                if (revealed == null) "显示 SSH 密码" else "隐藏 SSH 密码", Modifier.size(20.dp))
                        }
                    }
                    ManagerActionButton("复制密码", onCopyPassword, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, enabled && !loading)
                    ManagerActionButton("修改 SSH 设置", onEdit, Modifier.fillMaxWidth(), enabled = enabled)
                }
                Text("离开此页或切到后台后自动隐藏。修改密码并保存设置会断开已有 SSH 连接。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SshPage.HELP -> {
                ManagerSection("使用说明") {
                    Text("首次连接时请核对主机指纹。root 是 Ubuntu 中的账号，不代表手机已获取 Root 权限。\n\n维护、备份和更新会临时断开 SSH，完成后按原偏好恢复。操作前请保存终端工作。\n\n支持交互终端和远程命令，暂不提供 SFTP。",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                ManagerActionButton("卸载 SSH 扩展", onUninstall, Modifier.fillMaxWidth(), ActionTone.STOP, enabled)
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
        AlertDialog(onDismissRequest = onDismiss, title = { Text("SSH 设置") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it }, label = { Text("SSH 端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许局域网连接", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(lan, { lan = it })
                }
                if (lan) Text("同一网络内的设备可使用密码进入 Ubuntu，请在可信网络中开启。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { password = it }, label = { Text("新密码（留空保留原密码）") }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
                        IconButton(onClick = { visible = !visible }) { Icon(painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility), "切换新密码可见性", Modifier.size(20.dp)) }
                    })
                Text("密码 12–128 个字符。保存后会断开已有 SSH 连接；正在运行的 SSH 会重新启动。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { onSave(port.toInt(), lan, password.takeIf { it.isNotEmpty() }) }, enabled = valid) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    }

    private fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "已复制$label", Toast.LENGTH_SHORT).show()
    }
}
