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

class MainActivity : LocalizedActivity() {
    private val automaticUpdates by lazy { AutomaticUpdateChecks(applicationContext) }
    private var requestedPage by mutableStateOf<ManagerPage?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPage = ManagerPage.entries.firstOrNull { it.name == intent.getStringExtra("openPage") }
        enableEdgeToEdge()
        setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { ManagerScreen() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage = ManagerPage.entries.firstOrNull { it.name == intent.getStringExtra("openPage") }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@MainActivity) }
            val interrupted = withContext(Dispatchers.IO) { BackupRepository(filesDir).let { it.hasPendingRestore || it.hasPendingCleanup } || UpdateRepository(this@MainActivity).hasPendingUpdate || RuntimeImageRepository(this@MainActivity).hasPendingUpdate || EnvironmentRepairRepository(this@MainActivity).hasPendingRepair || PortableBackupRepository(this@MainActivity).hasInterruptedWork || PersonaRepository(this@MainActivity).hasPendingChange }
            val wanted = BackgroundRecovery.hasDesiredServices(this@MainActivity)
            if ((interrupted || wanted) && RuntimeStore.owner == null) RuntimeService.command(this@MainActivity, "reconcile")
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun ManagerScreen() {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val networkFlow = remember { NetworkConnectivity.observe(applicationContext) }
        val network by networkFlow.collectAsStateWithLifecycle(NetworkSnapshot())
        val requestedLog = LogCategory.entries.firstOrNull { it.name == intent.getStringExtra("openLogService") }
        val requestedService = ServiceId.entries.firstOrNull { it.name == (requestedLog?.name ?: intent.getStringExtra("openService")) }
        var page by rememberSaveable { mutableStateOf(if (requestedLog != null && requestedService == null) ManagerPage.SETTINGS else ManagerPage.HOME) }
        var selectedService by rememberSaveable { mutableStateOf(requestedService) }
        var serviceRoute by rememberSaveable { mutableStateOf(ServiceRoute.STATUS) }
        var settingsRoute by rememberSaveable { mutableStateOf(if (requestedLog != null && requestedService == null) SettingsRoute.DIAGNOSTICS else SettingsRoute.ROOT) }
        var logViewerVisible by rememberSaveable { mutableStateOf(requestedLog != null) }
        var logService by rememberSaveable { mutableStateOf(requestedLog ?: LogCategory.MANAGER) }
        LaunchedEffect(requestedPage) {
            requestedPage?.let {
                page = it; selectedService = null; logViewerVisible = false; settingsRoute = SettingsRoute.ROOT
                requestedPage = null
            }
        }
        var logText by remember { mutableStateOf("") }
        var logPaused by rememberSaveable { mutableStateOf(false) }
        var exportCategory by rememberSaveable { mutableStateOf<LogCategory?>(null) }
        var showVersions by rememberSaveable { mutableStateOf(false) }
        var message by remember { mutableStateOf<Pair<String, String>?>(null) }
        var shownProblem by remember { mutableStateOf<OperationProblem?>(null) }
        fun showNotice(title: String, text: String) {
            if (state.error != null && text == state.error) {
                message = null
                shownProblem = state.currentProblem() ?: OperationProblem.failed("unknown", state.phase, text)
            } else message = title to text
        }
        var backgroundGuide by rememberSaveable { mutableStateOf<BackgroundTopic?>(null) }
        var showEnvironmentCheck by rememberSaveable { mutableStateOf(false) }
        var showBackups by remember { mutableStateOf(false) }
        var showDataRetention by rememberSaveable { mutableStateOf(false) }
        var editBackupLimits by remember { mutableStateOf(false) }
        var restoreBackup by remember { mutableStateOf<BackupInfo?>(null) }
        var deleteBackup by remember { mutableStateOf<BackupInfo?>(null) }
        var confirmUpdate by remember { mutableStateOf(false) }
        var showUpdateImport by rememberSaveable { mutableStateOf(false) }
        var importRequest by rememberSaveable { mutableStateOf<String?>(null) }
        var importReady by rememberSaveable { mutableStateOf(false) }
        var importMessage by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmRollback by remember { mutableStateOf(false) }
        var confirmRuntime by remember { mutableStateOf<String?>(null) }
        var confirmPortableExport by remember { mutableStateOf(false) }
        var portableExportMask by rememberSaveable { mutableIntStateOf(1) }
        var clearAllLogs by remember { mutableStateOf(false) }
        var confirmPortableImport by remember { mutableStateOf(false) }
        var confirmDiagnostics by remember { mutableStateOf(false) }
        var viewOnlineUpdate by remember { mutableStateOf(false) }
        var requestedComponentCheck by remember { mutableLongStateOf(-1) }
        var showAutomaticUpdates by remember { mutableStateOf(false) }
        var showUpdateChannel by remember { mutableStateOf(false) }
        var showLanguage by remember { mutableStateOf(false) }
        var storagePrompt by remember { mutableStateOf<Pair<RuntimeStorageAdvice, () -> Unit>?>(null) }
        val automaticSettingsFlow = remember { automaticUpdates.settings.observeEnabled() }
        val automaticEnabled by automaticSettingsFlow.collectAsStateWithLifecycle(false)
        var confirmLan by remember { mutableStateOf<Pair<ServiceId, Boolean>?>(null) }
        var lanAddresses by remember { mutableStateOf(emptyList<String>()) }
        var resources by remember { mutableStateOf(ResourceSnapshot()) }
        val resourceMonitor = remember { ResourceMonitor(this@MainActivity) }
        val platformStatuses = rememberMessagePlatforms(state)
        fun openAstrBot(route: String = "") {
            startActivity(Intent(this@MainActivity, WebUiActivity::class.java).putExtra("serviceId", ServiceId.ASTRBOT.name)
                .apply { if (route in AstrBotWebRoutes.allowed) putExtra(AstrBotWebRoutes.EXTRA, route) })
        }
        val importUpdate = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                if (uris.size > 2) importMessage = AppText.get(R.string.upstream_import_pair)
                else {
                    importMessage = null
                    importReady = false
                    importRequest = RuntimeService.command(this@MainActivity, "prepare-update",
                        sourceUri = org.json.JSONArray(uris.map { it.toString() }).toString())
                }
            }
        }
        LaunchedEffect(importRequest, state.completedOperation, state.busy) {
            if (importRequest != null && state.completedOperation == importRequest && !state.busy) {
                importReady = state.error == null && state.pendingUpdateArchive == null && state.preparedUpdate != null
                importMessage = state.error
                importRequest = null
            }
        }
        val exportPortable = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) RuntimeService.command(this@MainActivity, "export-backup", sourceUri = uri.toString(), backupSelectionMask = portableExportMask)
        }
        val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) RuntimeService.command(this@MainActivity, "export-diagnostics", sourceUri = uri.toString())
        }
        LaunchedEffect(state.sharedExport) {
            state.sharedExport?.let { path ->
                RuntimeStore.state.update { if (it.sharedExport == path) it.copy(sharedExport = null) else it }
                runCatching { SharedExports.launch(this@MainActivity, File(path), "application/zip") }.onFailure {
                    Toast.makeText(this@MainActivity, AppText.get(R.string.export_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        val importPortable = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                RuntimeService.command(this@MainActivity, "prepare-backup", sourceUri = uri.toString())
            }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val terminal = remember { TerminalPage(this@MainActivity) { permission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        DisposableEffect(terminal) { onDispose { terminal.close() } }
        val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val category = exportCategory
            exportCategory = null
            if (uri != null && category != null) lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.bufferedWriter(Charsets.UTF_8).use {
                        RuntimeStore.log(this@MainActivity, category).exportTo(it)
                    }
                } }
                Toast.makeText(this@MainActivity, if (result.isSuccess) AppText.get(R.string.ui_redacted_logs_exported) else AppText.get(R.string.ui_log_export_failed_please_retry), Toast.LENGTH_LONG).show()
            } else if (uri != null) Toast.makeText(this@MainActivity, AppText.get(R.string.ui_export_interrupted_export_the_logs_again), Toast.LENGTH_LONG).show()
        }
        fun command(operation: String, id: ServiceId? = null, backupId: String? = null, updateId: String? = null) {
            val send: () -> Unit = {
                if (Build.VERSION.SDK_INT >= 33 && operation != "stop") permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                RuntimeService.command(this@MainActivity, operation, id, backupId, updateId = updateId)
            }
            if (operation in setOf("initialize", "start", "restart", "apply-runtime", "repair-runtime")) {
                if (RuntimeStore.state.value.busy || storagePrompt != null) return
                val preparation = RuntimeStore.beginRequest()
                lifecycleScope.launch {
                    try {
                        val advice = withContext(Dispatchers.IO) { RuntimeStorageAdvice.beforeOperation(this@MainActivity, operation) }
                        if (advice?.shouldWarn == true) storagePrompt = advice to send else send()
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        val problem = OperationProblem.failed(operation, state.phase,
                            failure.userFacingMessage() ?: AppText.get(R.string.problem_unknown_failure))
                        RuntimeStore.state.update { it.copy(error = problem.summary, problem = problem) }
                    } finally { RuntimeStore.endRequest(preparation) }
                }
            } else send()
        }
        fun openLogs(category: LogCategory) {
            logService = category
            logText = ""
            logPaused = false
            logViewerVisible = true
        }
        fun back() {
            when {
                selectedService != null -> {
                    if (intent.hasExtra("openService")) finish()
                    else { selectedService = null; serviceRoute = ServiceRoute.STATUS; logViewerVisible = false; logText = "" }
                }
                logViewerVisible -> { logViewerVisible = false; logText = "" }
                page == ManagerPage.SETTINGS && settingsRoute != SettingsRoute.ROOT -> settingsRoute = settingsRoute.parent
                else -> page = ManagerPage.HOME
            }
        }
        fun settingsAction(action: SettingAction) {
            when (action) {
                SettingAction.VERSIONS -> showVersions = true
                SettingAction.CHECK_UPDATE -> { requestedComponentCheck = state.componentCheckSerial; viewOnlineUpdate = true; command("check-update") }
                SettingAction.UPDATE_CHANNEL -> showUpdateChannel = true
                SettingAction.CHECK_APP_UPDATE -> startActivity(Intent(this@MainActivity, ManagerUpdateActivity::class.java))
                SettingAction.STORAGE_USAGE -> startActivity(Intent(this@MainActivity, StorageUsageActivity::class.java))
                SettingAction.AUTOMATIC_UPDATES -> showAutomaticUpdates = true
                SettingAction.LANGUAGE -> showLanguage = true
                SettingAction.IMPORT_UPDATE -> {
                    viewOnlineUpdate = false
                    importReady = false
                    importMessage = null
                    showUpdateImport = true
                }
                SettingAction.ONLINE_UPDATE -> {
                    requestedComponentCheck = if (state.latestUpdate == null) state.componentCheckSerial else -1
                    if (state.latestUpdate == null) command("check-update")
                    viewOnlineUpdate = true
                }
                SettingAction.APPLY_UPDATE -> { confirmUpdate = true }
                SettingAction.ROLLBACK -> { confirmRollback = true }
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
                SettingAction.CLEAR_LOGS -> clearAllLogs = true
                SettingAction.DIAGNOSTICS -> confirmDiagnostics = true
                SettingAction.ENVIRONMENT_CHECK -> { showEnvironmentCheck = true; command("check-environment") }
                SettingAction.EXTENSIONS -> startActivity(Intent(this@MainActivity, ExtensionsActivity::class.java))
                SettingAction.PERMISSIONS -> startActivity(Intent(this@MainActivity, DebugAccessActivity::class.java))
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
            contentWindowInsets = if (page == ManagerPage.TERMINAL) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = { if (page != ManagerPage.TERMINAL) ManagerHeader(page, state,
                title = if (logViewerVisible) AppText.get(R.string.ui_logs, logService.label) else selectedService?.let { "${it.label} · ${serviceRoute.title}" }
                    ?: settingsRoute.takeIf { page == ManagerPage.SETTINGS && it != SettingsRoute.ROOT }?.title,
                onBack = if (showBackButton) ({ back() }) else null) },
            bottomBar = { if (selectedService != null) ServiceBottomBar(if (logViewerVisible) ServiceRoute.LOGS else serviceRoute) { route ->
                if (route == ServiceRoute.LOGS) openLogs(LogCategory.valueOf(requireNotNull(selectedService).name))
                else { logViewerVisible = false; logText = ""; serviceRoute = route }
            } else if (page != ManagerPage.TERMINAL || !WindowInsets.isImeVisible) ManagerBottomBar(page) {
                page = it; logViewerVisible = false; logText = ""; selectedService = null; settingsRoute = SettingsRoute.ROOT
            } },
        ) { insets ->
            if (logViewerVisible) ManagerLogViewer(logText, logPaused,
                onPause = { logPaused = !logPaused }, onCopy = { copy(AppText.get(R.string.ui_current_logs), logText) }, onExport = {
                    exportCategory = logService
                    exportLog.launch("astrbot-${logService.name.lowercase()}-${System.currentTimeMillis()}.log")
                }, onClear = { RuntimeService.command(this@MainActivity, "clear-logs", logCategory = logService.name); logText = "" }, shareCategory = logService,
                modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp))
            else when (page) {
                ManagerPage.HOME -> Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    val id = selectedService
                    if (id == null) AstrBotHome(state, network, resources, platformStatuses,
                        onCommand = { operation -> command(operation, ServiceId.ASTRBOT) },
                        onDetails = { selectedService = ServiceId.ASTRBOT; serviceRoute = ServiceRoute.STATUS },
                        onOpen = { openAstrBot(it) }, onLogs = { openLogs(LogCategory.ASTRBOT) },
                        onPlatforms = { platform -> startActivity(Intent(this@MainActivity, MessagePlatformActivity::class.java)
                            .putExtra("platform", platform.name)) },
                        onNotice = { title, text -> showNotice(title, text) })
                    else FixedServiceDetails(id, serviceRoute, state, lanAddresses,
                        onNavigate = { serviceRoute = it }, onCommand = { command(it, id) },
                        onOpen = { startActivity(Intent(this@MainActivity, WebUiActivity::class.java).putExtra("serviceId", id.name)) },
                        onLogs = { openLogs(LogCategory.valueOf(id.name)) }, onLan = { confirmLan = id to it },
                        onExternal = { external(it) }, onCopy = { label, value -> copy(label, value) },
                        onNotice = { title, text -> showNotice(title, text) }, credentialContent = { CredentialControls(id) })
                }
                ManagerPage.TERMINAL -> terminal.Screen(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets))
                ManagerPage.TASKS -> UserTasksScreen(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp))
                ManagerPage.WEBSITES -> WebsiteBookmarksScreen(
                    modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                        .padding(horizontal = 16.dp).padding(bottom = 12.dp))
                ManagerPage.SETTINGS -> Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    FixedManagerSettings(settingsRoute, state, onNavigate = { settingsRoute = it },
                        onAction = { settingsAction(it) }, onNotice = { title, text -> showNotice(title, text) },
                        automaticUpdateEnabled = automaticEnabled, updateChannelConfigured = automaticUpdates.configured)
                }
            }
        }
        if (showLanguage) LanguageDialog(onDismiss = { showLanguage = false }) { language ->
            showLanguage = false
            AppLanguage.set(this@MainActivity, language)
            if (Build.VERSION.SDK_INT < 33) recreate()
        }
        storagePrompt?.let { (advice, send) ->
            RuntimeStorageDialog(advice, onContinue = { storagePrompt = null; send() },
                onDismiss = { storagePrompt = null })
        }
        if (showAutomaticUpdates) AutomaticUpdateDialog(automaticEnabled, automaticUpdates.configured, onToggle = { enabled ->
            if (!enabled || automaticUpdates.configured) lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { automaticUpdates.settings.setEnabled(enabled) } }
                if (saved.isFailure) message = AppText.get(R.string.ui_update_preferences_were_not_saved) to AppText.get(R.string.ui_check_available_storage_and_try_again)
            }
        }, onDismiss = { showAutomaticUpdates = false })
        if (showVersions) ProjectAboutDialog(state, onDismiss = { showVersions = false }, onAuthor = {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProjectIdentity.AUTHOR_URL))) }
                .onFailure { Toast.makeText(this@MainActivity, AppText.get(R.string.ui_no_browser_found_visit, ProjectIdentity.AUTHOR_URL), Toast.LENGTH_LONG).show() }
        })
        backgroundGuide?.let { BackgroundGuideDialog(it) { backgroundGuide = null } }
        if (showEnvironmentCheck) EnvironmentCheckDialog(state,
            onCheck = { command("check-environment") }, onRepair = { showEnvironmentCheck = false; confirmRuntime = "repair-environment" },
            onMaintenance = { showEnvironmentCheck = false; settingsRoute = SettingsRoute.ENVIRONMENT },
            onDismiss = { showEnvironmentCheck = false },
            onShare = { showEnvironmentCheck = false; command("share-diagnostics") })
        shownProblem?.let { problem -> OperationProblemDialog(problem, state.busy,
            onCheck = { shownProblem = null; showEnvironmentCheck = true; command("check-environment") },
            onShare = { shownProblem = null; command("share-diagnostics") },
            onLogs = { shownProblem = null; openLogs(LogCategory.MANAGER) }, onDismiss = { shownProblem = null }) }
        message?.let { (title, text) -> AlertDialog(onDismissRequest = { message = null }, title = { Text(title) },
            text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(AppText.get(R.string.ui_close)) } },
            dismissButton = { TextButton(onClick = { message = null; openLogs(LogCategory.MANAGER) }) { Text(AppText.get(R.string.ui_view_manager_logs)) } }) }
        confirmRuntime?.let { operation ->
            val targeted = operation == "repair-environment"
            val reverting = operation == "rollback-runtime"
            val repairing = operation == "repair-runtime"
            AlertDialog(onDismissRequest = { confirmRuntime = null }, title = { Text(if (targeted) AppText.get(R.string.repair_auto_title) else if (repairing) AppText.get(R.string.ui_redeploy_runtime_resources) else if (reverting) AppText.get(R.string.ui_roll_back_the_entire_runtime_6833c) else AppText.get(R.string.ui_upgrade_the_bundled_runtime)) },
                text = { Text(if (targeted) AppText.get(R.string.repair_confirmation) else if (repairing) AppText.get(R.string.ui_prepare_the_runtime_bundled_with_this_apk_stop_both)
                    else if (reverting) AppText.get(R.string.ui_back_up_current_data_then_restore_the_previous_runtime)
                    else AppText.get(R.string.ui_verify_and_prepare_the_full_bundled_runtime_stop_both),
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(enabled = !state.busy, onClick = { confirmRuntime = null; command(operation) }) { Text(if (targeted) AppText.get(R.string.repair_start) else if (repairing) AppText.get(R.string.ui_back_up_and_repair) else if (reverting) AppText.get(R.string.ui_back_up_and_roll_back) else AppText.get(R.string.ui_back_up_and_upgrade)) } },
                dismissButton = { TextButton(onClick = { confirmRuntime = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        confirmLan?.let { (id, enabled) ->
            AlertDialog(onDismissRequest = { confirmLan = null }, title = { Text(AppText.get(R.string.ui_lan_access_for, if (enabled) AppText.get(R.string.ui_enable) else AppText.get(R.string.ui_close), id.label)) },
                text = { Text(if (enabled) AppText.get(R.string.ui_allow_other_devices_to_access_this_dashboard_through_the)
                    else AppText.get(R.string.ui_restrict_this_dashboard_to_the_phone_the_service_will)) },
                confirmButton = { TextButton(onClick = { confirmLan = null; command(if (enabled) "enable-lan" else "disable-lan", id) }) { Text(if (enabled) AppText.get(R.string.ui_enable_and_apply) else AppText.get(R.string.ui_disable_and_apply)) } },
                dismissButton = { TextButton(onClick = { confirmLan = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        if (confirmDiagnostics) AlertDialog(onDismissRequest = { confirmDiagnostics = false }, title = { Text(AppText.get(R.string.ui_export_diagnostics_2f10a)) },
            text = { Text(AppText.get(R.string.ui_includes_the_device_model_versions_runtime_status_and_redacted)) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { confirmDiagnostics = false; command("share-diagnostics") }) { Text(AppText.get(R.string.export_share)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { confirmDiagnostics = false; exportDiagnostics.launch("astrbot-diagnostics-${System.currentTimeMillis()}.zip") }) { Text(AppText.get(R.string.export_save)) } })
        if (showDataRetention) DataRetentionDialog(
            onPortableBackup = { showDataRetention = false; settingsRoute = SettingsRoute.PORTABLE },
            onDismiss = { showDataRetention = false })
        if (confirmPortableExport) PortableBackupExportDialog(onExport = { selection ->
            portableExportMask = selection.mask
            confirmPortableExport = false
            exportPortable.launch("astrbot-portable-${System.currentTimeMillis()}.zip")
        }, onShare = { selection ->
            confirmPortableExport = false
            RuntimeService.command(this@MainActivity, "share-backup", backupSelectionMask = selection.mask)
        }, onDismiss = { confirmPortableExport = false })
        if (clearAllLogs) AlertDialog(onDismissRequest = { clearAllLogs = false }, title = { Text(AppText.get(R.string.logs_clear_all)) },
            text = { Text(AppText.get(R.string.logs_clear_all_confirm)) },
            confirmButton = { TextButton(onClick = { clearAllLogs = false; command("clear-all-logs") }) { Text(AppText.get(R.string.logs_clear)) } },
            dismissButton = { TextButton(onClick = { clearAllLogs = false }) { Text(AppText.get(R.string.action_cancel)) } })
        if (confirmPortableImport) state.portableImport?.let { backup ->
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, AppText.locale).format(java.util.Date(backup.createdAt))
            AlertDialog(onDismissRequest = { confirmPortableImport = false }, title = { Text(AppText.get(R.string.ui_restore_this_portable_backup)) },
                text = { Text(AppText.get(R.string.backup_restore_summary, date, portableScopeLabel(backup.scopes), AppText.get(R.string.backup_cross_version_hint)),
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { confirmPortableImport = false; command("apply-backup") }) { Text(AppText.get(R.string.ui_back_up_and_import)) } },
                dismissButton = { TextButton(onClick = { confirmPortableImport = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        if (editBackupLimits) {
            var countText by remember { mutableStateOf(state.backupLimits.automaticCount.toString()) }
            var sizeText by remember { mutableStateOf((state.backupLimits.automaticBytes / 1024 / 1024).toString()) }
            val count = countText.toIntOrNull()
            val size = sizeText.toIntOrNull()
            val valid = count != null && count in 1..100 && size != null && size in 64..65536
            AlertDialog(onDismissRequest = { editBackupLimits = false }, title = { Text(AppText.get(R.string.ui_automatic_snapshot_limits)) }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppText.get(R.string.ui_when_creating_a_snapshot_before_an_update_or_restore))
                    OutlinedTextField(value = countText, onValueChange = { countText = it.take(4) }, label = { Text(AppText.get(R.string.ui_maximum_snapshots)) },
                        supportingText = { Text(AppText.get(R.string.ui_1_to_100_snapshots)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = sizeText, onValueChange = { sizeText = it.take(6) }, label = { Text(AppText.get(R.string.ui_storage_limit_mib)) },
                        supportingText = { Text(AppText.get(R.string.ui_64_to_65536_mib_1024_mib_1_gib)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(AppText.get(R.string.ui_manual_backups_are_excluded_and_are_never_deleted_automatically), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(enabled = valid && !state.busy, onClick = {
                editBackupLimits = false
                RuntimeService.command(this@MainActivity, "save-backup-limits", backupLimitCount = requireNotNull(count), backupLimitMiB = requireNotNull(size))
            }) { Text(AppText.get(R.string.ui_save)) } }, dismissButton = { TextButton(onClick = { editBackupLimits = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        if (showBackups) AlertDialog(onDismissRequest = { showBackups = false }, title = { Text(AppText.get(R.string.ui_local_backups)) }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val automatic = state.backups.filter { it.kind != "manual" }
                Text(AppText.get(R.string.ui_total_backup_size, Formatter.formatFileSize(this@MainActivity, state.backups.sumOf { it.storedBytes })))
                Text(AppText.get(R.string.ui_automatic_snapshots_mib, automatic.size, state.backupLimits.automaticCount, Formatter.formatFileSize(this@MainActivity, automatic.sumOf { it.storedBytes }), state.backupLimits.automaticBytes / 1024 / 1024),
                    style = MaterialTheme.typography.bodySmall)
                if (state.backups.isEmpty()) Text(AppText.get(R.string.ui_no_backups_yet))
                state.backups.forEach { backup ->
                    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, AppText.locale).format(java.util.Date(backup.createdAt))
                    Text(date, style = MaterialTheme.typography.titleSmall)
                    Text(AppText.get(R.string.ui_mb_entries, when (backup.kind) { "manual" -> AppText.get(R.string.ui_manual_backup); "before-restore" -> AppText.get(R.string.ui_pre_restore_snapshot); else -> AppText.get(R.string.ui_pre_update_snapshot) }, backup.bytes / 1024 / 1024, backup.count))
                    Text("AstrBot ${backup.versions["AstrBot"] ?: AppText.get(R.string.ui_unknown)}", style = MaterialTheme.typography.bodySmall)
                    if (backup.id in state.protectedBackupIds) Text(AppText.get(R.string.ui_required_for_rollback_protected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { restoreBackup = backup }, enabled = !state.busy) { Text(AppText.get(R.string.ui_restore)) }
                        TextButton(onClick = { deleteBackup = backup }, enabled = !state.busy && backup.id !in state.protectedBackupIds) { Text(AppText.get(R.string.ui_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { showBackups = false }) { Text(AppText.get(R.string.ui_close)) } })
        restoreBackup?.let { backup ->
            AlertDialog(onDismissRequest = { restoreBackup = null }, title = { Text(AppText.get(R.string.ui_restore_this_backup)) },
                text = { Text(AppText.get(R.string.ui_both_services_will_stop_briefly_current_data_is_backed)) },
                confirmButton = { TextButton(onClick = { restoreBackup = null; showBackups = false; command("restore", backupId = backup.id) }) { Text(AppText.get(R.string.ui_back_up_and_restore)) } },
                dismissButton = { TextButton(onClick = { restoreBackup = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        deleteBackup?.let { backup ->
            AlertDialog(onDismissRequest = { deleteBackup = null }, title = { Text(AppText.get(R.string.ui_delete_this_local_backup)) },
                text = { Text(AppText.get(R.string.ui_only_the_selected_backup_file_is_deleted_data_currently)) },
                confirmButton = { TextButton(onClick = { deleteBackup = null; command("delete-backup", backupId = backup.id) }) { Text(AppText.get(R.string.ui_delete)) } },
                dismissButton = { TextButton(onClick = { deleteBackup = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        val updateLabel = "AstrBot"
        if (showUpdateChannel) AlertDialog(onDismissRequest = { showUpdateChannel = false },
            title = { Text(AppText.get(R.string.upstream_channel_title)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppText.get(R.string.upstream_preview_notice))
                    for (preview in listOf(false, true)) ManagerActionButton(
                        AppText.get(if (preview) R.string.upstream_preview_channel else R.string.upstream_channel), {
                            showUpdateChannel = false
                            command("set-update-channel", updateId = if (preview) "preview" else "stable")
                        }, Modifier.fillMaxWidth(), if (preview == state.previewUpdates) ActionTone.PRIMARY else ActionTone.NEUTRAL, !state.busy)
                }
            }, confirmButton = { TextButton(onClick = { showUpdateChannel = false }) { Text(AppText.get(R.string.ui_close)) } })
        val stagedUpdate = state.preparedUpdate
        val onlineVersion = state.latestUpdate
        if (showUpdateImport) LocalUpdateImportDialog(
            pending = state.pendingUpdateArchive,
            ready = stagedUpdate.takeIf { importReady },
            progress = state.phase.takeIf { importRequest != null && state.busy },
            error = importMessage,
            busy = state.busy,
            onChoose = { importUpdate.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onClear = {
                importMessage = null
                command("clear-update-import")
            },
            onInstall = { showUpdateImport = false; command("apply-update") },
            onDismiss = { showUpdateImport = false })
        if (confirmUpdate) stagedUpdate?.let { update ->
            AlertDialog(onDismissRequest = { confirmUpdate = false }, title = { Text(AppText.get(R.string.ui_update_to, updateLabel, update.version)) },
                text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(update.channel)
                    ReleaseNotes(update.notes, baseUrl = "https://github.com/AstrBotDevs/AstrBot/")
                    Text(AppText.get(R.string.upstream_flow_hint) + AppText.get(R.string.ui_astrbot_starts_to_check_its_local_dashboard), style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = { confirmUpdate = false; command("apply-update") }) { Text(AppText.get(R.string.ui_back_up_and_update)) } },
                dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        LaunchedEffect(viewOnlineUpdate, state.busy, state.error) {
            if (viewOnlineUpdate && !state.busy && state.error != null) viewOnlineUpdate = false
        }
        val checkedComponent = requestedComponentCheck < state.componentCheckSerial
        if (viewOnlineUpdate && onlineVersion == null && checkedComponent && !state.busy && state.error == null) {
            AlertDialog(onDismissRequest = { viewOnlineUpdate = false }, title = { Text(AppText.get(R.string.ui_check_for_updates)) },
                text = { Text(state.notice.orEmpty()) }, confirmButton = { TextButton(onClick = { viewOnlineUpdate = false }) { Text(AppText.get(R.string.ui_close)) } })
        }
        if (viewOnlineUpdate && onlineVersion != null && checkedComponent && !state.busy && state.error == null) {
            val update = onlineVersion
            val canUpgrade = update.version != state.versions[updateLabel]
            AlertDialog(onDismissRequest = { viewOnlineUpdate = false }, title = { Text("$updateLabel ${update.version}") },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${update.channel} · ${if (update.bytes > 0) Formatter.formatFileSize(this@MainActivity, update.bytes) else AppText.get(R.string.upstream_size_unknown)}")
                    ReleaseNotes(update.notes, baseUrl = "https://github.com/AstrBotDevs/AstrBot/")
                    Text(if (canUpgrade) AppText.get(R.string.upstream_flow_hint) else
                        AppText.get(R.string.upstream_latest, updateLabel, update.version), style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { if (canUpgrade) TextButton(onClick = { viewOnlineUpdate = false; command("update-online", updateId = update.id) }) { Text(AppText.get(R.string.upstream_download_install)) } },
                dismissButton = { TextButton(onClick = { viewOnlineUpdate = false }) { Text(AppText.get(R.string.ui_close)) } })
        }
        if (confirmRollback) AlertDialog(onDismissRequest = { confirmRollback = false }, title = { Text(AppText.get(R.string.ui_roll_back_and_its_data, updateLabel)) },
            text = { Text(AppText.get(R.string.ui_back_up_current_data_then_restore_the_program_and)) },
            confirmButton = { TextButton(onClick = { confirmRollback = false; command("rollback") }) { Text(AppText.get(R.string.ui_back_up_and_roll_back_65fe8)) } },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text(AppText.get(R.string.ui_cancel)) } })
    }

    @Composable private fun CredentialControls(id: ServiceId) {
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        val label = AppText.get(R.string.ui_astrbot_initial_password)
        val owner = LocalLifecycleOwner.current
        var revealed by remember(id) { mutableStateOf<String?>(null) }
        var loading by remember(id) { mutableStateOf(false) }
        var request by remember(id) { mutableIntStateOf(0) }
        var confirmReset by remember(id) { mutableStateOf(false) }
        LaunchedEffect(manager.busy) { if (manager.busy) { request++; revealed = null; loading = false } }
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
                    if (value == null) Toast.makeText(this@MainActivity, AppText.get(R.string.ui_credentials_are_generated_at_first_startup_use_your_existing), Toast.LENGTH_SHORT).show()
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
            }, enabled = !loading && !manager.busy) {
                Icon(painterResource(if (revealed == null) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription = "${if (revealed == null) AppText.get(R.string.ui_show) else AppText.get(R.string.ui_hide)} $label")
            }
        }
        ManagerActionButton(AppText.get(R.string.ui_copy_f4880, label), { readCredential { copy(label, it, sensitive = true) } }, tone = ActionTone.NEUTRAL, enabled = !loading && !manager.busy)
        ManagerActionButton(AppText.get(R.string.credentials_reset_password), {
            request++; revealed = null; confirmReset = true
        }, tone = ActionTone.NEUTRAL, enabled = manager.componentsReady && !manager.busy && !loading)
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
            title = { Text(AppText.get(R.string.credentials_reset_password)) },
            text = { Text(AppText.get(R.string.credentials_reset_astrbot_notice)) },
            confirmButton = { TextButton(onClick = {
                confirmReset = false; request++; revealed = null
                RuntimeService.command(this@MainActivity, "reset-webui-password", id)
            }) { Text(AppText.get(R.string.credentials_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(AppText.get(R.string.ui_cancel)) } })
    }

    private fun copy(label: String, value: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, AppText.get(R.string.ui_copied, label), Toast.LENGTH_SHORT).show()
    }
    private fun external(url: String, localBackend: Boolean = true) {
        if (localBackend) Toast.makeText(this, AppText.get(R.string.ui_you_may_need_to_sign_in_separately_in_the), Toast.LENGTH_SHORT).show()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, AppText.get(R.string.ui_no_browser_available), Toast.LENGTH_SHORT).show() }
    }
}
