package io.astrbot.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlin.math.floor

internal enum class SettingsRoute(val title: String) {
    ROOT("设置"), PROGRAMS("AstrBot 程序更新"), NAPCAT("NapCat 程序更新"), ENVIRONMENT("运行环境升级"),
    BACKUPS("备份与恢复"), PORTABLE("便携备份"), DIAGNOSTICS("日志与诊断"), BACKGROUND("后台运行"), ABOUT("关于");
    val parent get() = when (this) { PROGRAMS, NAPCAT, ENVIRONMENT -> ABOUT; PORTABLE -> BACKUPS; else -> ROOT }
}
internal enum class ServiceRoute(val title: String) { STATUS("概览"), CONNECTION("连接"), CREDENTIALS("凭据"), LOGS("日志"), LAN("连接") }
internal enum class SettingAction { VERSIONS, CHECK_UPDATE, CHECK_APP_UPDATE, IMPORT_UPDATE, ONLINE_UPDATE, APPLY_UPDATE, ROLLBACK,
    CHECK_NAPCAT, ONLINE_NAPCAT, APPLY_NAPCAT, ROLLBACK_NAPCAT,
    APPLY_RUNTIME, ROLLBACK_RUNTIME, CREATE_BACKUP, BACKUPS, BACKUP_LIMITS, EXPORT_PORTABLE, IMPORT_PORTABLE,
    PORTABLE_SCOPE, DISCARD_PORTABLE, LOGS, RUNTIME_LOGS, DIAGNOSTICS, EXTENSIONS, APP_SETTINGS, BATTERY, LICENSES,
    NOTIFICATIONS, UNRESTRICTED, AUTOSTART, TASK_LOCK, BACKGROUND_HELP, AUTOMATIC_UPDATES, ENVIRONMENT_CHECK, STORAGE_USAGE, DATA_RETENTION }
internal enum class ActionTone { PRIMARY, START, STOP, RESTART, NEUTRAL }

internal fun ManagerState.canStop(id: ServiceId? = null): Boolean = busy ||
    (id?.let { listOf(services.getValue(it)) } ?: services.values).any { it.desiredRunning || it.process != ProcessState.STOPPED }

@Composable internal fun ManagerActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.PRIMARY, enabled: Boolean = true, description: String = label) {
    val dark = isSystemInDarkTheme()
    val accent = when (tone) {
        ActionTone.START -> if (dark) Color(0xFF75D8AF) else Color(0xFF12835E)
        ActionTone.STOP -> if (dark) Color(0xFFFFABA4) else Color(0xFFC64242)
        ActionTone.RESTART -> if (dark) Color(0xFFF2C16C) else Color(0xFFA76A0E)
        else -> MaterialTheme.colorScheme.primary
    }
    val colors = when (tone) {
        ActionTone.PRIMARY -> ButtonDefaults.buttonColors()
        ActionTone.NEUTRAL -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface)
        else -> ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = if (dark) .18f else .10f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow), contentColor = accent)
    }
    Button(onClick, modifier.heightIn(min = 44.dp).semantics { contentDescription = description }, enabled = enabled,
        colors = colors, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class ManagerMenuItem(val title: String, val icon: Int, val subtitle: String? = null,
    val enabled: Boolean = true, val onClick: () -> Unit)

/** Fixed pages with a bounded number of rows. Small windows use pages instead of scrolling. */
@Composable internal fun FixedManagerMenu(items: List<ManagerMenuItem>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        val rowHeight = if (LocalDensity.current.fontScale > 1.2f) 82.dp else 70.dp
        val rowsWithoutPager = floor(maxHeight / (rowHeight + 8.dp)).toInt().coerceAtLeast(1)
        val allFit = items.size <= rowsWithoutPager * columns
        val rows = if (allFit) rowsWithoutPager else floor((maxHeight - 56.dp) / (rowHeight + 8.dp)).toInt().coerceAtLeast(1)
        val pageSize = rows * columns
        val pages = ((items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        var selectedPage by rememberSaveable(items.map { it.title }.joinToString()) { mutableIntStateOf(0) }
        val current = selectedPage.coerceIn(0, pages - 1)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.drop(current * pageSize).take(pageSize).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item ->
                        Surface(Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxSize().clickable(enabled = item.enabled, onClick = item.onClick).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ManagerGlyph(item.icon, if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerActionButton("上一页", { selectedPage = current - 1 }, tone = ActionTone.NEUTRAL, enabled = current > 0)
                Text("${current + 1} / $pages", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                ManagerActionButton("下一页", { selectedPage = current + 1 }, tone = ActionTone.NEUTRAL, enabled = current + 1 < pages)
            }
        }
    }
}

@Composable internal fun ManagerStatusStrip(state: ManagerState, onNotice: (String, String) -> Unit) {
    val storageWarning = state.storageSpace.warning
    val text = state.error ?: storageWarning ?: state.notice ?: return
    val error = state.error != null || storageWarning != null
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxWidth().clickable {
        onNotice(if (state.error != null) "操作未完成" else if (storageWarning != null) state.storageSpace.pressure.title else "操作提示",
            if (state.error == null && storageWarning != null) "$text\n\n${StorageSpace.HELP}" else text)
    },
        color = color.copy(alpha = .08f), shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(16.dp), tint = color)
            Text(text, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = color)
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(14.dp), tint = color)
        }
    }
}

@Composable internal fun FixedManagerHome(state: ManagerState, network: NetworkSnapshot, resources: ResourceSnapshot,
    onCommand: (String, ServiceId?) -> Unit, onDetails: (ServiceId) -> Unit, onOpen: (ServiceId) -> Unit, onNotice: (String, String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "首页内容" }) {
        val landscape = maxWidth >= 600.dp && maxHeight < 500.dp
        val tight = maxHeight < 600.dp || LocalDensity.current.fontScale > 1.25f
        @Composable fun overview() {
            if (tight) CompactOverview(state, network, { onCommand(it, null) }, onNotice)
            else ManagerOverview(state, network) { onCommand(it, null) }
            ManagerResources(resources, compact = true)
            ManagerStatusStrip(state, onNotice)
            if (!state.componentsReady) ManagerActionButton("初始化环境",
                { onCommand("initialize", null) }, Modifier.fillMaxWidth(), enabled = !state.busy)
        }
        @Composable fun services(modifier: Modifier) {
            BoxWithConstraints(modifier.fillMaxHeight()) {
            val minimumCard = if (LocalDensity.current.fontScale > 1.2f) 144.dp else 116.dp
            val paged = maxHeight < minimumCard * 2 + 12.dp
            var selected by rememberSaveable { mutableStateOf(ServiceId.ASTRBOT) }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (paged) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServiceId.entries.forEach { id ->
                        ManagerActionButton(id.label, { selected = id }, Modifier.weight(1f),
                            if (selected == id) ActionTone.PRIMARY else ActionTone.NEUTRAL, description = "查看 ${id.label} 卡片")
                    }
                }
                (if (paged) listOf(selected) else ServiceId.entries).forEach { id ->
                    DashboardServiceCard(id, state.services.getValue(id), state.versions[id.label], state.componentsReady, state.busy,
                        { onOpen(id) }, { onCommand(it, id) }, { onDetails(id) }, Modifier.weight(1f))
                }
            }
            }
        }
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(.44f), verticalArrangement = Arrangement.spacedBy(10.dp)) { overview() }
            services(Modifier.weight(.56f))
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 12.dp)) {
            overview()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的服务", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("点击详情管理连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            services(Modifier.weight(1f))
        }
    }
}

@Composable private fun CompactOverview(state: ManagerState, network: NetworkSnapshot, onCommand: (String) -> Unit,
    onNotice: (String, String) -> Unit) {
    val running = state.services.values.count { it.process == ProcessState.RUNNING }
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF386BF6), Color(0xFF5686FF))),
        MaterialTheme.shapes.large).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onNotice("系统网络状态", network.detail) }) {
                Text("运行概况", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(network.title, Modifier.semantics { contentDescription = "系统网络状态" }, style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ManagerRunningCount(running, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerOverviewCommand("start", "启动全部", R.drawable.ic_play, state, false, Modifier.weight(1f), onCommand)
            ManagerOverviewCommand("stop", "停止全部", R.drawable.ic_stop, state, false, Modifier.weight(1f), onCommand)
            ManagerOverviewCommand("restart", "重启全部", R.drawable.ic_restart, state, false, Modifier.weight(1f), onCommand)
        }
    }
}

@Composable private fun DashboardServiceCard(id: ServiceId, status: ServiceStatus, version: String?, ready: Boolean, busy: Boolean,
    onOpen: () -> Unit, onCommand: (String) -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val spacious = maxHeight >= 150.dp
        Column(Modifier.fillMaxSize().padding(if (spacious) 16.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onDetails).semantics { contentDescription = "${id.label} 服务详情" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerServiceLogo(id)
                Column(Modifier.weight(1f)) {
                    Text(id.label + (version?.takeIf { it.length <= 24 && it.firstOrNull()?.isDigit() == true }?.let { "  $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ManagerProcessBadge(status.process)
            }
            if (spacious) Text(if (!ready) "初始化后可使用后台" else if (id == ServiceId.ASTRBOT) "模型、平台与插件，请进入后台管理"
                else "OneBot 与 AstrBot：" + when (status.oneBot) {
                    OneBotState.UNCONFIGURED -> "尚未配置"; OneBotState.CONNECTING -> "连接中"; OneBotState.CONNECTED -> "已连接"
                    OneBotState.DISCONNECTED -> "未连接"; OneBotState.UNKNOWN -> "待确认"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton("打开后台", onOpen, Modifier.weight(1.4f), enabled = status.canOpenWebUi, description = "${id.label} 打开后台")
                val stop = status.desiredRunning || status.process != ProcessState.STOPPED
                ManagerActionButton(if (stop) "停止" else "启动", { onCommand(if (stop) "stop" else "start") }, Modifier.weight(.8f),
                    if (stop) ActionTone.STOP else ActionTone.START, stop || ready && !busy, "${if (stop) "停止" else "启动"} ${id.label}")
                ManagerActionButton("详情", onDetails, Modifier.weight(.8f), ActionTone.NEUTRAL, description = "${id.label} 详情")
            }
        }
        }
    }
}

@Composable internal fun FixedManagerSettings(route: SettingsRoute, state: ManagerState, onNavigate: (SettingsRoute) -> Unit,
    onAction: (SettingAction) -> Unit, onNotice: (String, String) -> Unit,
    automaticUpdateEnabled: Boolean = false, updateChannelConfigured: Boolean = BuildConfig.UPDATE_CATALOG_URL.isNotEmpty()) {
    fun action(title: String, icon: Int, subtitle: String? = null, enabled: Boolean = true, action: SettingAction) =
        ManagerMenuItem(title, icon, subtitle, enabled) { onAction(action) }
    fun destination(title: String, icon: Int, subtitle: String, destination: SettingsRoute) =
        ManagerMenuItem(title, icon, subtitle) { onNavigate(destination) }
    val ready = state.componentsReady && !state.busy
    val items = when (route) {
        SettingsRoute.ROOT -> listOf(
            action("扩展组件", R.drawable.ic_settings, "SSH 终端等可选功能", action = SettingAction.EXTENSIONS),
            destination("备份与恢复", R.drawable.ic_backup, "本机快照、便携备份与容量", SettingsRoute.BACKUPS),
            destination("日志与诊断", R.drawable.ic_logs, "运行日志与诊断导出", SettingsRoute.DIAGNOSTICS),
            destination("后台运行", R.drawable.ic_battery, "权限、自启动与电池管理", SettingsRoute.BACKGROUND),
            destination("关于", R.drawable.ic_info, "检查更新、应用版本与许可", SettingsRoute.ABOUT))
        SettingsRoute.PROGRAMS -> listOf(
            action("检查 AstrBot 更新", R.drawable.ic_update, "检查适配当前运行环境的程序包", ready, SettingAction.CHECK_UPDATE),
            action("在线版本与下载", R.drawable.ic_document, state.latestUpdate?.let { "AstrBot ${it.version} · 可下载或重新安装" } ?: "点击检查后获取版本说明与下载", ready, SettingAction.ONLINE_UPDATE),
            action("导入更新包", R.drawable.ic_import, "选择已签名的本地维护包", ready, SettingAction.IMPORT_UPDATE),
            action("查看待安装更新", R.drawable.ic_update, if (state.preparedUpdate == null) "尚未准备更新包" else "核对后确认安装", state.preparedUpdate != null && !state.busy, SettingAction.APPLY_UPDATE),
            action("回退 AstrBot 程序", R.drawable.ic_restart, state.rollback?.let { "回退到 ${it.version}" } ?: "暂无程序回退点", state.rollback != null && !state.busy, SettingAction.ROLLBACK),
            action("自动检查更新", R.drawable.ic_settings, when {
                !updateChannelConfigured -> "尚未配置在线更新通道"
                automaticUpdateEnabled -> "已开启 · 打开应用时每天检查"
                else -> "已关闭 · 点击设置"
            }, action = SettingAction.AUTOMATIC_UPDATES))
        SettingsRoute.NAPCAT -> listOf(
            action("检查 NapCat 更新", R.drawable.ic_update, "独立更新，无需重新安装管理器", ready, SettingAction.CHECK_NAPCAT),
            action("NapCat 在线版本与下载", R.drawable.ic_document, state.latestNapCat?.let { "NapCat ${it.version} · 上游正式程序包" } ?: "点击检查后获取版本说明与下载", ready, SettingAction.ONLINE_NAPCAT),
            action("安装 NapCat 更新", R.drawable.ic_update, state.preparedNapCat?.let { "已校验 ${it.version} · 先备份再安装" } ?: "先下载并校验程序包", state.preparedNapCat != null && !state.busy, SettingAction.APPLY_NAPCAT),
            action("回退 NapCat 程序", R.drawable.ic_restart, state.napCatRollback?.let { "回退到 ${it.version} 及对应数据" } ?: "暂无 NapCat 回退点", state.napCatRollback != null && !state.busy, SettingAction.ROLLBACK_NAPCAT))
        SettingsRoute.ENVIRONMENT -> listOf(
            action("环境检查与修复", R.drawable.ic_settings, "区分运行资源与后台配置问题", !state.busy, SettingAction.ENVIRONMENT_CHECK),
            action("升级 APK 内置环境", R.drawable.ic_update, state.runtimeUpdate?.name ?: "当前环境已匹配内置资源", state.runtimeUpdate != null && !state.busy, SettingAction.APPLY_RUNTIME),
            action("回退整个运行环境", R.drawable.ic_restart, if (state.runtimeRollback == null) "暂无环境回退点" else "恢复旧环境及对应数据", state.runtimeRollback != null && !state.busy, SettingAction.ROLLBACK_RUNTIME))
        SettingsRoute.BACKUPS -> listOf(
            action("创建本机备份", R.drawable.ic_backup, "含 QQ 会话 · 完成后恢复服务", ready, SettingAction.CREATE_BACKUP),
            action("备份管理（${state.backups.size}）", R.drawable.ic_document, "查看、恢复与删除本机备份", action = SettingAction.BACKUPS),
            action("自动快照上限", R.drawable.ic_settings, "${state.backupLimits.automaticCount} 份 · 自动清理最旧快照", !state.busy, SettingAction.BACKUP_LIMITS),
            destination("便携备份", R.drawable.ic_export, "跨设备导入导出 · 不含 QQ 会话", SettingsRoute.PORTABLE),
            action("数据与卸载说明", R.drawable.ic_info, "卸载前先将备份保存到应用外", action = SettingAction.DATA_RETENTION))
        SettingsRoute.PORTABLE -> listOf(
            action("导出便携备份", R.drawable.ic_export, "保存配置与数据到文件", ready, SettingAction.EXPORT_PORTABLE),
            action("导入便携备份", R.drawable.ic_import, "选择 ZIP 并核对恢复范围", ready, SettingAction.IMPORT_PORTABLE),
            action("查看导入范围", R.drawable.ic_info, if (state.portableImport == null) "尚未选择备份文件" else "核对后确认恢复", state.portableImport != null && !state.busy, SettingAction.PORTABLE_SCOPE),
            action("移除待导入备份", R.drawable.ic_backup, "仅移除待处理的临时副本", state.portableImport != null && !state.busy, SettingAction.DISCARD_PORTABLE))
        SettingsRoute.DIAGNOSTICS -> listOf(
            action("环境检查与修复", R.drawable.ic_settings, "检查资源、依赖与后台配置", !state.busy, SettingAction.ENVIRONMENT_CHECK),
            action("存储使用", R.drawable.ic_backup, "查看数据、缓存和备份占用", action = SettingAction.STORAGE_USAGE),
            action("管理器日志", R.drawable.ic_logs, "启停、更新与备份记录", action = SettingAction.LOGS),
            action("运行环境日志", R.drawable.ic_logs, "初始化与环境维护记录", action = SettingAction.RUNTIME_LOGS),
            action("导出诊断包", R.drawable.ic_export, "设备信息、状态和脱敏日志", !state.busy, SettingAction.DIAGNOSTICS))
        SettingsRoute.BACKGROUND -> listOf(
            action("运行通知", R.drawable.ic_info, "允许状态通知与通知类别", action = SettingAction.NOTIFICATIONS),
            action("忽略电池优化", R.drawable.ic_battery, "检查系统电池优化状态", action = SettingAction.BATTERY),
            action("后台耗电与无限制", R.drawable.ic_battery, "按当前手机系统引导设置", action = SettingAction.UNRESTRICTED),
            action("自启动与关联启动", R.drawable.ic_settings, "厂商权限与手动确认", action = SettingAction.AUTOSTART),
            action("锁定最近任务", R.drawable.ic_info, "防止一键清理误关", action = SettingAction.TASK_LOCK),
            action("后台运行说明", R.drawable.ic_document, "查看设置建议和运行限制", action = SettingAction.BACKGROUND_HELP))
        SettingsRoute.ABOUT -> listOf(
            action("检查更新", R.drawable.ic_update, "管理器 APK 更新与安装", true, SettingAction.CHECK_APP_UPDATE),
            action("应用与组件版本", R.drawable.ic_info, BuildConfig.VERSION_NAME, action = SettingAction.VERSIONS),
            destination("AstrBot 程序更新", R.drawable.ic_update, "在线说明、维护包与程序回退", SettingsRoute.PROGRAMS),
            destination("NapCat 程序更新", R.drawable.ic_update, "独立更新、保留配置与会话", SettingsRoute.NAPCAT),
            destination("运行环境升级", R.drawable.ic_restart, "QQ、Python 与底层依赖", SettingsRoute.ENVIRONMENT),
            action("第三方许可", R.drawable.ic_document, "开源许可与来源材料", action = SettingAction.LICENSES))
    }
    Column(Modifier.fillMaxSize().semantics { contentDescription = "设置内容" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ManagerStatusStrip(state, onNotice)
        FixedManagerMenu(items, Modifier.weight(1f))
        if (route == SettingsRoute.ROOT || route == SettingsRoute.ABOUT) Text("${ProjectIdentity.AUTHOR} · v${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
