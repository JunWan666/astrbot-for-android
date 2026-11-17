package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireNotNullLocalized
import io.astrbot.android.LocalizedStateException
import io.astrbot.android.userFacingMessage

import io.astrbot.android.AppText
import io.astrbot.android.BuildConfig
import io.astrbot.android.R

import android.content.Context
import android.net.Uri
import io.astrbot.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class ManagerState(
    val platformObservationNonce: String? = null,
    val environmentInstalled: Boolean = false,
    val componentsReady: Boolean = false,
    val executing: Boolean = false,
    val pendingRequests: Set<String> = emptySet(),
    val phase: String = AppText.get(R.string.ui_checking_runtime),
    val error: String? = null,
    val problem: OperationProblem? = null,
    val services: Map<ServiceId, ServiceStatus> = ServiceId.entries.associateWith { ServiceStatus(it) },
    val webUis: Map<ServiceId, WebUiConnection> = ServiceId.entries.associateWith { WebUiConnection(LocalEndpoint(it.defaultPort)) },
    val maintenance: MaintenanceState = MaintenanceState.IDLE,
    val backups: List<BackupInfo> = emptyList(),
    val backupLimits: BackupLimits = BackupLimits(),
    val protectedBackupIds: Set<String> = emptySet(),
    val versions: Map<String, String> = emptyMap(),
    val versionNotice: String? = null,
    val versionsCheckedAtMillis: Long? = null,
    val notice: String? = null,
    val completedOperation: String? = null,
    val preparedUpdate: UpdateInfo? = null,
    val rollback: RollbackInfo? = null,
    val availableUpdate: OnlineUpdateInfo? = null,
    val latestUpdate: OnlineUpdateInfo? = null,
    val runtimeUpdate: RuntimeImageInfo? = null,
    val runtimeRollback: RuntimeImageInfo? = null,
    val portableImport: PortableBackupInfo? = null,
    val componentCheckSerial: Long = 0,
    val environmentCheck: EnvironmentCheckReport? = null,
    val sharedExport: String? = null,
    val storageSpace: StorageSpaceReading = StorageSpaceReading(),
) {
    val busy: Boolean get() = executing || pendingRequests.isNotEmpty()
}

enum class LogCategory(private val labelResource: Int) {
    MANAGER(R.string.ui_manager_7ad33), RUNTIME(R.string.ui_runtime), ASTRBOT(R.string.component_astrbot), CPOLAR(R.string.cpolar_name), DESKTOP(R.string.desktop_name), HARNESS(R.string.harness_name)
;
    val label: String get() = AppText.get(labelResource)
}

object RuntimeStore {
    val state = MutableStateFlow(ManagerState())
    internal fun dismissNotice(expected: String) {
        state.update { if (it.notice == expected) it.copy(notice = null) else it }
    }
    @Volatile internal var owner: Any? = null
    internal val operationLock = Mutex()
    private val logs = ConcurrentHashMap<String, RotatingLog>()
    fun beginRequest(requestId: String = java.util.UUID.randomUUID().toString()): String {
        state.update { it.copy(pendingRequests = it.pendingRequests + requestId) }
        return requestId
    }
    fun endRequest(requestId: String) {
        state.update { it.copy(pendingRequests = it.pendingRequests - requestId) }
    }
    suspend fun refreshInstallation(context: Context) = withContext(Dispatchers.IO) {
        LegacyIntegrationMigration.apply(context)
        val root = File(context.filesDir, "runtime-data/ubuntu")
        val installed = File(root, ".astrbot-bootstrap.json").isFile
        val ready = installed && File(root, "opt/astrbot/main.py").isFile &&
            File(root, "opt/astrbot-venv/pyvenv.cfg").isFile
        val inspection = if (installed) InstalledVersionInspection.read(root) else null
        val backups = BackupRepository(context.filesDir).list()
        val updates = UpdateRepository(context)
        val online = OnlineUpdateRepository(context)
        val availableUpdate = online.cachedCandidate()
        val latestUpdate = online.cachedLatest()
        val images = RuntimeImageRepository(context)
        val portableImport = PortableBackupRepository(context).preparedInfo
        val connections = ServiceId.entries.associateWith { RuntimeConfiguration(context).read(it) }
        val histories = ServiceId.entries.associateWith { ServiceHistoryRepository(context).read(it) }
        runCatching {
            val repository = CpolarRepository(context)
            CpolarStore.state.update { it.copy(configuration = repository.configuration(), desired = repository.desired()) }
        }.onFailure { CpolarStore.state.update { it.copy(error = AppText.get(R.string.cpolar_operation_error)) } }
        runCatching {
            val ssh = SshRepository(context)
            ssh.ensureDefault()
            SshStore.state.update { state -> state.copy(configuration = ssh.configuration(), desired = ssh.desired()) }
        }.onFailure { SshStore.state.update { it.copy(error = AppText.get(R.string.ui_ssh_extension_settings_are_unavailable_configure_ssh_again_or)) } }
        runCatching {
            val desktop = DesktopRepository(context)
            DesktopStore.state.update { it.copy(configuration = desktop.configuration(), desired = desktop.desired(),
                lanEnabled = desktop.lanEnabled(), lanPort = desktop.lanPort()) }
        }.onFailure { DesktopStore.state.update { it.copy(error = AppText.get(R.string.desktop_operation_failed)) } }
        runCatching { HarnessRepository(context).refresh() }.onFailure { HarnessStore.state.update { it.copy(error = AppText.get(R.string.harness_failed)) } }
        state.update { it.copy(environmentInstalled = installed, componentsReady = ready,
            services = it.services.mapValues { (id, status) -> if (owner == null) status.copy(history = histories.getValue(id)) else status },
            versions = inspection?.components.orEmpty(), versionNotice = inspection?.notice, versionsCheckedAtMillis = inspection?.checkedAtMillis,
            backups = backups, backupLimits = BackupCapacitySettings(context).read(),
            protectedBackupIds = updates.protectedBackupIds + images.protectedBackupIds,
            preparedUpdate = updates.preparedInfo, rollback = updates.rollbackInfo, availableUpdate = availableUpdate,
            runtimeUpdate = images.available, runtimeRollback = images.rollbackInfo,
            portableImport = portableImport, storageSpace = StorageSpace.read(context),
            webUis = it.webUis + ServiceId.entries.filter { id -> !it.services.getValue(id).desiredRunning }
                .associateWith { id -> connections.getValue(id) },
            phase = if (it.busy) it.phase else if (ready) AppText.get(R.string.ui_runtime_ready) else if (installed) AppText.get(R.string.ui_base_runtime_ready_complete_components_still_required) else AppText.get(R.string.ui_waiting_for_initialization)) }
    }
    fun log(context: Context, id: ServiceId): RotatingLog {
        return log(context, LogCategory.valueOf(id.name))
    }
    fun log(context: Context, category: LogCategory): RotatingLog {
        val directory = File(context.filesDir, "logs")
        return logs.computeIfAbsent(File(directory, category.name.lowercase()).path) { RotatingLog(directory, category.name.lowercase()) }
    }
}

/** Owned by RuntimeService. Activities and WebViews never own guest processes. */
class RuntimeController(private val context: Context, private val scope: CoroutineScope) {
    private val owner = Any().also { RuntimeStore.owner = it }
    private val runtime = BootstrapRuntime(context)
    private val ssh = SshEngine(context)
    private val terminal = TerminalEngine(context, scope)
    private val tasks = UserTaskEngine(context, scope)
    private val sshRepository = SshRepository(context)
    private val cpolar = CpolarEngine(context, scope)
    private val cpolarRepository = CpolarRepository(context)
    private val harness = HarnessEngine(context,scope)
    private val harnessRepository = HarnessRepository(context)
    private val desktop = DesktopEngine(context, scope)
    private val desktopRepository = DesktopRepository(context)
    private val configuration = RuntimeConfiguration(context)
    private val activeConnections = ConcurrentHashMap<ServiceId, WebUiConnection>()
    private val commands get() = RuntimeStore.operationLock
    private val backups = BackupRepository(context.filesDir, protectedIds = { updates.protectedBackupIds + images.protectedBackupIds }) { BackupCapacitySettings(context).read() }
    private val updates = UpdateRepository(context)
    private val images = RuntimeImageRepository(context)
    private val environmentRepairs = EnvironmentRepairRepository(context)
    private val portable = PortableBackupRepository(context)
    private val onlineUpdates = OnlineUpdateRepository(context)
    private val personas = PersonaRepository(context)
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()
    private val oneBotObservation = OneBotObservation(context)
    private val astrbotStartup = AstrBotStartup(context)
    private val versionWatcher = VersionInspectionWatcher(context.filesDir)
    private val logRetention = UpstreamLogRetention(context.filesDir)
    private var lastLogRetentionDeferred = 0
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    private val histories = ServiceHistoryRepository(context)
    private val supervisors = ServiceId.entries.associateWith { id ->
        ProcessSupervisor(id, scope, launcher = {
            val connection = configuration.read(id)
            activeConnections[id] = connection
            updateState { it.copy(webUis = it.webUis + (id to connection)) }
            val launchNonce = if (id == ServiceId.ASTRBOT) oneBotObservation.prepareLaunch() else null
            if (launchNonce != null) {
                updateState { it.copy(platformObservationNonce = launchNonce) }
                astrbotStartup.prepare(launchNonce)
                astrbotStartup.checkInstance(runtime)
            }
            runtime.processBuilder("/bin/sh", "-lc", launchCommand(id, launchNonce)).start()
        }, onLog = { RuntimeStore.log(context, id).append(it) }, expectedRestart = { exitCode ->
            id == ServiceId.ASTRBOT && astrbotStartup.consumeRestartRequest(exitCode)
        }, initialHistory = histories.read(id), terminalFailure = { exitCode ->
            if (id == ServiceId.ASTRBOT && exitCode == 73) AppText.get(R.string.astrbot_instance_busy) else null
        })
    }

    init {
        scope.launch(Dispatchers.IO) {
            GuestDns.changes(context).collect { GuestDns.refresh(context, runtime.rootfs) }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val visible = AppVisibility.visible.value
                withTimeoutOrNull(if (visible) 5000L else 30_000L) { AppVisibility.visible.first { it != visible } }
                if (commands.tryLock()) try {
                    if (RuntimeStore.owner === owner && RuntimeStore.state.value.environmentInstalled &&
                        !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair) {
                        versionWatcher.readIfChanged(android.os.SystemClock.elapsedRealtime())?.let { inspected ->
                            updateState { it.copy(versions = inspected.components, versionNotice = inspected.notice,
                                versionsCheckedAtMillis = inspected.checkedAtMillis) }
                        }
                    }
                } finally { commands.unlock() }
            }
        }
        scope.launch(Dispatchers.IO) {
            StorageSpaceMonitor({ StorageSpace.read(context) }).watch(
                publish = { space -> if (RuntimeStore.owner === owner) updateState { it.copy(storageSpace = space) } },
                event = { message -> if (RuntimeStore.owner === owner) event(message) })
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                commands.withLock {
                    if (RuntimeStore.owner === owner && !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair) retainLogs()
                }
            }
        }
        supervisors.forEach { (id, supervisor) ->
            scope.launch {
                var saved = histories.read(id)
                supervisor.state.collect { status ->
                    updateState { it.copy(services = it.services + (id to status)) }
                    if (status.history != saved && RuntimeStore.owner === owner) {
                        runCatching { histories.save(id, status.history) }
                            .onSuccess { saved = status.history }
                            .onFailure { RuntimeStore.log(context, LogCategory.MANAGER).append(AppText.get(R.string.ui_unable_to_save_history_check_storage_space, id.label)) }
                    }
                }
            }
            scope.launch(Dispatchers.IO) {
                var healthGeneration = -1L
                val interfaceHealth = InterfaceHealthPolicy()
                var observedLaunch: String? = null
                var completedAt: Long? = null
                var completedGeneration: Long? = null
                val polling = combine(supervisor.state, AppVisibility.visible,
                    RuntimeStore.state.map { it.maintenance }.distinctUntilChanged()) { status, visible, maintenance ->
                    HealthPollingInput(status.process, status.health, status.processGeneration, visible, maintenance)
                }.distinctUntilChanged()
                while (isActive) {
                    val input = polling.first()
                    val wait = HealthPollingPolicy.delayMillis(input, completedGeneration, completedAt, android.os.SystemClock.elapsedRealtime())
                    if (wait == null) {
                        polling.first { it.process == ProcessState.RUNNING }
                        continue
                    }
                    if (wait > 0) {
                        withTimeoutOrNull(wait) { polling.first { it != input } }
                        continue
                    }
                    val before = supervisor.state.value
                    if (before.process == ProcessState.RUNNING) {
                        if (healthGeneration != before.processGeneration) {
                            healthGeneration = before.processGeneration
                            interfaceHealth.reset(android.os.SystemClock.elapsedRealtime())
                            observedLaunch = null
                        }
                        if (id == ServiceId.ASTRBOT) astrbotStartup.launchId()?.let { launch ->
                            if (observedLaunch != null && observedLaunch != launch) {
                                interfaceHealth.reset(android.os.SystemClock.elapsedRealtime())
                                event(AppText.get(R.string.ui_astrbot_upstream_restart_detected_checking_local_endpoints_and_versions))
                            }
                            observedLaunch = launch
                        }
                        var connection = activeConnections[id] ?: configuration.read(id)
                        val configured = configuration.read(id)
                        var available = interfaceAvailable(id, connection.endpoint)
                        // Saving a port does not always move the listener until restart. Follow a changed
                        // endpoint only after the new real interface answers and the old one has stopped.
                        if (!available && configured != connection && interfaceAvailable(id, configured.endpoint)) {
                            connection = configured
                            activeConnections[id] = configured
                            interfaceHealth.reset(android.os.SystemClock.elapsedRealtime())
                            available = true
                        }
                        val displayed = if (connection != configured) connection.copy(note = AppText.get(R.string.ui_webui_configuration_changed_restart_this_service_to_apply_it)) else connection
                        updateState { it.copy(webUis = it.webUis + (id to displayed)) }
                        val health = interfaceHealth.observe(connection.enabled, available, android.os.SystemClock.elapsedRealtime())
                        supervisor.observe(before.processGeneration, health,
                            QqState.UNKNOWN, OneBotState.UNKNOWN, System.currentTimeMillis())
                        completedAt = android.os.SystemClock.elapsedRealtime()
                        completedGeneration = before.processGeneration
                    }
                }
            }
        }
    }

    suspend fun execute(action: String, id: ServiceId?, backupId: String? = null, sourceUri: String? = null, updateId: String? = null,
                        backupLimitCount: Int = 0, backupLimitMiB: Int = 0,
                        sshPort: Int = 8022, sshLan: Boolean = false, sshPassword: String? = null,
                        terminalSessionId: Long? = null, desktopLan: Boolean = false, desktopLanPort: Int = 6082, backupSelectionMask: Int = 1, logCategory: String? = null,
                        taskId: String? = null, taskName: String? = null, taskDirectory: String? = null, taskCommand: String? = null) = commands.withLock {
        val diagnosticSnapshot = RuntimeStore.state.value
        var resumeSsh = false
        var resumeBots = emptyList<ServiceId>()
        val readOnlyCheck = action in setOf("export-diagnostics", "share-diagnostics", "check-environment")
        val prepareDesktop = action == "desktop-install" || action in setOf("initialize", "desktop-start", "desktop-open", "desktop-qq-start", "desktop-qq-open", "desktop-qq-install", "desktop-lan-save") &&
            !desktopRepository.configuration().installed
        updateState { it.copy(executing = true, error = if (readOnlyCheck) diagnosticSnapshot.error else null,
            problem = if (readOnlyCheck) diagnosticSnapshot.currentProblem() else null, notice = null) }
        if (action.startsWith("ssh-")) SshStore.state.update { it.copy(busy = true, error = null) }
        if (action.startsWith("cpolar-")) CpolarStore.state.update { it.copy(busy = true, error = null) }
        if (action.startsWith("harness-")) HarnessStore.state.update { it.copy(busy = true,error = null,downloaded = 0,total = 0, installation = null) }
        if (action.startsWith("desktop-")) DesktopStore.state.update { it.copy(busy = true, error = null) }
        if (action.startsWith("task-")) UserTaskStore.state.update { it.copy(busy = true, error = null) }
        event(AppText.get(R.string.ui_start_1ae95, action, id?.label.orEmpty()))
        try {
            if (action == "check-environment") {
                updateState { it.copy(phase = AppText.get(R.string.ui_checking_runtime_resources_and_webui_configuration)) }
                val report = EnvironmentChecks(context).check()
                updateState { it.copy(environmentCheck = report, notice = AppText.get(R.string.ui_environment_check_complete_review_results_and_recommended_actions)) }
                return@withLock
            }
            if (action == "export-diagnostics" || action == "share-diagnostics") {
                val repository = DiagnosticsRepository(context)
                val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) } }
                if (action == "share-diagnostics") {
                    val file = repository.prepareShare(diagnosticSnapshot, progress)
                    updateState { it.copy(sharedExport = file.path) }
                } else {
                    repository.export(Uri.parse(requireNotNullLocalized(sourceUri) { AppText.get(R.string.ui_no_diagnostic_export_location_selected) }), diagnosticSnapshot, progress)
                }
                updateState { it.copy(notice = AppText.get(R.string.ui_diagnostics_exported_review_log_contents_before_sharing)) }
                return@withLock
            }
            if (action == "repair-runtime") {
                val report = EnvironmentChecks(context).check()
                updateState { it.copy(environmentCheck = report) }
                report.requireRepairable()
            }
            resumeSsh = prepareDesktop || action in setOf("backup", "backup-apk", "restore", "export-backup", "share-backup", "apply-backup", "apply-update", "rollback", "apply-runtime", "rollback-runtime", "repair-runtime") ||
                backups.hasPendingRestore || updates.hasPendingUpdate || images.hasPendingUpdate || environmentRepairs.hasPendingRepair
            if (resumeSsh) {
                pauseExternalServices()
            }
            if (prepareDesktop) {
                resumeBots = ServiceId.entries.filter { supervisors.getValue(it).state.value.desiredRunning }
                supervisors.values.toList().reversed().forEach { it.stop() }
            }
            if (backups.hasPendingRestore || updates.hasPendingUpdate || images.hasPendingUpdate || environmentRepairs.hasPendingRepair) supervisors.values.toList().reversed().forEach { it.stop() }
            withContext(Dispatchers.IO) { if (environmentRepairs.recover()) event(AppText.get(R.string.repair_recovered)) }
            images.recover()?.let { result -> updateState { it.copy(notice = result) } }
            updates.recover()?.let { result -> updateState { it.copy(notice = result) } }
            withContext(Dispatchers.IO) { backups.recover() }?.let { result -> updateState { it.copy(notice = result) } }
            withContext(Dispatchers.IO) { portable.cleanupInterruptedWork() }
            if (personas.hasPendingChange) {
                supervisors.getValue(ServiceId.ASTRBOT).stop()
                withContext(Dispatchers.IO) { personas.recover() }
                updateState { it.copy(notice = AppText.get(R.string.ui_restored_persona_settings_from_before_the_interruption)) }
            }
            if (prepareDesktop && action != "initialize") {
                install()
                if (action == "desktop-install" || !desktopRepository.configuration().installed) DesktopInstallStore.install(context, "desktop") {
                    desktopRepository.install { phase -> updateState { it.copy(phase = phase) } }
                }
                desktop.refresh()
            }
            when (action) {
                "reconcile" -> {
                    RuntimeStore.refreshInstallation(context)
                    if (RuntimeStore.state.value.componentsReady) {
                        val wanted = ServiceId.entries.filter { preferences.getBoolean("desired_${it.name}", false) }
                        if (wanted.isNotEmpty()) {
                            configuration.prepareDefaults()
                            for (serviceId in wanted) {
                                if (supervisors.getValue(serviceId).state.value.desiredRunning) continue
                                updateState { it.copy(phase = AppText.get(R.string.ui_checking_and_restoring, serviceId.label)) }
                                // The native owner cleans descendants on parent death. Allow that bounded
                                // cleanup to finish; never kill an unknown process occupying this port.
                                val endpoint = configuration.read(serviceId).endpoint
                                if (endpoint != null && serviceId != ServiceId.ASTRBOT) withTimeout(15_000) {
                                    while (runCatching { checkPortFree(endpoint) }.isFailure) delay(250)
                                }
                                supervisors.getValue(serviceId).start()
                                if (activeConnections[serviceId]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(serviceId)
                            }
                        }
                    }
                    ssh.refresh()
                    if (sshRepository.desired()) ssh.start()
                    cpolar.refresh()
                    if (cpolarRepository.desired()) runCatching { cpolar.start() }.onFailure {
                        CpolarStore.state.update { state -> state.copy(error = AppText.get(R.string.cpolar_start_error)) }
                    }
                }
                "harness-install" -> {
                    harness.stop()
                    harnessRepository.install { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(phase = AppText.get(R.string.harness_starting)) }
                    harness.start(); harnessRepository.setDesired(true); harnessRepository.refresh()
                }
                "harness-start" -> {
                    updateState { it.copy(phase = AppText.get(R.string.harness_starting)) }
                    harness.start(); harnessRepository.setDesired(true); harnessRepository.refresh()
                }
                "harness-stop" -> { harnessRepository.setDesired(false); harness.stop(); harnessRepository.refresh() }
                "harness-uninstall" -> { harness.stop(); harnessRepository.uninstall() }
                "harness-lan-enable", "harness-lan-disable" -> {
                    val lan = action == "harness-lan-enable"
                    checkLocalized(!lan || LanAccess.addresses(context).isNotEmpty()) { AppText.get(R.string.harness_no_lan) }
                    val resume = HarnessStore.state.value.active
                    harness.stop(); harnessRepository.setLan(lan)
                    if (resume) harness.start()
                }
                "cpolar-install" -> {
                    cpolarRepository.install { phase -> updateState { it.copy(phase = phase) } }
                    cpolar.start()
                    cpolarRepository.setDesired(true)
                    cpolar.refresh()
                }
                "cpolar-start" -> {
                    cpolar.start()
                    cpolarRepository.setDesired(true)
                    cpolar.refresh()
                }
                "cpolar-stop" -> {
                    cpolarRepository.setDesired(false)
                    cpolar.stop()
                    cpolar.refresh()
                }
                "cpolar-uninstall" -> {
                    cpolar.stop()
                    cpolarRepository.uninstall()
                    cpolar.refresh()
                }
                "desktop-install" -> Unit // Prepared above while other services are paused.
                "desktop-start", "desktop-open" -> { desktop.start(); desktopRepository.setDesired(true); desktop.refresh() }
                "desktop-lan-save" -> desktop.saveLan(desktopLan, desktopLanPort)
                "desktop-stop" -> {
                    desktopRepository.setDesired(false); desktopRepository.setQqDesired(false)

                    desktop.stop(); desktop.refresh()
                }
                "desktop-qq-install" -> {

                    desktop.stopQq()
                    desktopRepository.setQqDesired(false)
                    DesktopInstallStore.install(context,"qq") {
                        desktopRepository.installQq { phase -> updateState { it.copy(phase = phase) } }
                    }
                    desktop.refresh()
                }
                "desktop-qq-start", "desktop-qq-open" -> {
                    updateState { it.copy(phase = AppText.get(R.string.qq_starting)) }
                    checkLocalized(desktopRepository.configuration().qqInstalled) { AppText.get(R.string.desktop_qq_install_first) }
                    desktop.startQq()
                    desktopRepository.setDesired(true); desktopRepository.setQqDesired(true); desktop.refresh()
                }
                "desktop-qq-stop" -> { desktopRepository.setQqDesired(false); desktop.stopQq() }
                "desktop-qq-uninstall" -> { desktop.stopQq(); desktopRepository.uninstallQq(); desktop.refresh() }
                "reset-webui-password" -> resetWebUiPassword(requireNotNull(id))
                "desktop-uninstall" -> { desktop.stop(); desktopRepository.uninstall(); desktop.refresh() }
                "task-save" -> tasks.save(taskId, taskName.orEmpty(), taskDirectory.orEmpty(), taskCommand.orEmpty())
                "task-start" -> tasks.start(requireNotNull(taskId))
                "task-stop" -> tasks.stop(requireNotNull(taskId))
                "task-restart" -> { tasks.stop(requireNotNull(taskId)); tasks.start(taskId) }
                "task-delete" -> tasks.delete(requireNotNull(taskId))
                "task-clear-log" -> tasks.clearLog(requireNotNull(taskId))
                "task-stop-all" -> tasks.stopAll()
                "terminal-new" -> terminal.start(newSession = true)
                "terminal-start" -> terminal.start(terminalSessionId ?: TerminalStore.state.value.selectedId)
                "terminal-restart" -> {
                    val target = terminalSessionId ?: TerminalStore.state.value.selectedId
                    terminal.stopSession(target); terminal.start(target)
                }
                "terminal-close" -> terminal.stopSession(terminalSessionId, remove = true)
                "terminal-stop" -> terminal.stopSession(terminalSessionId ?: TerminalStore.state.value.selectedId)
                "terminal-stop-all" -> terminal.stop()
                "ssh-start" -> {
                    sshRepository.ensureDefault()
                    ssh.start()
                    sshRepository.setDesired(true)
                    ssh.refresh()
                }
                "ssh-stop" -> {
                    sshRepository.setDesired(false)
                    ssh.stop()
                    ssh.refresh()
                }
                "ssh-install" -> { sshRepository.install(); ssh.refresh() }
                "ssh-uninstall" -> { ssh.stop(); sshRepository.uninstall(); ssh.refresh() }
                "ssh-save" -> {
                    val wanted = sshRepository.desired()
                    // Validate and durably save before ending any current terminal session.
                    sshRepository.save(sshPort, sshLan, sshPassword)
                    ssh.stop()
                    ssh.refresh()
                    if (wanted) ssh.start()
                    updateState { it.copy(notice = AppText.get(R.string.ui_ssh_settings_saved) + if (wanted) AppText.get(R.string.ui_existing_sessions_disconnected_connect_again) else AppText.get(R.string.ui_applies_when_the_service_starts)) }
                }
                "initialize" -> {
                    install()
                    if (BuildConfig.BUNDLED_RUNTIME && !desktopRepository.configuration().installed && runtime.abi == "arm64-v8a") {
                        desktopRepository.install { phase -> updateState { it.copy(phase = phase) } }
                        desktop.refresh()
                    }
                }
                "start", "restart" -> {
                    install()
                    checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_this_apk_contains_only_the_base_runtime_complete_resources) }
                    configuration.prepareDefaults()
                    val ids = listOf(id ?: ServiceId.ASTRBOT)
                    if (action == "restart") ids.reversed().forEach { supervisors.getValue(it).stop() }
                    for (serviceId in ids) {
                        checkLocalized(preferences.edit().putBoolean("desired_${serviceId.name}", true).commit()) { AppText.get(R.string.ui_unable_to_save_runtime_preferences_check_storage_space) }
                        updateState { it.copy(phase = AppText.get(R.string.ui_starting_d784e, serviceId.label)) }
                        supervisors.getValue(serviceId).start()
                        // Keep the operation active until each requested local interface is ready.
                        if (activeConnections[serviceId]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(serviceId)
                    }
                }
                "stop" -> listOf(id ?: ServiceId.ASTRBOT).forEach { serviceId ->
                    checkLocalized(preferences.edit().putBoolean("desired_${serviceId.name}", false).commit()) { AppText.get(R.string.ui_unable_to_save_stop_preferences_check_storage_space) }
                    supervisors.getValue(serviceId).stop()
                }
                "enable-lan", "disable-lan" -> {
                    val serviceId = requireNotNullLocalized(id) { AppText.get(R.string.ui_select_a_webui_to_configure) }
                    checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
                    configuration.prepareDefaults()
                    val connection = configuration.read(serviceId)
                    checkLocalized(connection.enabled && connection.endpoint != null) { AppText.get(R.string.ui_enable_a_valid_webui_endpoint_in_project_configuration_first) }
                    val supervisor = supervisors.getValue(serviceId)
                    val wanted = supervisor.state.value.desiredRunning
                    updateState { it.copy(phase = AppText.get(R.string.ui_updating_lan_access, serviceId.label)) }
                    if (wanted) supervisor.stop()
                    try {
                        withContext(Dispatchers.IO) { configuration.setLanAccess(serviceId, action == "enable-lan") }
                    } catch (failure: Exception) {
                        if (failure !is CancellationException && wanted) runCatching { supervisor.start() }
                        throw failure
                    }
                    if (wanted) {
                        supervisor.start()
                        waitForInterface(serviceId)
                    }
                    updateState { it.copy(notice = AppText.get(R.string.ui_formatted_text, serviceId.label) +
                        (if (action == "enable-lan") AppText.get(R.string.ui_lan_access_enabled_bc976) else AppText.get(R.string.ui_access_restricted_to_this_device)) +
                        if (wanted) AppText.get(R.string.ui_local_endpoint_checked_again) else AppText.get(R.string.ui_applies_when_the_service_starts_884b1)) }
                }
                "cpolar-lan-on", "cpolar-lan-off" -> {
                    val old = cpolarRepository.configuration().lanEnabled
                    val running = CpolarStore.state.value.running
                    if (running) cpolar.stop()
                    try {
                        cpolarRepository.setLan(action == "cpolar-lan-on")
                        if (running) cpolar.start()
                    } catch (failure: Throwable) {
                        cpolarRepository.setLan(old)
                        if (running) runCatching { cpolar.start() }
                        throw failure
                    } finally { cpolar.refresh() }
                    updateState { it.copy(notice = AppText.get(R.string.cpolar_lan_saved)) }
                }
                "clear-logs", "clear-all-logs" -> {
                    withContext(Dispatchers.IO) {
                        val categories = if (action == "clear-all-logs") LogCategory.entries else listOf(LogCategory.valueOf(requireNotNull(logCategory)))
                        categories.forEach { RuntimeStore.log(context, it).clear() }
                        if (action == "clear-all-logs") {
                            val active = supervisors.filterValues { it.state.value.process != ProcessState.STOPPED }.keys.toMutableSet()
                            logRetention.enforce(active, clearHistory = true)
                        }
                    }
                    updateState { it.copy(notice = AppText.get(R.string.logs_cleared)) }
                }
                "backup", "backup-apk", "restore" -> maintain(action, backupId)
                "save-backup-limits" -> {
                    val limits = BackupLimits(backupLimitCount, backupLimitMiB.toLong() * 1024 * 1024)
                    withContext(Dispatchers.IO) { BackupCapacitySettings(context).save(limits) }
                    updateState { it.copy(notice = AppText.get(R.string.ui_automatic_snapshot_limits_saved_future_snapshots_will_remove_the)) }
                }
                "export-backup", "share-backup", "apply-backup" -> maintainPortable(action, sourceUri, BackupSelection.fromMask(backupSelectionMask))
                "prepare-backup" -> {
                    install()
                    checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
                    portable.prepareImport(Uri.parse(requireNotNullLocalized(sourceUri) { AppText.get(R.string.ui_no_portable_backup_selected) }), InstalledVersions.read(context)) { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(notice = AppText.get(R.string.ui_portable_backup_verified_but_not_restored_review_its_scope)) }
                }
                "discard-backup" -> {
                    withContext(Dispatchers.IO) { portable.discardImport() }
                    updateState { it.copy(notice = AppText.get(R.string.ui_pending_backup_import_cancelled_current_data_is_unchanged)) }
                }
                "prepare-update" -> updates.prepare(Uri.parse(requireNotNullLocalized(sourceUri) { AppText.get(R.string.ui_no_update_package_selected) })) { phase -> updateState { it.copy(phase = phase) } }
                "check-update" -> {
                    val result = onlineUpdates.check { phase -> updateState { it.copy(phase = phase) } }
                    runCatching { AutomaticUpdateSettings(context).succeeded(onlineUpdates.sourceScope, System.currentTimeMillis()) }
                    updateState { it.copy(availableUpdate = result.candidate, latestUpdate = result.latest, notice = result.message,
                        componentCheckSerial = it.componentCheckSerial + 1) }
                }
                "download-update", "update-online" -> {
                    onlineUpdates.download(requireNotNullLocalized(updateId) { AppText.get(R.string.ui_check_for_updates_first) }, updates) { phase -> updateState { it.copy(phase = phase) } }
                    if (action == "update-online") {
                        resumeSsh = true
                        pauseExternalServices()
                        updatePrograms(false)
                    } else updateState { it.copy(notice = AppText.get(R.string.ui_update_package_downloaded_and_verified_review_release_notes_before)) }
                }
                "apply-update", "rollback" -> updatePrograms(action == "rollback")
                "apply-runtime", "rollback-runtime" -> updateRuntimeImage(action == "rollback-runtime")
                "repair-runtime" -> {
                    updateRuntimeImage(rollback = false, repair = true)
                    val report = EnvironmentChecks(context).check()
                    updateState { it.copy(environmentCheck = report) }
                }
                "repair-environment" -> repairEnvironment()
                "delete-backup" -> {
                    checkLocalized(backupId !in updates.protectedBackupIds + images.protectedBackupIds) { AppText.get(R.string.ui_this_snapshot_is_required_for_program_or_runtime_rollback) }
                    withContext(Dispatchers.IO) { backups.delete(requireNotNullLocalized(backupId) { AppText.get(R.string.ui_no_backup_selected) }) }
                    updateState { it.copy(notice = AppText.get(R.string.ui_backup_deleted)) }
                }
                else -> error(AppText.get(R.string.ui_unknown_runtime_operation))
            }
        } catch (_: TimeoutCancellationException) {
            if (action.startsWith("desktop-")) DesktopStore.state.update { it.copy(error = AppText.get(R.string.desktop_operation_failed)) }
            val message = if (action == "apply-backup")
                AppText.get(R.string.ui_services_did_not_become_ready_after_import_recovery_of)
                else if (action == "repair-environment") AppText.get(R.string.repair_timeout)
                else if (action in setOf("apply-update", "rollback", "apply-runtime", "rollback-runtime", "repair-runtime", "update-online"))
                AppText.get(R.string.ui_the_new_version_did_not_become_ready_recovery_of)
                else AppText.get(R.string.ui_webui_readiness_timed_out_process_monitoring_continues_view_logs)
            val problem = OperationProblem.failed(action, RuntimeStore.state.value.phase, message, timeout = true)
            event(problem.json().toString())
            updateState { it.copy(error = problem.summary, problem = problem) }
        } catch (failure: DesktopInstallStopped) {
            updateState { it.copy(notice = AppText.get(R.string.desktop_transfer_stopped), phase = AppText.get(R.string.desktop_transfer_stopped)) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            if (action in setOf("check-update", "download-update", "update-online"))
                event(UpdateNetworkDiagnostics.record(action, failure))
            val message = if (action.startsWith("desktop-")) failure.userFacingMessage() ?: AppText.get(R.string.desktop_operation_failed)
            else if (action.startsWith("cpolar-")) failure.userFacingMessage() ?: AppText.get(R.string.cpolar_operation_error)
            else if (action.startsWith("harness-")) failure.userFacingMessage() ?: AppText.get(R.string.harness_failed)
            else if (action.startsWith("ssh-")) failure.userFacingMessage()
                ?: AppText.get(R.string.ui_ssh_operation_failed_check_the_port_runtime_and_free)
            else if (action.startsWith("task-")) failure.userFacingMessage() ?: AppText.get(R.string.tasks_operation_failed)
            else if (action == "repair-environment") failure.userFacingMessage() ?: if (failure is java.io.IOException)
                UpdateNetworkDiagnostics.message(failure) ?: AppText.get(R.string.repair_resource_failed) else AppText.get(R.string.repair_resource_failed)
            else if (action in setOf("export-diagnostics", "share-diagnostics")) AppText.get(R.string.ui_diagnostic_export_failed_check_free_space_or_choose_another)
            else if (action in setOf("check-update", "download-update", "update-online")) when (failure) {
                is java.io.IOException -> UpdateNetworkDiagnostics.message(failure) ?: AppText.get(R.string.ui_the_update_connection_was_interrupted_or_the_server_is)
                is org.json.JSONException, is java.security.GeneralSecurityException -> AppText.get(R.string.ui_cannot_verify_the_update_catalog_or_package_check_for)
                else -> failure.userFacingMessage()
                    ?: AppText.get(R.string.ui_update_information_failed_format_or_integrity_checks_check_for)
            } else if (action in setOf("prepare-backup", "export-backup", "share-backup", "apply-backup")) when (failure) {
                is java.io.IOException -> AppText.get(R.string.ui_the_backup_could_not_be_read_or_written_or)
                is org.json.JSONException -> AppText.get(R.string.ui_the_portable_backup_manifest_is_unrecognized_existing_data_was)
                else -> failure.userFacingMessage() ?: AppText.get(R.string.ui_portable_backup_verification_or_restore_failed_existing_data_and)
            } else failure.userFacingMessage() ?: AppText.get(R.string.problem_unknown_failure)
            event(AppText.get(R.string.ui_operation_failed_6846d, SecretRedactor.redact(message)))
            if (action.startsWith("ssh-")) SshStore.state.update { it.copy(error = message) }
            if (action.startsWith("cpolar-")) CpolarStore.state.update { it.copy(error = message) }
            if (action.startsWith("harness-")) HarnessStore.state.update { it.copy(error = message) }
            if (action.startsWith("terminal-")) TerminalStore.state.update { it.copy(error = message) }
            if (action.startsWith("task-")) UserTaskStore.state.update { it.copy(error = message) }
            if (action.startsWith("desktop-")) DesktopStore.state.update { it.copy(error = SecretRedactor.redact(message)) }
            val problem = (failure as? OperationProblemException)?.problem
                ?: OperationProblem.failed(action, RuntimeStore.state.value.phase, message)
            event("operation-problem " + SecretRedactor.redact(problem.json().toString()))
            // Record code locations and exception types without copying request URLs or response bodies into logs.
            event("operation-failure types=" + generateSequence<Throwable>(failure) { it.cause }.take(6)
                .joinToString(" -> ") { it.javaClass.name } + "\n" + failure.stackTrace.take(12).joinToString("\n"))
            updateState { it.copy(error = problem.summary, problem = problem) }
        } finally {
            withContext(NonCancellable) {
                for (serviceId in resumeBots.filter { preferences.getBoolean("desired_${it.name}", false) }) runCatching { supervisors.getValue(serviceId).start() }.onFailure {
                    event(AppText.get(R.string.desktop_resume_failed, serviceId.label))
                }
                if (resumeSsh && !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair && sshRepository.desired()) {
                    runCatching { ssh.start() }.onFailure {
                        SshStore.state.update { state -> state.copy(paused = false, error = AppText.get(R.string.ui_maintenance_finished_but_ssh_could_not_restart_check_the)) }
                    }
                }
                if ((resumeSsh || action == "reconcile") && !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair && cpolarRepository.desired()) {
                    runCatching { cpolar.start() }.onFailure {
                        CpolarStore.state.update { state -> state.copy(paused = false, error = AppText.get(R.string.cpolar_start_error)) }
                    }
                }
                if (action.startsWith("ssh-")) SshStore.state.update { it.copy(busy = false) }
                if (action.startsWith("cpolar-")) CpolarStore.state.update { it.copy(busy = false) }
                if ((resumeSsh || action == "reconcile") && !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair && harnessRepository.desired()) {
                    runCatching { harness.start() }.onFailure { HarnessStore.state.update { it.copy(error = AppText.get(R.string.harness_failed)) } }
                }
                if (action.startsWith("harness-")) { harnessRepository.refresh(); HarnessStore.state.update { it.copy(busy = false) } }
                if ((resumeSsh || action == "reconcile") && !backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair && desktopRepository.desired()) {
                    runCatching {
                        desktop.start()
                        if (desktopRepository.qqDesired()) desktop.startQq()
                    }.onFailure { DesktopStore.state.update { state -> state.copy(paused = false, error = AppText.get(R.string.desktop_operation_failed)) } }
                }
                if (action.startsWith("desktop-")) { desktop.refresh(); DesktopStore.state.update { it.copy(busy = false) } }
                if (action.startsWith("task-")) UserTaskStore.state.update { it.copy(busy = false) }
                if (!readOnlyCheck) {
                    if (!backups.hasPendingRestore && !updates.hasPendingUpdate && !images.hasPendingUpdate && !environmentRepairs.hasPendingRepair) retainLogs()
                    RuntimeStore.refreshInstallation(context)
                }
                updateState { it.copy(executing = false, phase = if (readOnlyCheck) diagnosticSnapshot.phase else it.phase) }
                event(AppText.get(R.string.ui_finished_072d2, action))
            }
        }
    }

    private suspend fun pauseExternalServices() {
        terminal.stop(if (TerminalStore.state.value.running) AppText.get(R.string.terminal_maintenance_closed) else null)
        tasks.stopAll(AppText.get(R.string.tasks_maintenance_stopped))
        ssh.stop(paused = sshRepository.desired())
        cpolar.stop(paused = cpolarRepository.desired())
        harness.stop()

        desktop.stop(paused = desktopRepository.desired())
    }

    private suspend fun maintainPortable(action: String, sourceUri: String?, selection: BackupSelection) {
        install()
        checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val sharing = action == "share-backup"
        val exporting = action == "export-backup" || sharing
        val destination = if (exporting && !sharing) Uri.parse(requireNotNullLocalized(sourceUri) { AppText.get(R.string.ui_no_backup_destination_selected) }) else null
        if (!exporting) {
            portable.verifyPrepared(versions)
            withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        val maintenance = if (exporting) MaintenanceState.BACKING_UP else MaintenanceState.RESTORING
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        updateState { it.copy(maintenance = maintenance) }
        try {
            supervisors.values.forEach { it.setMaintenance(maintenance) }
            progress(AppText.get(R.string.ui_stopping_services_to_prepare_a_consistent, if (exporting) AppText.get(R.string.ui_portable_backups) else AppText.get(R.string.ui_pre_restore_snapshot)))
            supervisors.values.toList().reversed().forEach { it.stop() }
            if (exporting) portable.prepareExport(versions, selection, progress)
            else {
                val snapshot = backups.create(versions, "before-restore")
                progress(AppText.get(R.string.ui_preserving_this_phone_s_qq_session_and_preparing_imported))
                portable.beginRestore(snapshot, versions)
                updates.checkDatabase()
                configuration.prepareDefaults()
            }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            resumeAfterMaintenance(wanted, validateStartup = !exporting)
            if (sharing) {
                val file = portable.shareExport(progress)
                updateState { it.copy(sharedExport = file.path) }
            } else if (exporting) portable.deliverExport(requireNotNull(destination), progress)
            else withContext(Dispatchers.IO) { backups.commitRestore(); portable.discardImport() }
            updateState { it.copy(notice = if (exporting)
                AppText.get(R.string.backup_export_complete)
                else AppText.get(R.string.backup_import_complete)) }
        } catch (failure: Throwable) {
            // A local readiness timeout leaves this command active; an explicit Stop cancels it.
            val resumeWanted = currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (backups.hasPendingRestore) {
                    supervisors.values.toList().reversed().forEach { it.stop() }
                    withContext(Dispatchers.IO) { backups.recover() }
                }
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                if (resumeWanted) runCatching { resumeAfterMaintenance(wanted) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                if (exporting) withContext(Dispatchers.IO) { portable.discardExport() }
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun updateRuntimeImage(rollback: Boolean, repair: Boolean = false) {
        if (repair) {
            val report = EnvironmentChecks(context).check()
            report.requireRepairable()
        } else {
            install()
            checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
        }
        withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        val restoreData = if (repair) { images.prepareRepair(progress); null } else images.prepare(rollback, progress)
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            progress(AppText.get(R.string.ui_stopping_both_services_and_creating_a_snapshot_before_switching))
            supervisors.values.toList().reversed().forEach { it.stop() }
            val snapshot = backups.create(versions, "before-update")
            progress(AppText.get(R.string.ui_switching_runtime_and_components))
            withContext(Dispatchers.IO) { images.begin(snapshot, versions, wanted, rollback, repair) }
            if (restoreData != null) backups.beginRestore(restoreData, InstalledVersions.read(context))
            updates.checkDatabase()
            configuration.prepareDefaults()
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            progress(AppText.get(R.string.runtime_check_selected_services))
            // Check the core and previously enabled optional services. Runtime
            // maintenance must not sign in to an account the user kept stopped.
            for (id in listOf(ServiceId.ASTRBOT) + wanted.filter { it != ServiceId.ASTRBOT }) {
                supervisors.getValue(id).start()
                val connection = activeConnections[id]
                if (connection?.let { it.enabled && it.endpoint != null } == true) waitForInterface(id, maintenanceCheck = true)
                else withTimeout(120_000) {
                    while (astrbotStartup.migration() != "passed") { checkMigration(); delay(250) }
                }
            }
            updates.checkDatabase()
            ServiceId.entries.filter { it !in wanted }.reversed().forEach { supervisors.getValue(it).stop() }
            withContext(Dispatchers.IO) { updates.commitRollbackData(); images.commit() }
            updateState { it.copy(notice = AppText.get(R.string.ui_runtime_completed_previous_running_states_restored_check_qq_login, if (repair) AppText.get(R.string.ui_repair) else if (rollback) AppText.get(R.string.ui_rollback) else AppText.get(R.string.ui_upgrade))) }
        } catch (failure: Throwable) {
            event(AppText.get(R.string.ui_runtime_maintenance_error, SecretRedactor.redact(failure.stackTraceToString()).take(8000)))
            if (images.hasCommittedUpdate) throw LocalizedStateException(AppText.get(R.string.ui_the_new_runtime_passed_checks_and_was_committed_old), failure)
            withContext(NonCancellable) {
                supervisors.values.toList().reversed().forEach { it.stop() }
                if (images.hasPendingUpdate) {
                    progress(AppText.get(R.string.ui_runtime_switch_did_not_complete_restoring_the_original_runtime))
                    runCatching { images.recover() }.onFailure {
                        throw LocalizedStateException(AppText.get(R.string.ui_original_runtime_recovery_is_incomplete_files_and_snapshots_are), it)
                    }
                }
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                if (failure !is CancellationException) runCatching { resumeAfterMaintenance(wanted) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun repairEnvironment() {
        val report = EnvironmentChecks(context).check()
        updateState { it.copy(environmentCheck = report) }
        if (report.repairTargets.isEmpty()) {
            updateState { it.copy(notice = AppText.get(R.string.repair_nothing_to_do)) }
            return
        }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        // All downloads and staging finish before interrupting services.
        val prepared = environmentRepairs.prepare(report.repairTargets, progress)
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            pauseExternalServices()
            supervisors.values.toList().reversed().forEach { it.stop() }
            withContext(Dispatchers.IO) { prepared.applyAndVerify(progress) }
            val after = EnvironmentChecks(context).check()
            val unresolved = after.items.count { it.status == EnvironmentCheckStatus.FAILED }
            event("environment-repair completed targets=${prepared.targets} files=${prepared.files.size} unresolved=$unresolved")
            updateState { it.copy(environmentCheck = after, notice = AppText.get(
                if (unresolved == 0) R.string.repair_completed else R.string.repair_partially_completed, prepared.files.size)) }
        } finally {
            withContext(NonCancellable) {
                if (!environmentRepairs.hasPendingRepair) {
                    withContext(Dispatchers.IO) { prepared.discard() }
                    supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                    // Start only services that were running before repair; never start an unrelated QQ account.
                    runCatching { resumeAfterMaintenance(wanted) }.onFailure { event(AppText.get(R.string.repair_service_resume_failed)) }
                    if (sshRepository.desired()) runCatching { ssh.start() }
                    if (cpolarRepository.desired()) runCatching { cpolar.start() }
                    if (desktopRepository.desired()) runCatching { desktop.start() }
                    if (HarnessStore.state.value.installed && harnessRepository.desired()) runCatching { harness.start() }
                }
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun updatePrograms(rollback: Boolean) {
        install()
        checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
        withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        val restoreData = if (rollback) updates.prepareRollback(progress) else { updates.verifyPrepared(progress); null }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            progress(AppText.get(R.string.ui_stopping_services_and_creating_a_pre_update_snapshot))
            supervisors.values.toList().reversed().forEach { it.stop() }
            val snapshot = backups.create(versions, "before-update")
            progress(AppText.get(R.string.ui_switching_program, if (rollback) AppText.get(R.string.ui_and_its_data) else ""))
            withContext(Dispatchers.IO) { updates.begin(snapshot, versions, wanted) }
            if (restoreData != null) updates.restoreRollbackData(restoreData)
            updates.checkDatabase()
            progress(AppText.get(R.string.ui_checking_the_new_program_and_its_dashboard))
            val verify = runtime.execute("/bin/sh", "-lc", "cd /opt/astrbot && /opt/astrbot-venv/bin/python main.py --help", timeoutSeconds = 90)
            if (verify.exitCode != 0) event(AppText.get(R.string.ui_new_program_check_exited_with, verify.exitCode, SecretRedactor.redact(verify.output.takeLast(3000))))
            checkLocalized(verify.exitCode == 0) { AppText.get(R.string.ui_new_program_startup_verification_failed) }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            // The changed program must really start even if the user previously kept it stopped.
            supervisors.getValue(ServiceId.ASTRBOT).start()
            if (activeConnections[ServiceId.ASTRBOT]?.enabled == true) waitForInterface(ServiceId.ASTRBOT, maintenanceCheck = true)
            else {
                withTimeout(120_000) {
                    while (astrbotStartup.migration() != "passed") {
                        checkMigration()
                        checkLocalized(supervisors.getValue(ServiceId.ASTRBOT).state.value.process == ProcessState.RUNNING) { AppText.get(R.string.ui_the_new_program_did_not_stay_running) }
                        delay(250)
                    }
                }
            }
            updates.checkDatabase()
            if (ServiceId.ASTRBOT !in wanted) supervisors.getValue(ServiceId.ASTRBOT).stop()
            resumeAfterMaintenance(wanted)
            progress(AppText.get(R.string.ui_committing_records, if (rollback) AppText.get(R.string.ui_rollback) else AppText.get(R.string.ui_update)))
            withContext(Dispatchers.IO) { updates.commitRollbackData(); updates.commit() }
            updateState { it.copy(notice = if (rollback) AppText.get(R.string.ui_restored_the_program_data_snapshot_and_previous_running_state) else AppText.get(R.string.ui_update_completed_the_previous_program_and_its_data_snapshot)) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                supervisors.values.toList().reversed().forEach { it.stop() }
                if (updates.hasPendingUpdate) {
                    progress(AppText.get(R.string.ui_update_did_not_complete_restoring_the_previous_program_and))
                    runCatching { updates.recover() }.onFailure { throw LocalizedStateException(AppText.get(R.string.ui_update_rollback_is_incomplete_files_and_records_are_preserved), it) }
                }
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                if (failure !is CancellationException) runCatching { resumeAfterMaintenance(wanted) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun resetWebUiPassword(id: ServiceId) {
        checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
        val supervisor = supervisors.getValue(id)
        val wanted = supervisor.state.value.desiredRunning
        updateState { it.copy(phase = AppText.get(R.string.credentials_resetting, id.label)) }
        supervisor.setMaintenance(MaintenanceState.RESTORING)
        var resetFailure: Throwable? = null
        try {
            supervisor.stop()
            withContext(Dispatchers.IO) { WebUiCredentials.reset(File(context.filesDir, "persistent"), id) }
        } catch (failure: Throwable) {
            resetFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                supervisor.setMaintenance(MaintenanceState.IDLE)
                if (wanted) try { supervisor.start() } catch (failure: Throwable) {
                    if (resetFailure != null) resetFailure.addSuppressed(failure) else throw failure
                }
            }
        }
        updateState { it.copy(notice = AppText.get(R.string.credentials_reset_done, id.label)) }
    }

    private suspend fun maintain(action: String, backupId: String?) {
        install()
        checkLocalized(RuntimeStore.state.value.componentsReady) { AppText.get(R.string.ui_initialize_the_complete_runtime_first) }
        if (action != "backup") withContext(Dispatchers.IO) { backups.checkAutomaticCapacity(preserveIds = setOfNotNull(backupId)) }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        val isBackup = action != "restore"
        val maintenance = if (isBackup) MaintenanceState.BACKING_UP else MaintenanceState.RESTORING
        updateState { it.copy(maintenance = maintenance, phase = AppText.get(R.string.ui_stopping_services_to_prepare_a_consistent_snapshot)) }
        try {
            supervisors.values.forEach { it.setMaintenance(maintenance) }
            supervisors.values.toList().reversed().forEach { it.stop() }
            updateState { it.copy(phase = if (isBackup) AppText.get(R.string.ui_copying_and_verifying_backup) else AppText.get(R.string.ui_backing_up_data_before_restore)) }
            backups.create(versions, when (action) { "backup" -> "manual"; "backup-apk" -> "before-update"; else -> "before-restore" }, preserveIds = setOfNotNull(backupId))
            if (action == "restore") {
                updateState { it.copy(phase = AppText.get(R.string.ui_verifying_backup_and_switching_data)) }
                backups.beginRestore(requireNotNullLocalized(backupId) { AppText.get(R.string.ui_no_backup_selected) }, versions)
                val check = runtime.execute("/opt/astrbot-venv/bin/python", "-c", """
                    import sqlite3
                    from pathlib import Path
                    path = Path('/persistent/astrbot/data/data_v4.db')
                    if path.is_file():
                        with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as connection:
                            assert connection.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'database check failed'
                """.trimIndent(), timeoutSeconds = 60)
                checkLocalized(check.exitCode == 0) { AppText.get(R.string.ui_restored_database_check_failed_restoring_original_data) }
            }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            resumeAfterMaintenance(wanted)
            if (action == "restore") withContext(Dispatchers.IO) { backups.commitRestore() }
            updateState { it.copy(notice = if (isBackup) AppText.get(R.string.ui_local_backup_completed_previous_running_states_restored) else AppText.get(R.string.ui_data_restored_and_verified_previous_running_states_restored)) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (backups.hasPendingRestore) {
                    supervisors.values.toList().reversed().forEach { it.stop() }
                    withContext(Dispatchers.IO) { backups.recover() }
                }
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                if (failure !is CancellationException) runCatching { resumeAfterMaintenance(wanted) }
                    .onFailure { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun resumeAfterMaintenance(wanted: List<ServiceId>, validateStartup: Boolean = false) {
        for (id in wanted) {
            updateState { it.copy(phase = AppText.get(R.string.ui_resuming, id.label)) }
            supervisors.getValue(id).start()
            if (activeConnections[id]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(id, maintenanceCheck = validateStartup)
            else if (validateStartup && id == ServiceId.ASTRBOT) withTimeout(120_000) {
                while (astrbotStartup.migration() != "passed") { checkMigration(); delay(500) }
            }
        }
    }

    private suspend fun install() {
        val fresh = !File(runtime.rootfs, ".astrbot-bootstrap.json").isFile
        runtime.install { phase ->
            RuntimeStore.log(context, LogCategory.RUNTIME).append("${java.time.Instant.now()} $phase")
            updateState { it.copy(phase = phase) }
        }
        if (fresh && BuildConfig.BUNDLED_RUNTIME && runtime.abi == "arm64-v8a" &&
            !desktopRepository.configuration().installed) {
            desktopRepository.install { phase -> updateState { it.copy(phase = phase) } }
            desktop.refresh()
        }
        RuntimeStore.refreshInstallation(context)
    }

    private fun event(message: String) = RuntimeStore.log(context, LogCategory.MANAGER).append("${java.time.Instant.now()} $message")

    private fun retainLogs() {
        runCatching {
            val active = supervisors.filterValues { it.state.value.let { state -> state.desiredRunning || state.process != ProcessState.STOPPED } }.keys
            val result = logRetention.enforce(active)
            if (result.deletedFiles > 0) event(AppText.get(R.string.ui_historical_log_cleanup_files_kib, result.deletedFiles, result.deletedBytes / 1024))
            if (result.deferredGroups > 0 && result.deferredGroups != lastLogRetentionDeferred)
                event(AppText.get(R.string.ui_some_logs_cannot_be_cleaned_yet_active_recently_written))
            lastLogRetentionDeferred = result.deferredGroups
        }.onFailure { event(AppText.get(R.string.ui_historical_log_check_did_not_complete_it_will_retry)) }
    }


    private fun launchCommand(id: ServiceId, nonce: String? = null) = when (id) {
        ServiceId.ASTRBOT -> "cd /opt/astrbot; export HOME=/persistent/home ASTRBOT_ROOT=/persistent/astrbot; " +
            "export ASTRBOT_MANAGER_OWNED_SERVICE=1; " +
            "export ASTRBOT_MANAGER_OBSERVATION_NONCE=${requireNotNull(nonce)}; " +
            "if test -f /persistent/manager/astrbot-initial-password; then " +
            "export ASTRBOT_DASHBOARD_INITIAL_PASSWORD=\"\$(cat /persistent/manager/astrbot-initial-password)\"; fi; " +
            "exec /opt/astrbot-venv/bin/python /manager/launch_astrbot.py --webui-dir /opt/astrbot/astrbot/dashboard/dist"
    }

    private suspend fun waitForInterface(id: ServiceId, maintenanceCheck: Boolean = false) {
        withTimeout(if (maintenanceCheck) 120_000 else 300_000) {
            while (supervisors.getValue(id).state.value.health != HealthState.AVAILABLE ||
                maintenanceCheck && id == ServiceId.ASTRBOT && astrbotStartup.migration() != "passed") {
                checkLocalized(supervisors.getValue(id).state.value.desiredRunning) { AppText.get(R.string.ui_service_startup_canceled) }
                if (maintenanceCheck) checkLocalized(supervisors.getValue(id).state.value.process != ProcessState.CRASHED) { AppText.get(R.string.ui_the_new_program_exited_unexpectedly_after_startup_rolling_back) }
                if (maintenanceCheck && id == ServiceId.ASTRBOT) checkMigration()
                delay(500)
            }
        }
    }

    private fun checkMigration() {
        checkLocalized(astrbotStartup.migration() != "failed") { AppText.get(R.string.ui_new_program_data_migration_failed_restoring_the_previous_program) }
        checkLocalized(astrbotStartup.migration() != "unsupported") { AppText.get(R.string.ui_this_migration_state_cannot_be_verified_yet_use_a) }
    }

    suspend fun close() = commands.withLock {
        terminal.close()
        tasks.stopAll(AppText.get(R.string.tasks_service_stopped))
        cpolar.close()
        harness.close()

        desktop.close()
        ssh.stop()
        supervisors.values.toList().reversed().forEach { it.close() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        onlineUpdates.close()
        updateState { it.copy(services = supervisors.mapValues { entry -> entry.value.state.value }) }
        if (RuntimeStore.owner === owner) RuntimeStore.owner = null
    }

    private fun interfaceAvailable(id: ServiceId, endpoint: LocalEndpoint?): Boolean = endpoint != null && runCatching {
        val path = "/"
        client.newCall(Request.Builder().url(endpoint.origin + path).build()).execute().use {
            it.isSuccessful && it.peekBody(4096).string().contains("<html", ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun checkPortFree(endpoint: LocalEndpoint) {
        try { ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port)) } }
        catch (_: java.io.IOException) { error(AppText.get(R.string.ui_local_port_is_in_use_stop_the_service_using, endpoint.port)) }
    }

    private fun updateState(transform: (ManagerState) -> ManagerState) {
        RuntimeStore.state.update { if (RuntimeStore.owner === owner) transform(it) else it }
    }
}
