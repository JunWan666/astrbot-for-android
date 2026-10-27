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

internal enum class SettingsRoute(private val titleResource: Int) {
    ROOT(R.string.ui_settings), PROGRAMS(R.string.ui_astrbot_updates), NAPCAT(R.string.ui_napcat_updates), ENVIRONMENT(R.string.ui_runtime_upgrades),
    BACKUPS(R.string.ui_backup_and_restore), PORTABLE(R.string.ui_portable_backups), DIAGNOSTICS(R.string.ui_logs_and_diagnostics), BACKGROUND(R.string.ui_background_operation), ABOUT(R.string.ui_about);
    val title: String get() = AppText.get(titleResource)

    val parent get() = when (this) { PROGRAMS, NAPCAT, ENVIRONMENT -> ABOUT; PORTABLE -> BACKUPS; else -> ROOT }
}
internal enum class ServiceRoute(private val titleResource: Int) { STATUS(R.string.ui_overview), CONNECTION(R.string.ui_connection_eebb5), CREDENTIALS(R.string.ui_credentials), LOGS(R.string.ui_logs_894b8), LAN(R.string.ui_connection_eebb5) ;
    val title: String get() = AppText.get(titleResource)
}
internal enum class SettingAction { VERSIONS, CHECK_UPDATE, CHECK_APP_UPDATE, IMPORT_UPDATE, ONLINE_UPDATE, APPLY_UPDATE, ROLLBACK,
    CHECK_NAPCAT, ONLINE_NAPCAT, APPLY_NAPCAT, ROLLBACK_NAPCAT,
    APPLY_RUNTIME, ROLLBACK_RUNTIME, CREATE_BACKUP, BACKUPS, BACKUP_LIMITS, EXPORT_PORTABLE, IMPORT_PORTABLE,
    PORTABLE_SCOPE, DISCARD_PORTABLE, LOGS, RUNTIME_LOGS, DIAGNOSTICS, EXTENSIONS, APP_SETTINGS, BATTERY, LICENSES,
    NOTIFICATIONS, UNRESTRICTED, AUTOSTART, TASK_LOCK, BACKGROUND_HELP, AUTOMATIC_UPDATES, ENVIRONMENT_CHECK, STORAGE_USAGE, DATA_RETENTION, LANGUAGE }
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
    val enabled: Boolean = true, val badge: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

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
                                item.badge?.invoke()
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
                ManagerActionButton(AppText.get(R.string.ui_previous), { selectedPage = current - 1 }, tone = ActionTone.NEUTRAL, enabled = current > 0)
                Text("${current + 1} / $pages", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                ManagerActionButton(AppText.get(R.string.ui_next), { selectedPage = current + 1 }, tone = ActionTone.NEUTRAL, enabled = current + 1 < pages)
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
        onNotice(if (state.error != null) AppText.get(R.string.ui_operation_incomplete) else if (storageWarning != null) state.storageSpace.pressure.title else AppText.get(R.string.ui_operation_notice),
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
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_home_content) }) {
        val landscape = maxWidth >= 600.dp && maxHeight < 500.dp
        val tight = maxHeight < 600.dp || LocalDensity.current.fontScale > 1.25f
        @Composable fun overview() {
            if (tight) CompactOverview(state, network, { onCommand(it, null) }, onNotice)
            else ManagerOverview(state, network) { onCommand(it, null) }
            ManagerResources(resources, compact = true)
            ManagerStatusStrip(state, onNotice)
            if (!state.componentsReady) ManagerActionButton(AppText.get(R.string.ui_initialize_runtime),
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
                            if (selected == id) ActionTone.PRIMARY else ActionTone.NEUTRAL, description = AppText.get(R.string.ui_view_card, id.label))
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
                Text(AppText.get(R.string.ui_my_services), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(AppText.get(R.string.ui_manage_connections_in_details), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Column(Modifier.weight(1f).clickable { onNotice(AppText.get(R.string.ui_system_network_status), network.detail) }) {
                Text(AppText.get(R.string.ui_runtime_overview), style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(network.title, Modifier.semantics { contentDescription = AppText.get(R.string.ui_system_network_status) }, style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ManagerRunningCount(running, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerOverviewCommand("start", AppText.get(R.string.ui_start_all), R.drawable.ic_play, state, false, Modifier.weight(1f), onCommand)
            ManagerOverviewCommand("stop", AppText.get(R.string.ui_stop_all), R.drawable.ic_stop, state, false, Modifier.weight(1f), onCommand)
            ManagerOverviewCommand("restart", AppText.get(R.string.ui_restart_all), R.drawable.ic_restart, state, false, Modifier.weight(1f), onCommand)
        }
    }
}

@Composable private fun DashboardServiceCard(id: ServiceId, status: ServiceStatus, version: String?, ready: Boolean, busy: Boolean,
    onOpen: () -> Unit, onCommand: (String) -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val spacious = maxHeight >= 150.dp
        Column(Modifier.fillMaxSize().padding(if (spacious) 16.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onDetails).semantics { contentDescription = AppText.get(R.string.ui_service_details, id.label) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerServiceLogo(id)
                Column(Modifier.weight(1f)) {
                    Text(id.label + (version?.takeIf { it.length <= 24 && it.firstOrNull()?.isDigit() == true }?.let { "  $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status.localizedSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ManagerProcessBadge(status.process)
            }
            if (spacious) Text(if (!ready) AppText.get(R.string.ui_initialize_the_runtime_to_use_the_webui) else if (id == ServiceId.ASTRBOT) AppText.get(R.string.ui_manage_models_platforms_and_plugins_in_the_webui)
                else AppText.get(R.string.ui_onebot_to_astrbot) + when (status.oneBot) {
                    OneBotState.UNCONFIGURED -> AppText.get(R.string.ui_not_configured); OneBotState.CONNECTING -> AppText.get(R.string.ui_connecting); OneBotState.CONNECTED -> AppText.get(R.string.ui_connected)
                    OneBotState.DISCONNECTED -> AppText.get(R.string.ui_disconnected); OneBotState.UNKNOWN -> AppText.get(R.string.ui_pending)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(AppText.get(R.string.ui_webui), onOpen, Modifier.weight(1.4f), enabled = status.canOpenWebUi, description = AppText.get(R.string.ui_open_webui, id.label))
                val stop = status.desiredRunning || status.process != ProcessState.STOPPED
                ManagerActionButton(if (stop) AppText.get(R.string.ui_stop) else AppText.get(R.string.ui_start), { onCommand(if (stop) "stop" else "start") }, Modifier.weight(.8f),
                    if (stop) ActionTone.STOP else ActionTone.START, stop || ready && !busy, "${if (stop) AppText.get(R.string.ui_stop) else AppText.get(R.string.ui_start)} ${id.label}")
                ManagerActionButton(AppText.get(R.string.ui_details), onDetails, Modifier.weight(.8f), ActionTone.NEUTRAL, description = AppText.get(R.string.ui_details_2ee06, id.label))
            }
        }
        }
    }
}

@Composable internal fun FixedManagerSettings(route: SettingsRoute, state: ManagerState, onNavigate: (SettingsRoute) -> Unit,
    onAction: (SettingAction) -> Unit, onNotice: (String, String) -> Unit,
    automaticUpdateEnabled: Boolean = false, updateChannelConfigured: Boolean = BuildConfig.UPDATE_CATALOG_URL.isNotEmpty()) {
    if (route == SettingsRoute.BACKGROUND) {
        Column(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_settings_content) },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ManagerStatusStrip(state, onNotice)
            BackgroundSettings(Modifier.weight(1f), onAction)
        }
        return
    }
    fun action(title: String, icon: Int, subtitle: String? = null, enabled: Boolean = true, action: SettingAction) =
        ManagerMenuItem(title, icon, subtitle, enabled) { onAction(action) }
    fun destination(title: String, icon: Int, subtitle: String, destination: SettingsRoute) =
        ManagerMenuItem(title, icon, subtitle) { onNavigate(destination) }
    val ready = state.componentsReady && !state.busy
    val items = when (route) {
        SettingsRoute.ROOT -> listOf(
            action(AppText.get(R.string.ui_extensions), R.drawable.ic_settings, AppText.get(R.string.ui_ssh_terminal_and_other_optional_features), action = SettingAction.EXTENSIONS),
            destination(AppText.get(R.string.ui_backup_and_restore), R.drawable.ic_backup, AppText.get(R.string.ui_local_snapshots_portable_backups_and_limits), SettingsRoute.BACKUPS),
            destination(AppText.get(R.string.ui_logs_and_diagnostics), R.drawable.ic_logs, AppText.get(R.string.ui_runtime_logs_and_diagnostic_exports), SettingsRoute.DIAGNOSTICS),
            destination(AppText.get(R.string.ui_background_operation), R.drawable.ic_battery, AppText.get(R.string.ui_permissions_auto_start_and_battery_management), SettingsRoute.BACKGROUND),
            action(AppText.get(R.string.language_title), R.drawable.ic_settings, AppText.get(R.string.language_subtitle), action = SettingAction.LANGUAGE),
            destination(AppText.get(R.string.ui_about), R.drawable.ic_info, AppText.get(R.string.ui_updates_app_version_and_licenses), SettingsRoute.ABOUT))
        SettingsRoute.PROGRAMS -> listOf(
            action(AppText.get(R.string.ui_check_astrbot_updates), R.drawable.ic_update, AppText.get(R.string.ui_find_packages_compatible_with_the_current_runtime), ready, SettingAction.CHECK_UPDATE),
            action(AppText.get(R.string.ui_available_version_and_download), R.drawable.ic_document, state.latestUpdate?.let { AppText.get(R.string.ui_astrbot_download_or_reinstall, it.version) } ?: AppText.get(R.string.ui_check_for_updates_to_view_release_notes_and_downloads), ready, SettingAction.ONLINE_UPDATE),
            action(AppText.get(R.string.ui_import_update_package), R.drawable.ic_import, AppText.get(R.string.ui_select_a_signed_local_maintenance_package), ready, SettingAction.IMPORT_UPDATE),
            action(AppText.get(R.string.ui_view_prepared_update), R.drawable.ic_update, if (state.preparedUpdate == null) AppText.get(R.string.ui_no_update_package_prepared) else AppText.get(R.string.ui_review_before_installation), state.preparedUpdate != null && !state.busy, SettingAction.APPLY_UPDATE),
            action(AppText.get(R.string.ui_roll_back_astrbot), R.drawable.ic_restart, state.rollback?.let { AppText.get(R.string.ui_restore_version, it.version) } ?: AppText.get(R.string.ui_no_program_rollback_available), state.rollback != null && !state.busy, SettingAction.ROLLBACK),
            action(AppText.get(R.string.ui_automatic_update_checks), R.drawable.ic_settings, when {
                !updateChannelConfigured -> AppText.get(R.string.ui_no_online_update_channel_configured)
                automaticUpdateEnabled -> AppText.get(R.string.ui_on_check_daily_when_opening_the_app)
                else -> AppText.get(R.string.ui_off_tap_to_configure)
            }, action = SettingAction.AUTOMATIC_UPDATES))
        SettingsRoute.NAPCAT -> listOf(
            action(AppText.get(R.string.ui_check_napcat_updates), R.drawable.ic_update, AppText.get(R.string.ui_update_independently_without_reinstalling_the_app), ready, SettingAction.CHECK_NAPCAT),
            action(AppText.get(R.string.ui_napcat_version_and_download), R.drawable.ic_document, state.latestNapCat?.let { AppText.get(R.string.ui_napcat_official_upstream_package, it.version) } ?: AppText.get(R.string.ui_check_for_updates_to_view_release_notes_and_downloads), ready, SettingAction.ONLINE_NAPCAT),
            action(AppText.get(R.string.ui_install_napcat_update), R.drawable.ic_update, state.preparedNapCat?.let { AppText.get(R.string.ui_verified_back_up_before_installing, it.version) } ?: AppText.get(R.string.ui_download_and_verify_a_package_first), state.preparedNapCat != null && !state.busy, SettingAction.APPLY_NAPCAT),
            action(AppText.get(R.string.ui_roll_back_napcat), R.drawable.ic_restart, state.napCatRollback?.let { AppText.get(R.string.ui_restore_and_its_data, it.version) } ?: AppText.get(R.string.ui_no_napcat_rollback_available), state.napCatRollback != null && !state.busy, SettingAction.ROLLBACK_NAPCAT))
        SettingsRoute.ENVIRONMENT -> listOf(
            action(AppText.get(R.string.ui_environment_check_and_repair), R.drawable.ic_settings, AppText.get(R.string.ui_distinguish_runtime_issues_from_webui_configuration_issues), !state.busy, SettingAction.ENVIRONMENT_CHECK),
            action(AppText.get(R.string.ui_upgrade_bundled_runtime), R.drawable.ic_update, state.runtimeUpdate?.name ?: AppText.get(R.string.ui_runtime_matches_the_bundled_resources), state.runtimeUpdate != null && !state.busy, SettingAction.APPLY_RUNTIME),
            action(AppText.get(R.string.ui_roll_back_the_entire_runtime), R.drawable.ic_restart, if (state.runtimeRollback == null) AppText.get(R.string.ui_no_runtime_rollback_available) else AppText.get(R.string.ui_restore_the_previous_runtime_and_its_data), state.runtimeRollback != null && !state.busy, SettingAction.ROLLBACK_RUNTIME))
        SettingsRoute.BACKUPS -> listOf(
            action(AppText.get(R.string.ui_create_local_backup), R.drawable.ic_backup, AppText.get(R.string.ui_includes_qq_sessions_services_resume_afterward), ready, SettingAction.CREATE_BACKUP),
            action(AppText.get(R.string.ui_manage_backups, state.backups.size), R.drawable.ic_document, AppText.get(R.string.ui_view_restore_and_delete_local_backups), action = SettingAction.BACKUPS),
            action(AppText.get(R.string.ui_automatic_snapshot_limits), R.drawable.ic_settings, AppText.get(R.string.ui_keep_remove_oldest_snapshots_automatically, state.backupLimits.automaticCount), !state.busy, SettingAction.BACKUP_LIMITS),
            destination(AppText.get(R.string.ui_portable_backups), R.drawable.ic_export, AppText.get(R.string.ui_transfer_between_devices_excludes_qq_sessions), SettingsRoute.PORTABLE),
            action(AppText.get(R.string.ui_data_and_uninstallation), R.drawable.ic_info, AppText.get(R.string.ui_save_backups_outside_the_app_before_uninstalling), action = SettingAction.DATA_RETENTION))
        SettingsRoute.PORTABLE -> listOf(
            action(AppText.get(R.string.ui_export_portable_backup), R.drawable.ic_export, AppText.get(R.string.ui_save_configuration_and_data_to_a_file), ready, SettingAction.EXPORT_PORTABLE),
            action(AppText.get(R.string.ui_import_portable_backup), R.drawable.ic_import, AppText.get(R.string.ui_select_a_zip_and_review_what_will_be_restored), ready, SettingAction.IMPORT_PORTABLE),
            action(AppText.get(R.string.ui_view_import_scope), R.drawable.ic_info, if (state.portableImport == null) AppText.get(R.string.ui_no_backup_file_selected) else AppText.get(R.string.ui_review_before_restoring), state.portableImport != null && !state.busy, SettingAction.PORTABLE_SCOPE),
            action(AppText.get(R.string.ui_discard_pending_import), R.drawable.ic_backup, AppText.get(R.string.ui_remove_only_the_pending_temporary_copy), state.portableImport != null && !state.busy, SettingAction.DISCARD_PORTABLE))
        SettingsRoute.DIAGNOSTICS -> listOf(
            action(AppText.get(R.string.ui_environment_check_and_repair), R.drawable.ic_settings, AppText.get(R.string.ui_check_resources_dependencies_and_webui_settings), !state.busy, SettingAction.ENVIRONMENT_CHECK),
            action(AppText.get(R.string.ui_storage_usage), R.drawable.ic_backup, AppText.get(R.string.ui_view_data_cache_and_backup_sizes), action = SettingAction.STORAGE_USAGE),
            action(AppText.get(R.string.ui_manager_logs), R.drawable.ic_logs, AppText.get(R.string.ui_service_controls_updates_and_backup_events), action = SettingAction.LOGS),
            action(AppText.get(R.string.ui_runtime_logs), R.drawable.ic_logs, AppText.get(R.string.ui_initialization_and_runtime_maintenance), action = SettingAction.RUNTIME_LOGS),
            action(AppText.get(R.string.ui_export_diagnostics), R.drawable.ic_export, AppText.get(R.string.ui_device_information_status_and_redacted_logs), !state.busy, SettingAction.DIAGNOSTICS))
        SettingsRoute.BACKGROUND -> emptyList() // Rendered above with live permission statuses.
        SettingsRoute.ABOUT -> listOf(
            action(AppText.get(R.string.ui_check_for_updates), R.drawable.ic_update, AppText.get(R.string.ui_app_apk_updates_and_installation), true, SettingAction.CHECK_APP_UPDATE),
            action(AppText.get(R.string.ui_app_and_component_versions), R.drawable.ic_info, BuildConfig.VERSION_NAME, action = SettingAction.VERSIONS),
            destination(AppText.get(R.string.ui_astrbot_updates), R.drawable.ic_update, AppText.get(R.string.ui_release_notes_maintenance_packages_and_rollback), SettingsRoute.PROGRAMS),
            destination(AppText.get(R.string.ui_napcat_updates), R.drawable.ic_update, AppText.get(R.string.ui_independent_updates_retaining_configuration_and_sessions), SettingsRoute.NAPCAT),
            destination(AppText.get(R.string.ui_runtime_upgrades), R.drawable.ic_restart, AppText.get(R.string.ui_qq_python_and_underlying_dependencies), SettingsRoute.ENVIRONMENT),
            action(AppText.get(R.string.ui_third_party_licenses), R.drawable.ic_document, AppText.get(R.string.ui_open_source_licenses_and_source_materials), action = SettingAction.LICENSES))
    }
    Column(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_settings_content) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ManagerStatusStrip(state, onNotice)
        FixedManagerMenu(items, Modifier.weight(1f))
        if (route == SettingsRoute.ROOT || route == SettingsRoute.ABOUT) Text("${ProjectIdentity.AUTHOR} · v${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
