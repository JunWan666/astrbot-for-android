package io.astrbot.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val automaticUpdates by lazy { AutomaticUpdateChecks(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { ManagerScreen() } } }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@MainActivity) }
            val interrupted = withContext(Dispatchers.IO) { BackupRepository(filesDir).let { it.hasPendingRestore || it.hasPendingCleanup } || UpdateRepository(this@MainActivity).hasPendingUpdate || UpdateRepository(this@MainActivity, "napcat").hasPendingUpdate || RuntimeImageRepository(this@MainActivity).hasPendingUpdate || PortableBackupRepository(this@MainActivity).hasInterruptedWork || PersonaRepository(this@MainActivity).hasPendingChange }
            val wanted = getSharedPreferences("manager", MODE_PRIVATE).let { preferences ->
                ServiceId.entries.any { preferences.getBoolean("desired_${it.name}", false) } ||
                    preferences.getBoolean(SshRepository.DESIRED, false) && preferences.getBoolean(SshRepository.INSTALLED, true)
            }
            if ((interrupted || wanted) && RuntimeStore.owner == null) RuntimeService.command(this@MainActivity, "reconcile")
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun ManagerScreen() {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val networkFlow = remember { NetworkConnectivity.observe(applicationContext) }
        val network by networkFlow.collectAsStateWithLifecycle(NetworkSnapshot())
        val requestedLog = LogCategory.entries.firstOrNull { it.name == intent.getStringExtra("openLogService") }
        val requestedService = ServiceId.entries.firstOrNull { it.name == requestedLog?.name }
        var page by rememberSaveable { mutableStateOf(if (requestedLog != null && requestedService == null) ManagerPage.SETTINGS else ManagerPage.HOME) }
        val personaPageState = rememberSaveableStateHolder()
        var selectedService by rememberSaveable { mutableStateOf(requestedService) }
        var serviceRoute by rememberSaveable { mutableStateOf(ServiceRoute.STATUS) }
        var settingsRoute by rememberSaveable { mutableStateOf(if (requestedLog != null && requestedService == null) SettingsRoute.DIAGNOSTICS else SettingsRoute.ROOT) }
        var logViewerVisible by rememberSaveable { mutableStateOf(requestedLog != null) }
        var logService by rememberSaveable { mutableStateOf(requestedLog ?: LogCategory.MANAGER) }
        var logText by remember { mutableStateOf("") }
        var logPaused by rememberSaveable { mutableStateOf(false) }
        var exportCategory by rememberSaveable { mutableStateOf<LogCategory?>(null) }
        var showVersions by rememberSaveable { mutableStateOf(false) }
        var message by remember { mutableStateOf<Pair<String, String>?>(null) }
        var backgroundGuide by rememberSaveable { mutableStateOf<BackgroundTopic?>(null) }
        var showEnvironmentCheck by rememberSaveable { mutableStateOf(false) }
        var showBackups by remember { mutableStateOf(false) }
        var showDataRetention by rememberSaveable { mutableStateOf(false) }
        var editBackupLimits by remember { mutableStateOf(false) }
        var restoreBackup by remember { mutableStateOf<BackupInfo?>(null) }
        var deleteBackup by remember { mutableStateOf<BackupInfo?>(null) }
        var confirmUpdate by remember { mutableStateOf(false) }
        var confirmRollback by remember { mutableStateOf(false) }
        var confirmRuntime by remember { mutableStateOf<String?>(null) }
        var confirmPortableExport by remember { mutableStateOf(false) }
        var confirmPortableImport by remember { mutableStateOf(false) }
        var confirmDiagnostics by remember { mutableStateOf(false) }
        var viewOnlineUpdate by remember { mutableStateOf(false) }
        var napcatUpdateDialog by remember { mutableStateOf(false) }
        var showAutomaticUpdates by remember { mutableStateOf(false) }
        val automaticSettingsFlow = remember { automaticUpdates.settings.observeEnabled() }
        val automaticEnabled by automaticSettingsFlow.collectAsStateWithLifecycle(false)
        var confirmLan by remember { mutableStateOf<Pair<ServiceId, Boolean>?>(null) }
        var lanAddresses by remember { mutableStateOf(emptyList<String>()) }
        var resources by remember { mutableStateOf(ResourceSnapshot()) }
        val resourceMonitor = remember { ResourceMonitor(this@MainActivity) }
        val importUpdate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                RuntimeService.command(this@MainActivity, "prepare-update", sourceUri = uri.toString())
            }
        }
        val exportPortable = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) RuntimeService.command(this@MainActivity, "export-backup", sourceUri = uri.toString())
        }
        val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) RuntimeService.command(this@MainActivity, "export-diagnostics", sourceUri = uri.toString())
        }
        val importPortable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                RuntimeService.command(this@MainActivity, "prepare-backup", sourceUri = uri.toString())
            }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val category = exportCategory
            exportCategory = null
            if (uri != null && category != null) lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.bufferedWriter(Charsets.UTF_8).use {
                        RuntimeStore.log(this@MainActivity, category).exportTo(it)
                    }
                } }
                Toast.makeText(this@MainActivity, if (result.isSuccess) "脱敏日志已导出" else "日志导出失败，请重试", Toast.LENGTH_LONG).show()
            } else if (uri != null) Toast.makeText(this@MainActivity, "导出已中断，请重新导出日志", Toast.LENGTH_LONG).show()
        }
        fun command(operation: String, id: ServiceId? = null, backupId: String? = null, updateId: String? = null) {
            if (Build.VERSION.SDK_INT >= 33 && operation != "stop") permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            RuntimeService.command(this@MainActivity, operation, id, backupId, updateId = updateId)
        }
        fun openLogs(category: LogCategory) {
            logService = category
            logText = ""
            logPaused = false
            logViewerVisible = true
        }
        fun back() {
            when {
                selectedService != null -> { selectedService = null; serviceRoute = ServiceRoute.STATUS; logViewerVisible = false; logText = "" }
                logViewerVisible -> { logViewerVisible = false; logText = "" }
                page == ManagerPage.SETTINGS && settingsRoute != SettingsRoute.ROOT -> settingsRoute = settingsRoute.parent
                else -> page = ManagerPage.HOME
            }
        }
        fun settingsAction(action: SettingAction) {
            when (action) {
                SettingAction.VERSIONS -> showVersions = true
                SettingAction.CHECK_UPDATE -> command("check-update")
                SettingAction.CHECK_APP_UPDATE -> startActivity(Intent(this@MainActivity, ManagerUpdateActivity::class.java))
                SettingAction.STORAGE_USAGE -> startActivity(Intent(this@MainActivity, StorageUsageActivity::class.java))
                SettingAction.AUTOMATIC_UPDATES -> showAutomaticUpdates = true
                SettingAction.IMPORT_UPDATE -> importUpdate.launch(arrayOf("application/zip", "application/octet-stream"))
                SettingAction.ONLINE_UPDATE -> {
                    napcatUpdateDialog = false
                    if (state.latestUpdate == null) command("check-update")
                    viewOnlineUpdate = true
                }
                SettingAction.APPLY_UPDATE -> { napcatUpdateDialog = false; confirmUpdate = true }
                SettingAction.CHECK_NAPCAT -> command("check-napcat")
                SettingAction.ONLINE_NAPCAT -> {
                    napcatUpdateDialog = true
                    if (state.latestNapCat == null) command("check-napcat")
                    viewOnlineUpdate = true
                }
                SettingAction.APPLY_NAPCAT -> { napcatUpdateDialog = true; confirmUpdate = true }
                SettingAction.ROLLBACK_NAPCAT -> { napcatUpdateDialog = true; confirmRollback = true }
                SettingAction.ROLLBACK -> { napcatUpdateDialog = false; confirmRollback = true }
                SettingAction.APPLY_RUNTIME -> confirmRuntime = "apply-runtime"
                SettingAction.ROLLBACK_RUNTIME -> confirmRuntime = "rollback-runtime"
                SettingAction.CREATE_BACKUP -> command("backup")
                SettingAction.BACKUPS -> showBackups = true
                SettingAction.DATA_RETENTION -> showDataRetention = true
                SettingAction.BACKUP_LIMITS -> editBackupLimits = true
                SettingAction.EXPORT_PORTABLE -> confirmPortableExport = true
                SettingAction.IMPORT_PORTABLE -> importPortable.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                SettingAction.PORTABLE_SCOPE -> confirmPortableImport = true
                SettingAction.DISCARD_PORTABLE -> command("discard-backup")
                SettingAction.LOGS -> openLogs(LogCategory.MANAGER)
                SettingAction.RUNTIME_LOGS -> openLogs(LogCategory.RUNTIME)
                SettingAction.DIAGNOSTICS -> confirmDiagnostics = true
                SettingAction.ENVIRONMENT_CHECK -> { showEnvironmentCheck = true; command("check-environment") }
                SettingAction.EXTENSIONS -> startActivity(Intent(this@MainActivity, ExtensionsActivity::class.java))
                SettingAction.APP_SETTINGS -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                SettingAction.BATTERY -> backgroundGuide = BackgroundTopic.BATTERY
                SettingAction.NOTIFICATIONS -> backgroundGuide = BackgroundTopic.NOTIFICATIONS
                SettingAction.UNRESTRICTED -> backgroundGuide = BackgroundTopic.UNRESTRICTED
                SettingAction.AUTOSTART -> backgroundGuide = BackgroundTopic.AUTOSTART
                SettingAction.TASK_LOCK -> backgroundGuide = BackgroundTopic.TASKS
                SettingAction.BACKGROUND_HELP -> backgroundGuide = BackgroundTopic.HELP
                SettingAction.LICENSES -> startActivity(Intent(this@MainActivity, ThirdPartyNoticesActivity::class.java))
            }
        }
        val canGoBack = logViewerVisible || selectedService != null || page != ManagerPage.HOME
        val showBackButton = logViewerVisible || selectedService != null || page == ManagerPage.SETTINGS && settingsRoute != SettingsRoute.ROOT
        BackHandler(enabled = canGoBack) { back() }
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(automaticEnabled, network.state, state.componentsReady, state.busy, lifecycleOwner) {
            if (automaticEnabled && state.componentsReady && !state.busy && network.state == InternetState.VALIDATED) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    automaticUpdates.checkIfDue {
                        val current = RuntimeStore.state.value
                        AppVisibility.visible.value && current.componentsReady && !current.busy &&
                            NetworkConnectivity.snapshot(this@MainActivity).state == InternetState.VALIDATED
                    }
                }
            }
        }
        LaunchedEffect(page, selectedService, logViewerVisible, lifecycleOwner) {
            if (page == ManagerPage.HOME && selectedService == null && !logViewerVisible) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    resources = resourceMonitor.sample()
                    RuntimeStore.state.update { it.copy(storageSpace = StorageSpaceReading(resources.storageAvailableBytes, resources.checkedAtMillis)) }
                    delay(15_000)
                }
            }
        }
        LaunchedEffect(logViewerVisible, logService, logPaused, lifecycleOwner) {
            if (logViewerVisible) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (logPaused && logText.isEmpty()) {
                    // Rotation restores the pause preference, not the potentially large log text.
                    logText = withContext(Dispatchers.IO) { RuntimeStore.log(this@MainActivity, logService).tail() }
                }
                while (isActive && !logPaused) {
                    logText = withContext(Dispatchers.IO) { RuntimeStore.log(this@MainActivity, logService).tail() }
                    delay(2000)
                }
            }
        }
        LaunchedEffect(Unit) {
            while (isActive) {
                lanAddresses = withContext(Dispatchers.IO) { LanAccess.addresses(this@MainActivity) }
                delay(5000)
            }
        }
        Scaffold(
            topBar = { ManagerHeader(page, state,
                title = if (logViewerVisible) "${logService.label}日志" else selectedService?.let { "${it.label} · ${serviceRoute.title}" }
                    ?: settingsRoute.takeIf { page == ManagerPage.SETTINGS && it != SettingsRoute.ROOT }?.title,
                onBack = if (showBackButton) ({ back() }) else null) },
            bottomBar = { if (selectedService != null) ServiceBottomBar(if (logViewerVisible) ServiceRoute.LOGS else serviceRoute) { route ->
                if (route == ServiceRoute.LOGS) openLogs(LogCategory.valueOf(requireNotNull(selectedService).name))
                else { logViewerVisible = false; logText = ""; serviceRoute = route }
            } else ManagerBottomBar(page) {
                page = it; logViewerVisible = false; logText = ""; selectedService = null; settingsRoute = SettingsRoute.ROOT
            } },
        ) { insets ->
            if (logViewerVisible) ManagerLogViewer(logText, logPaused,
                onPause = { logPaused = !logPaused }, onCopy = { copy("当前日志", logText) }, onExport = {
                    exportCategory = logService
                    exportLog.launch("astrbot-${logService.name.lowercase()}-${System.currentTimeMillis()}.log")
                }, modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp))
            else when (page) {
                ManagerPage.HOME -> Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    val id = selectedService
                    if (id == null) FixedManagerHome(state, network, resources,
                        onCommand = { operation, service -> command(operation, service) },
                        onDetails = { selectedService = it; serviceRoute = ServiceRoute.STATUS },
                        onOpen = { startActivity(Intent(this@MainActivity, WebUiActivity::class.java).putExtra("serviceId", it.name)) },
                        onNotice = { title, text -> message = title to text })
                    else FixedServiceDetails(id, serviceRoute, state, lanAddresses,
                        onNavigate = { serviceRoute = it }, onCommand = { command(it, id) },
                        onOpen = { startActivity(Intent(this@MainActivity, WebUiActivity::class.java).putExtra("serviceId", id.name)) },
                        onLogs = { openLogs(LogCategory.valueOf(id.name)) }, onLan = { confirmLan = id to it },
                        onExternal = { external(it) }, onCopy = { label, value -> copy(label, value) },
                        onNotice = { title, text -> message = title to text }, credentialContent = { CredentialControls(id) })
                }
                ManagerPage.PERSONAS -> personaPageState.SaveableStateProvider("personas") {
                    Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                        .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        ManagerPersonas(state,
                            onApply = { preset, mode -> command(mode, ServiceId.ASTRBOT, updateId = preset?.id) },
                            onCopy = { copy("人设提示词", it) }, onExternal = { external(it, localBackend = false) },
                            onBackend = { startActivity(Intent(this@MainActivity, WebUiActivity::class.java).putExtra("serviceId", ServiceId.ASTRBOT.name)) })
                    }
                }
                ManagerPage.SETTINGS -> Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    FixedManagerSettings(settingsRoute, state, onNavigate = { settingsRoute = it },
                        onAction = { settingsAction(it) }, onNotice = { title, text -> message = title to text },
                        automaticUpdateEnabled = automaticEnabled, updateChannelConfigured = automaticUpdates.configured)
                }
            }
        }
        if (showAutomaticUpdates) AutomaticUpdateDialog(automaticEnabled, automaticUpdates.configured, onToggle = { enabled ->
            if (!enabled || automaticUpdates.configured) lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { automaticUpdates.settings.setEnabled(enabled) } }
                if (saved.isFailure) message = "更新偏好未保存" to "请检查可用存储后重试。"
            }
        }, onDismiss = { showAutomaticUpdates = false })
        if (showVersions) ProjectAboutDialog(state, onDismiss = { showVersions = false }, onAuthor = {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProjectIdentity.AUTHOR_URL))) }
                .onFailure { Toast.makeText(this@MainActivity, "未找到浏览器，请访问 ${ProjectIdentity.AUTHOR_URL}", Toast.LENGTH_LONG).show() }
        })
        backgroundGuide?.let { BackgroundGuideDialog(it) { backgroundGuide = null } }
        if (showEnvironmentCheck) EnvironmentCheckDialog(state,
            onCheck = { command("check-environment") }, onRepair = { showEnvironmentCheck = false; confirmRuntime = "repair-runtime" },
            onMaintenance = { showEnvironmentCheck = false; settingsRoute = SettingsRoute.ENVIRONMENT },
            onDismiss = { showEnvironmentCheck = false })
        message?.let { (title, text) -> AlertDialog(onDismissRequest = { message = null }, title = { Text(title) },
            text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { message = null; openLogs(LogCategory.MANAGER) }) { Text("查看管理日志") } }) }
        confirmRuntime?.let { operation ->
            val reverting = operation == "rollback-runtime"
            val repairing = operation == "repair-runtime"
            AlertDialog(onDismissRequest = { confirmRuntime = null }, title = { Text(if (repairing) "重新部署运行资源？" else if (reverting) "回退整个运行环境？" else "升级 APK 内置环境？") },
                text = { Text(if (repairing) "将准备 APK 内置的同版本环境，停止两个服务与 SSH，备份配置、数据库和 QQ 会话，再重新部署运行资源并验证双服务。仅在组件组合完全匹配时执行，配置不重置。\n\n在 Ubuntu 程序目录内手动安装的依赖或修改将被替换；AstrBot 数据目录中的插件保留。成功后保留一次环境回退，完成后恢复原运行状态。失败则恢复原环境与数据快照。"
                    else if (reverting) "将先备份当前数据，再恢复旧环境与对应快照。恢复点之后的数据不会合并。完成后恢复原先的运行状态。"
                    else "将校验并准备 APK 内的完整环境，停止两个服务并备份配置、数据库和 QQ 会话，再切换环境并检查两个服务。失败时恢复原环境与对应快照；成功后保留一次回退。",
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(enabled = !state.busy, onClick = { confirmRuntime = null; command(operation) }) { Text(if (repairing) "备份并修复" else if (reverting) "备份并回退环境" else "备份并升级环境") } },
                dismissButton = { TextButton(onClick = { confirmRuntime = null }) { Text("取消") } })
        }
        confirmLan?.let { (id, enabled) ->
            AlertDialog(onDismissRequest = { confirmLan = null }, title = { Text("${if (enabled) "开启" else "关闭"} ${id.label} 局域网访问？") },
                text = { Text(if (enabled) "允许其他设备通过手机 IPv4 地址访问此后台，请在可信局域网开启，仍需原有登录凭据。正在运行的服务会重启，端口及其他配置保留。"
                    else "将此后台限制为手机本机访问。正在运行的服务会重启，端口及其他配置保留。") },
                confirmButton = { TextButton(onClick = { confirmLan = null; command(if (enabled) "enable-lan" else "disable-lan", id) }) { Text(if (enabled) "开启并应用" else "关闭并应用") } },
                dismissButton = { TextButton(onClick = { confirmLan = null }) { Text("取消") } })
        }
        if (confirmDiagnostics) AlertDialog(onDismissRequest = { confirmDiagnostics = false }, title = { Text("导出诊断包？") },
            text = { Text("包含设备型号、版本、运行状态和脱敏日志，不附带配置、数据库或 QQ 登录文件。导出不会停止服务。日志仍可能包含聊天内容或个人信息，请检查后再分享。") },
            confirmButton = { TextButton(onClick = { confirmDiagnostics = false; exportDiagnostics.launch("astrbot-diagnostics-${System.currentTimeMillis()}.zip") }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { confirmDiagnostics = false }) { Text("取消") } })
        if (showDataRetention) DataRetentionDialog(
            onPortableBackup = { showDataRetention = false; settingsRoute = SettingsRoute.PORTABLE },
            onDismiss = { showDataRetention = false })
        if (confirmPortableExport) AlertDialog(onDismissRequest = { confirmPortableExport = false }, title = { Text("导出便携备份？") },
            text = { Text("包含 AstrBot 配置、数据库、插件和数据，以及 NapCat 配置；不包含 QQ 登录会话、运行环境和本机快照。会暂时停止两个服务制作一致性备份，再恢复原状态并保存到所选位置。文件含密码和业务密钥，不做脱敏；文件上限为 2 GiB。") },
            confirmButton = { TextButton(onClick = { confirmPortableExport = false; exportPortable.launch("astrbot-portable-${System.currentTimeMillis()}.zip") }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { confirmPortableExport = false }) { Text("取消") } })
        if (confirmPortableImport) state.portableImport?.let { backup ->
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAt))
            AlertDialog(onDismissRequest = { confirmPortableImport = false }, title = { Text("恢复此便携备份？") },
                text = { Text("备份时间：$date。将先停机创建本机恢复前快照，再替换 AstrBot 数据与 NapCat 配置，保留当前手机的 QQ 会话。备份点之后的数据不会合并；导入后请使用备份中的后台凭据检查业务配置。") },
                confirmButton = { TextButton(onClick = { confirmPortableImport = false; command("apply-backup") }) { Text("备份当前数据并导入") } },
                dismissButton = { TextButton(onClick = { confirmPortableImport = false }) { Text("取消") } })
        }
        if (editBackupLimits) {
            var countText by remember { mutableStateOf(state.backupLimits.automaticCount.toString()) }
            var sizeText by remember { mutableStateOf((state.backupLimits.automaticBytes / 1024 / 1024).toString()) }
            val count = countText.toIntOrNull()
            val size = sizeText.toIntOrNull()
            val valid = count != null && count in 1..100 && size != null && size in 64..65536
            AlertDialog(onDismissRequest = { editBackupLimits = false }, title = { Text("自动快照上限") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新建更新前或恢复前快照时，按上限自动清理最旧且未受保护的自动快照。新快照校验成功后才清理旧快照。")
                    OutlinedTextField(value = countText, onValueChange = { countText = it.take(4) }, label = { Text("最多保留（份）") },
                        supportingText = { Text("1 至 100 份") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = sizeText, onValueChange = { sizeText = it.take(6) }, label = { Text("容量上限（MiB）") },
                        supportingText = { Text("64 至 65536 MiB；1024 MiB = 1 GiB") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("手动备份不计入上限且不会自动删除。回退所需和本次选中的恢复快照会保留；若仍无法满足上限，将暂停操作并提示调整。降低上限从下次自动快照生效。", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(enabled = valid && !state.busy, onClick = {
                editBackupLimits = false
                RuntimeService.command(this@MainActivity, "save-backup-limits", backupLimitCount = requireNotNull(count), backupLimitMiB = requireNotNull(size))
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editBackupLimits = false }) { Text("取消") } })
        }
        if (showBackups) AlertDialog(onDismissRequest = { showBackups = false }, title = { Text("本机备份") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val automatic = state.backups.filter { it.kind != "manual" }
                Text("备份文件合计 ${Formatter.formatFileSize(this@MainActivity, state.backups.sumOf { it.storedBytes })}")
                Text("自动快照 ${automatic.size} / ${state.backupLimits.automaticCount} 份 · ${Formatter.formatFileSize(this@MainActivity, automatic.sumOf { it.storedBytes })} / ${state.backupLimits.automaticBytes / 1024 / 1024} MiB",
                    style = MaterialTheme.typography.bodySmall)
                if (state.backups.isEmpty()) Text("暂无备份")
                state.backups.forEach { backup ->
                    val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAt))
                    Text(date, style = MaterialTheme.typography.titleSmall)
                    Text("${when (backup.kind) { "manual" -> "手动备份"; "before-restore" -> "恢复前快照"; else -> "更新前快照" }} · ${backup.bytes / 1024 / 1024} MB · ${backup.count} 个文件项")
                    Text("AstrBot ${backup.versions["AstrBot"] ?: "未知"} / NapCat ${backup.versions["NapCat"] ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                    if (backup.id in state.protectedBackupIds) Text("回退所需 · 受保护", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { restoreBackup = backup }, enabled = !state.busy) { Text("恢复") }
                        TextButton(onClick = { deleteBackup = backup }, enabled = !state.busy && backup.id !in state.protectedBackupIds) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { showBackups = false }) { Text("关闭") } })
        restoreBackup?.let { backup ->
            AlertDialog(onDismissRequest = { restoreBackup = null }, title = { Text("恢复此备份？") },
                text = { Text("两个服务将暂时停止，当前数据会先备份，然后替换为此恢复点的配置、数据库、插件与 QQ 会话。恢复点之后的数据不会合并；仅允许恢复与当前程序版本匹配的备份。") },
                confirmButton = { TextButton(onClick = { restoreBackup = null; showBackups = false; command("restore", backupId = backup.id) }) { Text("备份当前数据并恢复") } },
                dismissButton = { TextButton(onClick = { restoreBackup = null }) { Text("取消") } })
        }
        deleteBackup?.let { backup ->
            AlertDialog(onDismissRequest = { deleteBackup = null }, title = { Text("删除此本机备份？") },
                text = { Text("仅删除选中的备份文件，服务当前使用的数据不受影响。此操作无法撤销。") },
                confirmButton = { TextButton(onClick = { deleteBackup = null; command("delete-backup", backupId = backup.id) }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { deleteBackup = null }) { Text("取消") } })
        }
        val updateLabel = if (napcatUpdateDialog) "NapCat" else "AstrBot"
        val stagedUpdate = if (napcatUpdateDialog) state.preparedNapCat else state.preparedUpdate
        val onlineVersion = if (napcatUpdateDialog) state.latestNapCat else state.latestUpdate
        if (confirmUpdate) stagedUpdate?.let { update ->
            AlertDialog(onDismissRequest = { confirmUpdate = false }, title = { Text("更新 $updateLabel 至 ${update.version}？") },
                text = { Text("${update.channel}\n${update.notes}\n\n将暂时停止两个服务，先备份数据，再切换程序。配置与 QQ 会话会保留，失败会恢复原程序及对应数据。" +
                    if (napcatUpdateDialog) "QQ 原本停止时保持停止；原本运行时会重启检查后台接口。" else "AstrBot 会启动检查本机后台。") },
                confirmButton = { TextButton(onClick = { confirmUpdate = false; command(if (napcatUpdateDialog) "apply-napcat" else "apply-update") }) { Text("备份并更新") } },
                dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("取消") } })
        }
        if (viewOnlineUpdate && onlineVersion != null) {
            val update = onlineVersion
            AlertDialog(onDismissRequest = { viewOnlineUpdate = false }, title = { Text("$updateLabel ${update.version}") },
                text = { Text("${update.channel} · ${update.bytes / 1024 / 1024} MB\n${update.notes}\n\n" +
                    (if (state.versions[updateLabel] == update.version) "当前已是此版本，可重新下载或安装。\n" else "") +
                    "下载和校验期间服务继续运行。下载完成后需另行确认备份和安装。") },
                confirmButton = { TextButton(onClick = { viewOnlineUpdate = false; command(if (napcatUpdateDialog) "download-napcat" else "download-update", updateId = update.id) }) { Text("下载并校验") } },
                dismissButton = { TextButton(onClick = { viewOnlineUpdate = false }) { Text("关闭") } })
        }
        if (confirmRollback) AlertDialog(onDismissRequest = { confirmRollback = false }, title = { Text("回退 $updateLabel 及对应数据？") },
            text = { Text("当前数据会先备份，然后恢复上次更新前的程序与数据快照。恢复点之后的数据不会合并，两个服务将暂时停止。") },
            confirmButton = { TextButton(onClick = { confirmRollback = false; command(if (napcatUpdateDialog) "rollback-napcat" else "rollback") }) { Text("备份当前数据并回退") } },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("取消") } })
    }

    @Composable private fun CredentialControls(id: ServiceId) {
        val label = if (id == ServiceId.NAPCAT) "NapCat 访问口令" else "AstrBot 初始密码"
        val owner = LocalLifecycleOwner.current
        var revealed by remember(id) { mutableStateOf<String?>(null) }
        var loading by remember(id) { mutableStateOf(false) }
        var request by remember(id) { mutableIntStateOf(0) }
        DisposableEffect(owner, id) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) { request++; revealed = null; loading = false }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { request++; revealed = null; loading = false; owner.lifecycle.removeObserver(observer) }
        }
        fun readCredential(onRead: (String) -> Unit) {
            val attempt = ++request
            loading = true
            lifecycleScope.launch {
                val value = withContext(Dispatchers.IO) { WebUiCredentials.read(this@MainActivity, id) }
                if (attempt == request && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    loading = false
                    if (value == null) Toast.makeText(this@MainActivity, "首次启动后生成凭据；已有配置请使用原密码", Toast.LENGTH_SHORT).show()
                    else onRead(value)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(revealed ?: "••••••••", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = {
                if (revealed != null) { request++; revealed = null }
                else readCredential { revealed = it }
            }, enabled = !loading) {
                Icon(painterResource(if (revealed == null) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription = "${if (revealed == null) "显示" else "隐藏"} $label")
            }
        }
        ManagerActionButton("复制$label", { readCredential { copy(label, it, sensitive = true) } }, tone = ActionTone.NEUTRAL, enabled = !loading)
    }

    private fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "已复制$label", Toast.LENGTH_SHORT).show()
    }
    private fun external(url: String, localBackend: Boolean = true) {
        if (localBackend) Toast.makeText(this, "外部浏览器可能需要单独登录后台", Toast.LENGTH_SHORT).show()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
    }
}
