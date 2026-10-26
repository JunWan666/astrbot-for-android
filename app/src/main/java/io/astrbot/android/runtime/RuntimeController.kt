package io.astrbot.android.runtime

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
    val environmentInstalled: Boolean = false,
    val componentsReady: Boolean = false,
    val busy: Boolean = false,
    val phase: String = "检查运行环境",
    val error: String? = null,
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
    val latestNapCat: OnlineUpdateInfo? = null,
    val preparedNapCat: UpdateInfo? = null,
    val napCatRollback: RollbackInfo? = null,
    val runtimeUpdate: RuntimeImageInfo? = null,
    val runtimeRollback: RuntimeImageInfo? = null,
    val portableImport: PortableBackupInfo? = null,
    val personas: PersonaInstallation = PersonaInstallation(),
    val environmentCheck: EnvironmentCheckReport? = null,
    val storageSpace: StorageSpaceReading = StorageSpaceReading(),
)

enum class LogCategory(val label: String) {
    MANAGER("管理器"), RUNTIME("运行环境"), ASTRBOT("AstrBot"), NAPCAT("NapCat / QQ")
}

object RuntimeStore {
    val state = MutableStateFlow(ManagerState())
    @Volatile internal var owner: Any? = null
    internal val operationLock = Mutex()
    private val logs = ConcurrentHashMap<String, RotatingLog>()
    suspend fun refreshInstallation(context: Context) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "runtime-data/ubuntu")
        val installed = File(root, ".astrbot-bootstrap.json").isFile
        val ready = installed && File(root, "opt/astrbot/main.py").isFile &&
            File(root, "opt/astrbot-venv/pyvenv.cfg").isFile && File(root, "opt/qq/.astrbot-components.json").isFile
        val inspection = if (installed) InstalledVersionInspection.read(root) else null
        val backups = BackupRepository(context.filesDir).list()
        val updates = UpdateRepository(context)
        val online = OnlineUpdateRepository(context)
        val availableUpdate = online.cachedCandidate()
        val latestUpdate = online.cachedLatest()
        val napcat = UpdateRepository(context, "napcat")
        val latestNapCat = OnlineUpdateRepository(context, io.astrbot.android.BuildConfig.NAPCAT_CATALOG_URL, component = "napcat").cachedLatest()
        val images = RuntimeImageRepository(context)
        val portableImport = PortableBackupRepository(context).preparedInfo
        val personas = PersonaRepository(context).inspect()
        val connections = ServiceId.entries.associateWith { RuntimeConfiguration(context).read(it) }
        val histories = ServiceId.entries.associateWith { ServiceHistoryRepository(context).read(it) }
        runCatching {
            val ssh = SshRepository(context)
            ssh.ensureDefault()
            SshStore.state.update { state -> state.copy(configuration = ssh.configuration(), desired = ssh.desired()) }
        }.onFailure { SshStore.state.update { it.copy(error = "SSH 扩展设置暂不可用，请重新设置或重装扩展") } }
        state.update { it.copy(environmentInstalled = installed, componentsReady = ready,
            services = it.services.mapValues { (id, status) -> if (owner == null) status.copy(history = histories.getValue(id)) else status },
            versions = inspection?.components.orEmpty(), versionNotice = inspection?.notice, versionsCheckedAtMillis = inspection?.checkedAtMillis,
            backups = backups, backupLimits = BackupCapacitySettings(context).read(),
            protectedBackupIds = updates.protectedBackupIds + napcat.protectedBackupIds + images.protectedBackupIds,
            preparedUpdate = updates.preparedInfo, rollback = updates.rollbackInfo, availableUpdate = availableUpdate,
            latestUpdate = latestUpdate, latestNapCat = latestNapCat,
            preparedNapCat = napcat.preparedInfo, napCatRollback = napcat.rollbackInfo,
            runtimeUpdate = images.available, runtimeRollback = images.rollbackInfo,
            portableImport = portableImport, personas = personas, storageSpace = StorageSpace.read(context),
            webUis = it.webUis + ServiceId.entries.filter { id -> !it.services.getValue(id).desiredRunning }
                .associateWith { id -> connections.getValue(id) },
            phase = if (it.busy) it.phase else if (ready) "运行环境已就绪" else if (installed) "基础环境已就绪，完整组件待准备" else "等待初始化") }
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
    private val sshRepository = SshRepository(context)
    private val configuration = RuntimeConfiguration(context)
    private val activeConnections = ConcurrentHashMap<ServiceId, WebUiConnection>()
    private val commands get() = RuntimeStore.operationLock
    private val backups = BackupRepository(context.filesDir, protectedIds = { updates.protectedBackupIds + napcat.protectedBackupIds + images.protectedBackupIds }) { BackupCapacitySettings(context).read() }
    private val updates = UpdateRepository(context)
    private val napcat = UpdateRepository(context, "napcat")
    private val images = RuntimeImageRepository(context)
    private val portable = PortableBackupRepository(context)
    private val onlineUpdates = OnlineUpdateRepository(context)
    private val onlineNapCat = OnlineUpdateRepository(context, io.astrbot.android.BuildConfig.NAPCAT_CATALOG_URL, component = "napcat")
    private val personas = PersonaRepository(context)
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).followRedirects(false).build()
    private var napCatHealth: NapCatHealth? = null
    private var napCatEndpoint: LocalEndpoint? = null
    private val oneBotObservation = OneBotObservation(context)
    private val astrbotStartup = AstrBotStartup(context)
    private val versionWatcher = VersionInspectionWatcher(context.filesDir)
    private val logRetention = UpstreamLogRetention(context.filesDir)
    private var lastLogRetentionDeferred = 0
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    private val histories = ServiceHistoryRepository(context)
    private val supervisors = ServiceId.entries.associateWith { id ->
        ProcessSupervisor(id, scope, launcher = {
            if (id == ServiceId.NAPCAT) NapCatCompatibility(context).prepare()
            val connection = configuration.read(id)
            connection.endpoint?.let { checkPortFree(it) }
            activeConnections[id] = connection
            updateState { it.copy(webUis = it.webUis + (id to connection)) }
            val launchNonce = if (id == ServiceId.ASTRBOT) oneBotObservation.prepareLaunch() else null
            if (launchNonce != null) astrbotStartup.prepare(launchNonce)
            runtime.processBuilder("/bin/sh", "-lc", launchCommand(id, launchNonce)).start()
        }, onLog = { RuntimeStore.log(context, id).append(it) }, expectedRestart = { exitCode ->
            id == ServiceId.ASTRBOT && astrbotStartup.consumeRestartRequest(exitCode)
        }, initialHistory = histories.read(id))
    }

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val visible = AppVisibility.visible.value
                withTimeoutOrNull(if (visible) 5000L else 30_000L) { AppVisibility.visible.first { it != visible } }
                if (commands.tryLock()) try {
                    if (RuntimeStore.owner === owner && RuntimeStore.state.value.environmentInstalled &&
                        !backups.hasPendingRestore && !updates.hasPendingUpdate && !napcat.hasPendingUpdate && !images.hasPendingUpdate) {
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
                    if (RuntimeStore.owner === owner && !backups.hasPendingRestore && !updates.hasPendingUpdate && !napcat.hasPendingUpdate && !images.hasPendingUpdate) retainLogs()
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
                            .onFailure { RuntimeStore.log(context, LogCategory.MANAGER).append("${id.label} 运行记录保存失败，请检查存储空间") }
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
                                event("检测到 AstrBot 上游重启，重新检查本机接口与版本")
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
                        val displayed = if (connection != configured) connection.copy(note = "后台配置已变更，重启此服务后生效") else connection
                        updateState { it.copy(webUis = it.webUis + (id to displayed)) }
                        val qq = if (available && id == ServiceId.NAPCAT) {
                            if (napCatEndpoint != connection.endpoint) {
                                napCatEndpoint = connection.endpoint
                                napCatHealth = connection.endpoint?.let { NapCatHealth(client, it) }
                            }
                            runCatching { napCatHealth?.query(readNapCatToken()) ?: QqObservation(QqState.UNKNOWN) }
                                .getOrDefault(QqObservation(QqState.UNKNOWN))
                        } else QqObservation(QqState.UNKNOWN)
                        val oneBot = if (id == ServiceId.NAPCAT &&
                            supervisors.getValue(ServiceId.ASTRBOT).state.value.process == ProcessState.RUNNING)
                            oneBotObservation.query(qq.account) else OneBotState.UNKNOWN
                        val health = interfaceHealth.observe(connection.enabled, available, android.os.SystemClock.elapsedRealtime())
                        supervisor.observe(before.processGeneration, health,
                            qq.state, oneBot, System.currentTimeMillis())
                        completedAt = android.os.SystemClock.elapsedRealtime()
                        completedGeneration = before.processGeneration
                    }
                }
            }
        }
    }

    suspend fun execute(action: String, id: ServiceId?, backupId: String? = null, sourceUri: String? = null, updateId: String? = null,
                        backupLimitCount: Int = 0, backupLimitMiB: Int = 0,
                        sshPort: Int = 8022, sshLan: Boolean = false, sshPassword: String? = null) = commands.withLock {
        val diagnosticSnapshot = RuntimeStore.state.value
        var resumeSsh = false
        val readOnlyCheck = action in setOf("export-diagnostics", "check-environment")
        updateState { it.copy(busy = true, error = if (readOnlyCheck) diagnosticSnapshot.error else null, notice = null) }
        if (action.startsWith("ssh-")) SshStore.state.update { it.copy(busy = true, error = null) }
        event("开始 $action ${id?.label.orEmpty()}")
        try {
            if (action == "check-environment") {
                updateState { it.copy(phase = "正在检查运行资源与后台配置") }
                val report = EnvironmentChecks(context).check()
                updateState { it.copy(environmentCheck = report, notice = "环境检查完成，可查看分类结果与处理建议") }
                return@withLock
            }
            if (action == "export-diagnostics") {
                DiagnosticsRepository(context).export(Uri.parse(requireNotNull(sourceUri) { "未选择诊断包保存位置" }), diagnosticSnapshot) { phase ->
                    updateState { it.copy(phase = phase) }
                }
                updateState { it.copy(notice = "诊断包已导出，请检查日志内容后再分享") }
                return@withLock
            }
            resumeSsh = action in setOf("backup", "backup-apk", "restore", "export-backup", "apply-backup", "apply-update", "rollback", "apply-runtime", "rollback-runtime", "repair-runtime", "apply-napcat", "rollback-napcat") ||
                backups.hasPendingRestore || updates.hasPendingUpdate || napcat.hasPendingUpdate || images.hasPendingUpdate
            if (resumeSsh) ssh.stop(paused = sshRepository.desired())
            if (backups.hasPendingRestore || updates.hasPendingUpdate || napcat.hasPendingUpdate || images.hasPendingUpdate) supervisors.values.toList().reversed().forEach { it.stop() }
            images.recover()?.let { result -> updateState { it.copy(notice = result) } }
            updates.recover()?.let { result -> updateState { it.copy(notice = result) } }
            napcat.recover()?.let { result -> updateState { it.copy(notice = result) } }
            withContext(Dispatchers.IO) { backups.recover() }?.let { result -> updateState { it.copy(notice = result) } }
            withContext(Dispatchers.IO) { portable.cleanupInterruptedWork() }
            if (personas.hasPendingChange) {
                supervisors.getValue(ServiceId.ASTRBOT).stop()
                withContext(Dispatchers.IO) { personas.recover() }
                updateState { it.copy(notice = "已恢复中断前的人设设置") }
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
                                updateState { it.copy(phase = "核对并恢复 ${serviceId.label} 运行") }
                                // The native owner cleans descendants on parent death. Allow that bounded
                                // cleanup to finish; never kill an unknown process occupying this port.
                                val endpoint = configuration.read(serviceId).endpoint
                                if (endpoint != null) withTimeout(15_000) {
                                    while (runCatching { checkPortFree(endpoint) }.isFailure) delay(250)
                                }
                                supervisors.getValue(serviceId).start()
                                if (activeConnections[serviceId]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(serviceId)
                            }
                        }
                    }
                    ssh.refresh()
                    if (sshRepository.desired()) ssh.start()
                }
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
                    updateState { it.copy(notice = "SSH 设置已保存" + if (wanted) "，原连接已断开，请重新连接" else "，启动后生效") }
                }
                "initialize" -> install()
                "persona-add", "persona-apply", "persona-undo" -> changePersona(action, updateId)
                "start", "restart" -> {
                    install()
                    check(RuntimeStore.state.value.componentsReady) { "当前安装包仅含基础环境，完整运行资源尚未准备好" }
                    configuration.prepareDefaults()
                    val ids = id?.let { listOf(it) } ?: ServiceId.entries
                    if (action == "restart") ids.reversed().forEach { supervisors.getValue(it).stop() }
                    for (serviceId in ids) {
                        check(preferences.edit().putBoolean("desired_${serviceId.name}", true).commit()) { "无法保存运行偏好，请检查存储空间" }
                        updateState { it.copy(phase = "正在启动 ${serviceId.label}") }
                        supervisors.getValue(serviceId).start()
                        // A full start waits for the actual local interface before the next service.
                        if (id == null && activeConnections[serviceId]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(serviceId)
                    }
                }
                "stop" -> (id?.let { listOf(it) } ?: ServiceId.entries.reversed()).forEach { serviceId ->
                    check(preferences.edit().putBoolean("desired_${serviceId.name}", false).commit()) { "无法保存停止偏好，请检查存储空间" }
                    supervisors.getValue(serviceId).stop()
                }
                "enable-lan", "disable-lan" -> {
                    val serviceId = requireNotNull(id) { "请选择需要调整的后台" }
                    check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
                    configuration.prepareDefaults()
                    val connection = configuration.read(serviceId)
                    check(connection.enabled && connection.endpoint != null) { "请先在项目配置中启用有效的 WebUI 地址" }
                    val supervisor = supervisors.getValue(serviceId)
                    val wanted = supervisor.state.value.desiredRunning
                    updateState { it.copy(phase = "正在调整 ${serviceId.label} 局域网访问") }
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
                    updateState { it.copy(notice = "${serviceId.label} 已" +
                        (if (action == "enable-lan") "开启局域网访问" else "限制为本机访问") +
                        if (wanted) "；已重新检查本机接口" else "；启动服务后生效") }
                }
                "backup", "backup-apk", "restore" -> maintain(action, backupId)
                "save-backup-limits" -> {
                    val limits = BackupLimits(backupLimitCount, backupLimitMiB.toLong() * 1024 * 1024)
                    withContext(Dispatchers.IO) { BackupCapacitySettings(context).save(limits) }
                    updateState { it.copy(notice = "自动快照上限已保存，下次新建自动快照时按上限清理最旧且未受保护的快照") }
                }
                "export-backup", "apply-backup" -> maintainPortable(action, sourceUri)
                "prepare-backup" -> {
                    install()
                    check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
                    portable.prepareImport(Uri.parse(requireNotNull(sourceUri) { "未选择便携备份" }), InstalledVersions.read(context)) { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(notice = "便携备份已校验，尚未恢复。请查看范围并确认导入") }
                }
                "discard-backup" -> {
                    withContext(Dispatchers.IO) { portable.discardImport() }
                    updateState { it.copy(notice = "已取消待导入备份，当前数据保持原样") }
                }
                "prepare-update" -> updates.prepare(Uri.parse(requireNotNull(sourceUri) { "未选择更新包" })) { phase -> updateState { it.copy(phase = phase) } }
                "check-update" -> {
                    val result = onlineUpdates.check { phase -> updateState { it.copy(phase = phase) } }
                    runCatching { AutomaticUpdateSettings(context).succeeded(onlineUpdates.sourceScope, System.currentTimeMillis()) }
                    updateState { it.copy(availableUpdate = result.candidate, latestUpdate = result.latest, notice = result.message) }
                }
                "download-update" -> {
                    onlineUpdates.download(requireNotNull(updateId) { "请先检查更新" }, updates) { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(notice = "更新包已下载并校验，确认版本说明后再安装") }
                }
                "check-napcat" -> {
                    val result = onlineNapCat.check { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(latestNapCat = result.latest, notice = result.message) }
                }
                "download-napcat" -> {
                    onlineNapCat.download(requireNotNull(updateId) { "请先检查更新" }, napcat) { phase -> updateState { it.copy(phase = phase) } }
                    updateState { it.copy(notice = "NapCat 更新包已下载并校验，可确认备份和安装") }
                }
                "apply-napcat", "rollback-napcat" -> updateNapCat(action == "rollback-napcat")
                "apply-update", "rollback" -> updatePrograms(action == "rollback")
                "apply-runtime", "rollback-runtime" -> updateRuntimeImage(action == "rollback-runtime")
                "repair-runtime" -> {
                    updateRuntimeImage(rollback = false, repair = true)
                    val report = EnvironmentChecks(context).check()
                    updateState { it.copy(environmentCheck = report) }
                }
                "delete-backup" -> {
                    check(backupId !in updates.protectedBackupIds + napcat.protectedBackupIds + images.protectedBackupIds) { "此快照用于程序或环境回退，请保留；后续成功更新后会解除保护" }
                    withContext(Dispatchers.IO) { backups.delete(requireNotNull(backupId) { "未选择备份" }) }
                    updateState { it.copy(notice = "备份已删除") }
                }
                else -> error("未知运行操作")
            }
        } catch (_: TimeoutCancellationException) {
            updateState { it.copy(error = if (action == "apply-backup")
                "导入后的服务就绪检查超时，已尝试恢复操作前数据和原运行状态；请查看管理日志及服务状态"
                else if (action in setOf("apply-update", "rollback", "apply-runtime", "rollback-runtime", "repair-runtime", "apply-napcat", "rollback-napcat"))
                "新版本就绪检查超时，已尝试恢复旧程序与对应数据；请查看管理日志及服务状态"
                else "等待后台就绪超时，进程状态会继续检查；可查看日志、继续等待或停止服务") }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val message = if (action.startsWith("ssh-")) failure.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } }
                ?: "SSH 操作失败，请检查端口是否被占用、运行环境及可用存储后重试"
            else if (action == "export-diagnostics") "诊断包导出失败，请检查可用空间或重新选择保存位置；现有运行环境与数据已保留"
            else if (action in setOf("check-update", "download-update", "check-napcat", "download-napcat")) when (failure) {
                is java.io.IOException -> "更新连接中断或服务器不可用，已保留当前程序和下载进度，可稍后重试"
                is org.json.JSONException, is java.security.GeneralSecurityException -> "无法验证更新目录或更新包，请重新检查更新"
                else -> failure.message?.takeIf { it.any { char -> char in '\u4e00'..'\u9fff' } }
                    ?: "更新信息格式或完整性检查失败，请重新检查更新"
            } else if (action in setOf("prepare-backup", "export-backup", "apply-backup")) when (failure) {
                is java.io.IOException -> "备份文件读写失败或被截断，请重新选择文件后重试；现有数据已保留"
                is org.json.JSONException -> "便携备份清单无法识别，未修改现有数据"
                else -> failure.message?.takeIf { it.any { char -> char in '\u4e00'..'\u9fff' } } ?: "便携备份校验或恢复失败，现有数据与恢复记录已保留"
            } else failure.message ?: "操作失败"
            event("操作失败：${SecretRedactor.redact(message)}")
            if (action.startsWith("ssh-")) SshStore.state.update { it.copy(error = message) }
            updateState { it.copy(error = SecretRedactor.redact(message)) }
        } finally {
            withContext(NonCancellable) {
                if (resumeSsh && !backups.hasPendingRestore && !updates.hasPendingUpdate && !napcat.hasPendingUpdate && !images.hasPendingUpdate && sshRepository.desired()) {
                    runCatching { ssh.start() }.onFailure {
                        SshStore.state.update { state -> state.copy(paused = false, error = "维护已结束，但 SSH 恢复失败，请检查端口后重试") }
                    }
                }
                if (action.startsWith("ssh-")) SshStore.state.update { it.copy(busy = false) }
                if (!readOnlyCheck) {
                    if (!backups.hasPendingRestore && !updates.hasPendingUpdate && !napcat.hasPendingUpdate && !images.hasPendingUpdate) retainLogs()
                    RuntimeStore.refreshInstallation(context)
                }
                updateState { it.copy(busy = false, phase = if (readOnlyCheck) diagnosticSnapshot.phase else it.phase) }
                event("结束 $action")
            }
        }
    }

    private suspend fun maintainPortable(action: String, sourceUri: String?) {
        install()
        check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val exporting = action == "export-backup"
        val destination = if (exporting) Uri.parse(requireNotNull(sourceUri) { "未选择备份保存位置" }) else null
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
            progress("正在停止服务，准备一致性${if (exporting) "便携备份" else "恢复前快照"}")
            supervisors.values.toList().reversed().forEach { it.stop() }
            if (exporting) portable.prepareExport(versions, progress)
            else {
                val snapshot = backups.create(versions, "before-restore")
                progress("正在保留当前手机 QQ 会话并准备导入数据")
                portable.beginRestore(snapshot, versions)
                updates.checkDatabase()
                configuration.prepareDefaults()
            }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            resumeAfterMaintenance(wanted, validateStartup = !exporting)
            if (exporting) portable.deliverExport(requireNotNull(destination), progress)
            else withContext(Dispatchers.IO) { backups.commitRestore(); portable.discardImport() }
            updateState { it.copy(notice = if (exporting)
                "便携备份已导出，不包含 QQ 登录会话；包含业务配置及密钥，请妥善保管"
                else "便携备份已恢复，已保留本机 QQ 会话及恢复前快照；请使用备份中的后台凭据核对业务配置") }
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
            check(report.canRepair) { report.repairExplanation }
        } else {
            install()
            check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
        }
        withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        val restoreData = if (repair) { images.prepareRepair(progress); null } else images.prepare(rollback, progress)
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            progress("正在停止两个服务并创建环境切换前快照")
            supervisors.values.toList().reversed().forEach { it.stop() }
            val snapshot = backups.create(versions, "before-update")
            progress("正在切换运行环境及组件")
            withContext(Dispatchers.IO) { images.begin(snapshot, versions, wanted, rollback, repair) }
            if (restoreData != null) backups.beginRestore(restoreData, InstalledVersions.read(context))
            updates.checkDatabase()
            configuration.prepareDefaults()
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            progress("正在验证实际 AstrBot 与 NapCat 服务")
            // Both projects must launch, including when they were previously stopped.
            for (id in ServiceId.entries) {
                supervisors.getValue(id).start()
                val connection = activeConnections[id]
                if (connection?.let { it.enabled && it.endpoint != null } == true) waitForInterface(id, maintenanceCheck = true)
                else if (id == ServiceId.ASTRBOT) withTimeout(120_000) {
                    while (astrbotStartup.migration() != "passed") { checkMigration(); delay(250) }
                } else {
                    delay(5000)
                    check(supervisors.getValue(id).state.value.process == ProcessState.RUNNING) { "NapCat 启动检查失败" }
                }
            }
            updates.checkDatabase()
            ServiceId.entries.filter { it !in wanted }.reversed().forEach { supervisors.getValue(it).stop() }
            withContext(Dispatchers.IO) { updates.commitRollbackData(); images.commit() }
            updateState { it.copy(notice = "运行环境${if (repair) "修复" else if (rollback) "回退" else "升级"}完成，已恢复原运行状态；QQ 登录与业务连接请在后台确认") }
        } catch (failure: Throwable) {
            event("环境维护异常：${SecretRedactor.redact(failure.stackTraceToString()).take(8000)}")
            if (images.hasCommittedUpdate) throw IllegalStateException("新环境已通过检查并提交，旧文件清理未完成。记录已保留，重新打开管理器可重试清理", failure)
            withContext(NonCancellable) {
                supervisors.values.toList().reversed().forEach { it.stop() }
                if (images.hasPendingUpdate) {
                    progress("环境切换未完成，正在恢复原环境与数据")
                    runCatching { images.recover() }.onFailure {
                        throw IllegalStateException("原环境恢复尚未完成，已保留文件与快照，请查看管理日志后重试", it)
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

    private suspend fun updateNapCat(rollback: Boolean) {
        check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
        withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        val restoreData = if (rollback) napcat.prepareRollback(progress) else { napcat.verifyPrepared(progress); null }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            progress("正在停止服务并创建 NapCat 更新前快照")
            supervisors.values.toList().reversed().forEach { it.stop() }
            val snapshot = backups.create(versions, "before-update")
            if (!rollback) napcat.preserveUserPlugins()
            withContext(Dispatchers.IO) { napcat.begin(snapshot, versions, wanted) }
            if (restoreData != null) napcat.restoreRollbackData(restoreData)
            progress("正在检查 NapCat 资源、QQ 运行依赖与现有数据")
            // QQ disables Electron's RunAsNode mode. Never launch it just to inspect a stopped
            // account. ldd inspects the loader/dependencies; no NapCat module is imported here.
            val result = runtime.execute("/opt/astrbot-venv/bin/python", "-c", """
                import json, subprocess
                from pathlib import Path
                app = Path('/opt/qq/resources/app')
                assert json.loads((app / 'package.json').read_text())['main'] == './loadNapCat.js'
                assert 'napcat.mjs' in (app / 'loadNapCat.js').read_text()
                program = app / 'app_launcher/napcat'
                assert (program / 'napcat.mjs').is_file() and (program / 'static/index.html').is_file()
                assert (program / 'node_modules').is_dir() and (program / 'native').is_dir()
                result = subprocess.run(['ldd', '/opt/qq/qq'], capture_output=True, text=True, timeout=45)
                assert result.returncode == 0 and 'not found' not in result.stdout + result.stderr, 'QQ dependencies missing'
            """.trimIndent(), timeoutSeconds = 60)
            if (result.exitCode != 0) event("NapCat 资源检查退出 ${result.exitCode}：${SecretRedactor.redact(result.output.takeLast(2000))}")
            check(result.exitCode == 0) { "NapCat 资源或 QQ 运行依赖不兼容，正在恢复原版本" }
            updates.checkDatabase()
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            // Only services that the user had running may restart. A stopped QQ stays stopped.
            resumeAfterMaintenance(wanted, validateStartup = true)
            withContext(Dispatchers.IO) { napcat.commitRollbackData(); napcat.commit() }
            updateState { it.copy(notice = if (ServiceId.NAPCAT in wanted)
                "NapCat ${if (rollback) "回退" else "更新"}完成，配置与 QQ 会话已保留，后台接口已恢复"
                else "NapCat ${if (rollback) "回退" else "更新"}完成，资源与依赖检查通过；QQ 保持停止，启动后可检查后台") }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                supervisors.values.toList().reversed().forEach { it.stop() }
                if (napcat.hasPendingUpdate) runCatching { napcat.recover() }.onFailure {
                    throw IllegalStateException("NapCat 恢复未完成，程序与数据快照已保留，请查看管理日志后重试", it)
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

    private suspend fun updatePrograms(rollback: Boolean) {
        install()
        check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
        withContext(Dispatchers.IO) { backups.checkAutomaticCapacity() }
        val progress: (String) -> Unit = { phase -> updateState { it.copy(phase = phase) }; event(phase) }
        val restoreData = if (rollback) updates.prepareRollback(progress) else { updates.verifyPrepared(progress); null }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        updateState { it.copy(maintenance = MaintenanceState.UPDATING) }
        try {
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.UPDATING) }
            progress("正在停止服务并创建更新前快照")
            supervisors.values.toList().reversed().forEach { it.stop() }
            val snapshot = backups.create(versions, "before-update")
            progress("正在切换程序${if (rollback) "及对应数据" else ""}")
            withContext(Dispatchers.IO) { updates.begin(snapshot, versions, wanted) }
            if (restoreData != null) updates.restoreRollbackData(restoreData)
            updates.checkDatabase()
            progress("正在检查新程序与实际后台")
            val verify = runtime.execute("/bin/sh", "-lc", "cd /opt/astrbot && /opt/astrbot-venv/bin/python main.py --help", timeoutSeconds = 90)
            if (verify.exitCode != 0) event("新程序检查退出 ${verify.exitCode}：${SecretRedactor.redact(verify.output.takeLast(3000))}")
            check(verify.exitCode == 0) { "新程序启动验证失败" }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            // The changed program must really start even if the user previously kept it stopped.
            supervisors.getValue(ServiceId.ASTRBOT).start()
            if (activeConnections[ServiceId.ASTRBOT]?.enabled == true) waitForInterface(ServiceId.ASTRBOT, maintenanceCheck = true)
            else {
                withTimeout(120_000) {
                    while (astrbotStartup.migration() != "passed") {
                        checkMigration()
                        check(supervisors.getValue(ServiceId.ASTRBOT).state.value.process == ProcessState.RUNNING) { "新程序未保持运行" }
                        delay(250)
                    }
                }
            }
            updates.checkDatabase()
            if (ServiceId.ASTRBOT !in wanted) supervisors.getValue(ServiceId.ASTRBOT).stop()
            resumeAfterMaintenance(wanted)
            progress("正在提交${if (rollback) "回退" else "更新"}记录")
            withContext(Dispatchers.IO) { updates.commitRollbackData(); updates.commit() }
            updateState { it.copy(notice = if (rollback) "已恢复对应程序和数据快照，并恢复原运行状态" else "更新完成，已保留旧程序与对应数据快照；QQ 与业务连接请在后台确认") }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                supervisors.values.toList().reversed().forEach { it.stop() }
                if (updates.hasPendingUpdate) {
                    progress("更新未完成，正在恢复旧程序及对应数据")
                    runCatching { updates.recover() }.onFailure { throw IllegalStateException("更新回退未完成，文件与记录已保留，请查看管理日志后重试", it) }
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

    private suspend fun maintain(action: String, backupId: String?) {
        install()
        check(RuntimeStore.state.value.componentsReady) { "请先完成完整运行环境初始化" }
        if (action != "backup") withContext(Dispatchers.IO) { backups.checkAutomaticCapacity(preserveIds = setOfNotNull(backupId)) }
        val versions = withContext(Dispatchers.IO) { InstalledVersions.read(context) }
        val wanted = supervisors.filterValues { it.state.value.desiredRunning }.keys.toList()
        val isBackup = action != "restore"
        val maintenance = if (isBackup) MaintenanceState.BACKING_UP else MaintenanceState.RESTORING
        updateState { it.copy(maintenance = maintenance, phase = "正在停止服务，准备一致性快照") }
        try {
            supervisors.values.forEach { it.setMaintenance(maintenance) }
            supervisors.values.toList().reversed().forEach { it.stop() }
            updateState { it.copy(phase = if (isBackup) "正在复制并校验备份" else "正在备份恢复前的数据") }
            backups.create(versions, when (action) { "backup" -> "manual"; "backup-apk" -> "before-update"; else -> "before-restore" }, preserveIds = setOfNotNull(backupId))
            if (action == "restore") {
                updateState { it.copy(phase = "校验备份并切换数据") }
                backups.beginRestore(requireNotNull(backupId) { "未选择备份" }, versions)
                val check = runtime.execute("/opt/astrbot-venv/bin/python", "-c", """
                    import sqlite3
                    from pathlib import Path
                    path = Path('/persistent/astrbot/data/data_v4.db')
                    if path.is_file():
                        with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as connection:
                            assert connection.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'database check failed'
                """.trimIndent(), timeoutSeconds = 60)
                check(check.exitCode == 0) { "恢复后的数据库检查失败，正在恢复原数据" }
            }
            supervisors.values.forEach { it.setMaintenance(MaintenanceState.IDLE) }
            resumeAfterMaintenance(wanted)
            if (action == "restore") withContext(Dispatchers.IO) { backups.commitRestore() }
            updateState { it.copy(notice = if (isBackup) "本机备份已完成，已恢复原先的运行状态" else "数据已恢复并校验，已恢复原先的运行状态") }
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

    private suspend fun changePersona(action: String, presetId: String?) {
        check(RuntimeStore.state.value.componentsReady) { "请先完成运行环境初始化" }
        val preset = if (action == "persona-undo") null else PersonaLibrary.read(context).firstOrNull { it.id == presetId }
            ?: error("未找到此人设预设")
        val supervisor = supervisors.getValue(ServiceId.ASTRBOT)
        val wanted = supervisor.state.value.desiredRunning
        updateState { it.copy(maintenance = MaintenanceState.RESTORING, phase = "正在保存 AstrBot 人设设置") }
        try {
            supervisor.setMaintenance(MaintenanceState.RESTORING)
            supervisor.stop()
            withContext(Dispatchers.IO) { personas.begin(preset, action == "persona-apply", action == "persona-undo") }
            supervisor.setMaintenance(MaintenanceState.IDLE)
            if (wanted) resumeAfterMaintenance(listOf(ServiceId.ASTRBOT), validateStartup = true)
            withContext(Dispatchers.IO) { personas.commit() }
            updateState { it.copy(notice = when (action) {
                "persona-add" -> "人设已添加到 AstrBot，原有同名人设和后台修改已保留"
                "persona-undo" -> "已恢复上一次默认人设，已添加的预设保留"
                else -> "默认人设已切换，新会话会使用此人设；单独指定人设的会话不受影响"
            }) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                supervisor.stop()
                runCatching { withContext(Dispatchers.IO) { personas.recover() } }.onFailure { failure.addSuppressed(it) }
                supervisor.setMaintenance(MaintenanceState.IDLE)
                if (!personas.hasPendingChange && failure !is CancellationException && wanted)
                    runCatching { resumeAfterMaintenance(listOf(ServiceId.ASTRBOT)) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                supervisor.setMaintenance(MaintenanceState.IDLE)
                updateState { it.copy(maintenance = MaintenanceState.IDLE) }
            }
        }
    }

    private suspend fun resumeAfterMaintenance(wanted: List<ServiceId>, validateStartup: Boolean = false) {
        for (id in wanted) {
            updateState { it.copy(phase = "正在恢复 ${id.label} 运行") }
            supervisors.getValue(id).start()
            if (activeConnections[id]?.let { it.enabled && it.endpoint != null } == true) waitForInterface(id, maintenanceCheck = validateStartup)
            else if (validateStartup && id == ServiceId.ASTRBOT) withTimeout(120_000) {
                while (astrbotStartup.migration() != "passed") { checkMigration(); delay(500) }
            }
        }
    }

    private suspend fun install() {
        runtime.install { phase ->
            RuntimeStore.log(context, LogCategory.RUNTIME).append("${java.time.Instant.now()} $phase")
            updateState { it.copy(phase = phase) }
        }
        RuntimeStore.refreshInstallation(context)
    }

    private fun event(message: String) = RuntimeStore.log(context, LogCategory.MANAGER).append("${java.time.Instant.now()} $message")

    private fun retainLogs() {
        runCatching {
            val active = supervisors.filterValues { it.state.value.let { state -> state.desiredRunning || state.process != ProcessState.STOPPED } }.keys
            val result = logRetention.enforce(active)
            if (result.deletedFiles > 0) event("历史日志清理：${result.deletedFiles} 个文件，${result.deletedBytes / 1024} KiB")
            if (result.deferredGroups > 0 && result.deferredGroups != lastLogRetentionDeferred)
                event("部分日志暂不能清理，已保留正在使用、最近写入或无法检查的文件；停止服务后将再次检查")
            lastLogRetentionDeferred = result.deferredGroups
        }.onFailure { event("历史日志检查未完成，将在下次维护时重试；现有文件已保留") }
    }

    private fun readNapCatToken() = JSONObject(File(context.filesDir, "persistent/napcat/config/webui.json").readText()).getString("token")

    private fun launchCommand(id: ServiceId, nonce: String? = null) = when (id) {
        ServiceId.ASTRBOT -> "cd /opt/astrbot; export HOME=/persistent/home ASTRBOT_ROOT=/persistent/astrbot; " +
            "export ASTRBOT_MANAGER_OBSERVATION_NONCE=${requireNotNull(nonce)}; " +
            "if test -f /persistent/manager/astrbot-initial-password; then " +
            "export ASTRBOT_DASHBOARD_INITIAL_PASSWORD=\"\$(cat /persistent/manager/astrbot-initial-password)\"; fi; " +
            "exec /opt/astrbot-venv/bin/python /manager/launch_astrbot.py --webui-dir /opt/astrbot/astrbot/dashboard/dist"
        ServiceId.NAPCAT -> "cd /opt/qq; export HOME=/persistent/home NAPCAT_WORKDIR=/persistent/napcat; " +
            "exec xvfb-run -a /opt/qq/qq --no-sandbox"
    }

    private suspend fun waitForInterface(id: ServiceId, maintenanceCheck: Boolean = false) {
        withTimeout(if (maintenanceCheck) 120_000 else 300_000) {
            while (supervisors.getValue(id).state.value.health != HealthState.AVAILABLE ||
                maintenanceCheck && id == ServiceId.ASTRBOT && astrbotStartup.migration() != "passed") {
                check(supervisors.getValue(id).state.value.desiredRunning) { "服务启动已取消" }
                if (maintenanceCheck) check(supervisors.getValue(id).state.value.process != ProcessState.CRASHED) { "新程序启动后异常退出，正在回退" }
                if (maintenanceCheck && id == ServiceId.ASTRBOT) checkMigration()
                delay(500)
            }
        }
    }

    private fun checkMigration() {
        check(astrbotStartup.migration() != "failed") { "新程序数据迁移失败，正在恢复旧程序及对应数据" }
        check(astrbotStartup.migration() != "unsupported") { "新程序迁移状态暂不支持验证，请使用已验证的版本组合" }
    }

    suspend fun close() = commands.withLock {
        ssh.stop()
        supervisors.values.toList().reversed().forEach { it.close() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        onlineUpdates.close()
        onlineNapCat.close()
        updateState { it.copy(services = supervisors.mapValues { entry -> entry.value.state.value }) }
        if (RuntimeStore.owner === owner) RuntimeStore.owner = null
    }

    private fun interfaceAvailable(id: ServiceId, endpoint: LocalEndpoint?): Boolean = endpoint != null && runCatching {
        val path = if (id == ServiceId.NAPCAT) "/webui/" else "/"
        client.newCall(Request.Builder().url(endpoint.origin + path).build()).execute().use {
            it.isSuccessful && it.peekBody(4096).string().contains("<html", ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun checkPortFree(endpoint: LocalEndpoint) {
        try { ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName(endpoint.host), endpoint.port)) } }
        catch (_: java.io.IOException) { error("本机端口 ${endpoint.port} 已被占用，请先停止占用端口的服务") }
    }

    private fun updateState(transform: (ManagerState) -> ManagerState) {
        RuntimeStore.state.update { if (RuntimeStore.owner === owner) transform(it) else it }
    }
}
