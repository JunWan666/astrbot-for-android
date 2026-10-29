package io.astrbot.android

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.astrbot.android.debug.DebugAccess
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import rikka.shizuku.Shizuku

@Composable
internal fun PermissionsPage(
    status: DebugAccess.Status, report: JSONObject?, busy: Boolean, installerBusy: Boolean,
    batteryExempt: Boolean, message: String?, error: String?,
    onBack: () -> Unit, onInstall: () -> Unit, onAuthorize: () -> Unit, onManager: () -> Unit,
    onDeveloperSettings: () -> Unit, onOfficialSite: () -> Unit, onBatterySettings: () -> Unit,
    onBatteryChange: (Boolean) -> Unit, onLimitChange: (Boolean) -> Unit,
    onRefresh: () -> Unit, onCopy: () -> Unit,
    onDeviceAccess: () -> Unit,
) {
    var help by remember { mutableStateOf<String?>(null) }
    val ready = status == DebugAccess.Status.READY
    val working = busy || installerBusy
    val counts = report?.optJSONObject("children")
    val effective = report?.takeUnless { it.isNull("effective") }?.optInt("effective")
    val countReadable = counts?.optBoolean("readable") == true
    val systemCount = if (countReadable) counts!!.getInt("system") else null
    val appCount = if (countReadable) counts!!.getInt("app") else null
    val configured = if (report?.optBoolean("configuredReadable") == true) {
        report.optString("configured").let { if (it == "null") AppText.get(R.string.debug_system_default) else it }
    } else AppText.get(R.string.debug_short_unknown)
    val statusText = AppText.get(when (status) {
        DebugAccess.Status.NOT_INSTALLED -> R.string.debug_not_installed
        DebugAccess.Status.STOPPED -> R.string.debug_stopped
        DebugAccess.Status.NEED_PERMISSION -> R.string.debug_needs_permission
        DebugAccess.Status.DENIED -> R.string.debug_denied
        DebugAccess.Status.READY -> R.string.debug_state_ready
        DebugAccess.Status.UNSUPPORTED -> R.string.debug_unsupported
    })
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(20.dp))
                }
                Text(AppText.get(R.string.permissions_title), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerSection(AppText.get(R.string.group_privilege), padded = false) {
                PermissionCard("Shizuku", R.drawable.ic_link, { help = "setup" }, subtitle = statusText) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (status) {
                            DebugAccess.Status.NOT_INSTALLED -> Button(onClick = onInstall, enabled = !working, modifier = Modifier.weight(1f)) {
                                Text(AppText.get(R.string.debug_install_short))
                            }
                            DebugAccess.Status.NEED_PERMISSION -> Button(onClick = onAuthorize, enabled = !working, modifier = Modifier.weight(1f)) {
                                Text(AppText.get(R.string.debug_authorize_short))
                            }
                            else -> OutlinedButton(onClick = onManager, enabled = !working, modifier = Modifier.weight(1f)) {
                                Text(AppText.get(R.string.debug_open_manager))
                            }
                        }
                        TextButton(onClick = { help = "setup" }, modifier = Modifier.weight(1f)) {
                            Text(AppText.get(R.string.debug_setup_help))
                        }
                    }
                }
                }
                ManagerSection(AppText.get(R.string.group_background), padded = false) {
                PermissionCard(AppText.get(R.string.debug_children_title), R.drawable.ic_terminal, { help = "children" }) {
                    if (Build.VERSION.SDK_INT < 31) Text(AppText.get(R.string.debug_limit_old_android), style = MaterialTheme.typography.bodySmall)
                    else {
                        val narrow = LocalDensity.current.fontScale > 1.3f
                        if (narrow) {
                            PermissionMetricLine(R.string.debug_count_app, appCount)
                            PermissionMetricLine(R.string.debug_count_system, systemCount)
                            PermissionMetricLine(R.string.debug_count_limit, effective)
                        } else Row(Modifier.fillMaxWidth()) {
                            PermissionMetric(R.string.debug_count_app, appCount, Modifier.weight(1f))
                            PermissionMetric(R.string.debug_count_system, systemCount, Modifier.weight(1f))
                            PermissionMetric(R.string.debug_count_limit, effective, Modifier.weight(1f))
                        }
                        val monitored = report?.optString("monitorSetting") != "false"
                        if (effective != null && effective > 0 && systemCount != null && monitored) {
                            LinearProgressIndicator(progress = { (systemCount.toFloat() / effective).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(), color = if (systemCount >= effective) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        Text(AppText.get(R.string.debug_configured_short, configured), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val countMessage = when {
                            !ready -> R.string.debug_counts_need_access
                            busy && report == null -> R.string.debug_counts_loading
                            !countReadable -> R.string.debug_counts_unavailable
                            else -> null
                        }
                        countMessage?.let { Text(AppText.get(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!monitored) Text(AppText.get(R.string.debug_monitor_disabled), style = MaterialTheme.typography.bodySmall)
                        if (ready && report != null && effective == null) Text(AppText.get(R.string.debug_rom_not_exposed), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onLimitChange(false) }, enabled = ready && !working, modifier = Modifier.weight(1f)) {
                                Text(AppText.get(R.string.debug_limit_raise_short))
                            }
                            OutlinedButton(onClick = { onLimitChange(true) }, enabled = ready && !working, modifier = Modifier.weight(1f)) {
                                Text(AppText.get(R.string.debug_limit_restore_short))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                PermissionCard(AppText.get(R.string.debug_battery_title), R.drawable.ic_battery, { help = "battery" }, accessory = {
                    Switch(checked = batteryExempt, onCheckedChange = onBatteryChange,
                        enabled = ready && !working && (!batteryExempt || report?.optJSONObject("background")?.optBoolean("userWhitelisted") == true))
                }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppText.get(if (batteryExempt) R.string.debug_battery_on_short else R.string.debug_battery_off_short),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (batteryExempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onBatterySettings, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(AppText.get(R.string.debug_battery_system_short))
                        }
                    }
                }
                }
                ManagerSection(AppText.get(R.string.group_device_bridge), padded = false) {
                    ManagerSettingsRow(AppText.get(R.string.device_access_title), R.drawable.ic_terminal,
                        AppText.get(R.string.device_access_screen) + " · " + AppText.get(R.string.device_access_control), divider = false, onClick = onDeviceAccess)
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = !working, modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_restart), null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(AppText.get(R.string.debug_refresh))
                    }
                    TextButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text(AppText.get(R.string.debug_copy_diagnostic))
                    }
                }
                if (counts != null && counts.optLong("checkedAtMillis") > 0) Text(
                    AppText.get(R.string.debug_checked_at, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(counts.getLong("checkedAtMillis")))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    help?.let { topic ->
        AlertDialog(onDismissRequest = { help = null },
            title = { Text(if (topic == "setup") "Shizuku" else AppText.get(if (topic == "children") R.string.debug_children_title else R.string.debug_battery_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppText.get(when (topic) {
                        "setup" -> if (Build.VERSION.SDK_INT >= 30) R.string.debug_setup_wireless else R.string.debug_setup_adb
                        "children" -> R.string.debug_counts_explanation
                        else -> R.string.debug_battery_detail
                    }))
                    if (topic == "children") Text(AppText.get(R.string.debug_limit_detail))
                    if (topic == "setup") {
                        if (ready) runCatching { AppText.get(R.string.debug_server_identity, Shizuku.getVersion(), Shizuku.getUid()) }.getOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { help = null; onDeveloperSettings() }) { Text(AppText.get(R.string.debug_developer_settings)) }
                        TextButton(onClick = { help = null; onOfficialSite() }) { Text(AppText.get(R.string.debug_official_site)) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { help = null }) { Text(AppText.get(R.string.ui_ok)) } })
    }
}

@Composable
private fun PermissionCard(title: String, icon: Int, onHelp: () -> Unit, subtitle: String? = null,
    accessory: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManagerGlyph(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                accessory()
                IconButton(onClick = onHelp, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_info), AppText.get(R.string.debug_more_info, title), Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun PermissionMetric(label: Int, value: Int?, modifier: Modifier) {
    Column(modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall.copy(fontSize = if ((value?.toString()?.length ?: 0) > 6) 17.sp else 24.sp),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(AppText.get(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionMetricLine(label: Int, value: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(AppText.get(label), style = MaterialTheme.typography.bodyMedium)
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
