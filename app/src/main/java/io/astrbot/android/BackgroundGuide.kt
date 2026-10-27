package io.astrbot.android

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal enum class BackgroundTopic(private val titleResource: Int) {
    NOTIFICATIONS(R.string.ui_service_notifications), BATTERY(R.string.ui_battery_optimization), UNRESTRICTED(R.string.ui_background_battery_limits),
    AUTOSTART(R.string.ui_auto_start_permissions), TASKS(R.string.ui_lock_recent_task), HELP(R.string.ui_background_operation_guide)
;
    val title: String get() = AppText.get(titleResource)
    val manual: Boolean get() = this == UNRESTRICTED || this == AUTOSTART || this == TASKS
}

internal data class BackgroundSettingStatus(val complete: Boolean, val manual: Boolean, val label: String, val detail: String)

internal object BackgroundGuide {
    private const val CHANNEL = "local-runtime"
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("background-guide", Context.MODE_PRIVATE)

    fun setConfirmed(context: Context, topic: BackgroundTopic, confirmed: Boolean) {
        require(topic.manual) { "System permissions must be read from Android" }
        preferences(context).edit().putBoolean(topic.name, confirmed).apply()
    }

    fun status(context: Context, topic: BackgroundTopic): BackgroundSettingStatus = when (topic) {
        BackgroundTopic.NOTIFICATIONS -> context.getSystemService(NotificationManager::class.java).let {
            val allowed = it.areNotificationsEnabled()
            val channelBlocked = it.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
            BackgroundSettingStatus(allowed && !channelBlocked, false,
                AppText.get(if (allowed && !channelBlocked) R.string.background_enabled else R.string.background_needs_setting),
                AppText.get(when {
                    !allowed -> R.string.ui_notifications_are_blocked_enable_them
                    channelBlocked -> R.string.ui_app_notifications_are_allowed_but_the_service_notification_channel
                    else -> R.string.ui_service_notifications_are_allowed
                }))
        }
        BackgroundTopic.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName).let { allowed ->
            BackgroundSettingStatus(allowed, false,
                AppText.get(if (allowed) R.string.background_allowed else R.string.background_needs_setting),
                AppText.get(if (allowed) R.string.ui_exempt_from_system_battery_optimization else R.string.ui_system_battery_optimization_still_applies))
        }
        else -> {
            require(topic.manual)
            val confirmed = preferences(context).getBoolean(topic.name, false)
            BackgroundSettingStatus(confirmed, true,
                AppText.get(if (confirmed) R.string.background_confirmed else R.string.background_needs_confirmation),
                AppText.get(if (confirmed) R.string.background_confirmation_record else R.string.background_manual_status_hint))
        }
    }

    fun steps(topic: BackgroundTopic, manufacturer: String = Build.MANUFACTURER): String {
        val vendor = manufacturer.lowercase(java.util.Locale.ROOT)
        val family = when {
            "vivo" in vendor || "iqoo" in vendor -> "vivo"
            "xiaomi" in vendor || "redmi" in vendor || "poco" in vendor -> "xiaomi"
            "huawei" in vendor || "honor" in vendor -> "huawei"
            "oppo" in vendor || "realme" in vendor || "oneplus" in vendor -> "oppo"
            "samsung" in vendor -> "samsung"
            else -> "generic"
        }
        return AppText.get(when (topic) {
            BackgroundTopic.NOTIFICATIONS -> R.string.background_steps_notifications
            BackgroundTopic.BATTERY -> R.string.background_steps_battery
            BackgroundTopic.TASKS -> R.string.background_steps_tasks
            BackgroundTopic.HELP -> R.string.background_help
            BackgroundTopic.UNRESTRICTED -> when (family) {
                "vivo" -> R.string.background_battery_vivo
                "xiaomi" -> R.string.background_battery_xiaomi
                "huawei" -> R.string.background_battery_huawei
                "oppo" -> R.string.background_battery_oppo
                "samsung" -> R.string.background_battery_samsung
                else -> R.string.background_battery_generic
            }
            BackgroundTopic.AUTOSTART -> when (family) {
                "vivo" -> R.string.background_autostart_vivo
                "xiaomi" -> R.string.background_autostart_xiaomi
                "huawei" -> R.string.background_autostart_huawei
                "oppo" -> R.string.background_autostart_oppo
                "samsung" -> R.string.background_autostart_samsung
                else -> R.string.background_autostart_generic
            }
        })
    }

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun appBattery(context: Context) = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:${context.packageName}"))
    fun channelSettings(context: Context) = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)

    fun buttonLabel(context: Context, topic: BackgroundTopic): String = when (topic) {
        BackgroundTopic.NOTIFICATIONS -> AppText.get(R.string.ui_open_app_notification_settings)
        BackgroundTopic.BATTERY -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) AppText.get(R.string.ui_view_battery_optimization_list) else AppText.get(R.string.ui_request_battery_exemption)
        BackgroundTopic.UNRESTRICTED -> AppText.get(R.string.ui_open_background_battery_settings)
        BackgroundTopic.AUTOSTART -> if (intents(context, topic).firstOrNull()?.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            AppText.get(R.string.ui_open_app_details_and_follow_the_steps) else AppText.get(R.string.ui_open_auto_start_management)
        BackgroundTopic.TASKS -> AppText.get(R.string.ui_go_home_then_lock_the_recent_task)
        BackgroundTopic.HELP -> AppText.get(R.string.ui_ok)
    }

    fun intents(context: Context, topic: BackgroundTopic, manufacturer: String = Build.MANUFACTURER,
                ignoringBattery: Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)): List<Intent> {
        val detail = appDetails(context)
        if (topic == BackgroundTopic.NOTIFICATIONS) return buildList {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
                add(channelSettings(context))
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(detail)
        }
        if (topic == BackgroundTopic.BATTERY) return buildList {
            if (!ignoringBattery) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); add(detail)
        }
        if (topic == BackgroundTopic.TASKS || topic == BackgroundTopic.HELP) return emptyList()
        val vendor = manufacturer.lowercase(java.util.Locale.ROOT)
        val components = if (topic == BackgroundTopic.AUTOSTART) when {
            "vivo" in vendor || "iqoo" in vendor -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            "xiaomi" in vendor || "redmi" in vendor || "poco" in vendor -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "huawei" in vendor -> listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            "honor" in vendor -> listOf("com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            "oppo" in vendor || "realme" in vendor || "oneplus" in vendor -> listOf(
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            else -> emptyList()
        } else if ("vivo" in vendor || "iqoo" in vendor) listOf("com.iqoo.powersaving" to "com.iqoo.powersaving.BackgroundHighUsageActivity") else emptyList()
        return buildList {
            if (topic == BackgroundTopic.UNRESTRICTED && listOf("xiaomi", "redmi", "poco").any { it in vendor })
                add(Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").setPackage("com.miui.powerkeeper")
                    .putExtra("package_name", context.packageName).putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()))
            addAll(components.map { (pkg, name) -> Intent().setComponent(ComponentName(pkg, name))
                .putExtra("package_name", context.packageName).putExtra("packageName", context.packageName).putExtra("pkgName", context.packageName) })
            if (topic == BackgroundTopic.UNRESTRICTED) add(appBattery(context))
            add(detail)
        }
    }

    /** Try the actual launch; package visibility must not hide otherwise accessible settings. */
    fun open(context: Context, candidates: List<Intent>, launch: (Intent) -> Unit = { context.startActivity(it) }): Int {
        for ((index, intent) in candidates.withIndex()) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { launch(intent); return index }
            catch (_: ActivityNotFoundException) { /* Older or different ROM; try next route. */ }
            catch (_: SecurityException) { /* Some exported vendor pages require a system signature. */ }
        }
        return -1
    }
}

/** Refresh on return from Settings and whenever a manual confirmation changes. */
@Composable private fun rememberBackgroundStatuses(): Map<BackgroundTopic, BackgroundSettingStatus> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, owner) {
        val preferences = BackgroundGuide.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return remember(context, revision) {
        BackgroundTopic.entries.filter { it != BackgroundTopic.HELP }.associateWith { BackgroundGuide.status(context, it) }
    }
}

@Composable private fun BackgroundStatusBadge(status: BackgroundSettingStatus) {
    val dark = isSystemInDarkTheme()
    val color = when {
        !status.complete -> if (dark) Color(0xFFF2C16C) else Color(0xFF98600D)
        status.manual -> MaterialTheme.colorScheme.primary
        else -> if (dark) Color(0xFF75D8AF) else Color(0xFF12835E)
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = .10f)) {
        Text(status.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(max = 132.dp),
            style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable internal fun BackgroundSettings(modifier: Modifier = Modifier, onAction: (SettingAction) -> Unit) {
    val statuses = rememberBackgroundStatuses()
    val automaticCount = statuses.values.count { !it.manual && it.complete }
    val manualCount = statuses.values.count { it.manual && it.complete }
    val items = listOf(
        Triple(BackgroundTopic.NOTIFICATIONS, R.drawable.ic_info, SettingAction.NOTIFICATIONS),
        Triple(BackgroundTopic.BATTERY, R.drawable.ic_battery, SettingAction.BATTERY),
        Triple(BackgroundTopic.UNRESTRICTED, R.drawable.ic_battery, SettingAction.UNRESTRICTED),
        Triple(BackgroundTopic.AUTOSTART, R.drawable.ic_settings, SettingAction.AUTOSTART),
        Triple(BackgroundTopic.TASKS, R.drawable.ic_info, SettingAction.TASK_LOCK)
    ).map { (topic, icon, action) ->
        val status = statuses.getValue(topic)
        ManagerMenuItem(topic.title, icon,
            AppText.get(if (!topic.manual) R.string.background_auto_checked
                else if (status.complete) R.string.background_manual_recorded else R.string.background_manual_todo),
            badge = { BackgroundStatusBadge(status) }) { onAction(action) }
    } + ManagerMenuItem(BackgroundTopic.HELP.title, R.drawable.ic_document,
        AppText.get(R.string.ui_setup_recommendations_and_runtime_limitations)) { onAction(SettingAction.BACKGROUND_HELP) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppText.get(R.string.background_system_summary, automaticCount), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(AppText.get(R.string.background_manual_summary, manualCount), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                Text(AppText.get(R.string.background_summary_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FixedManagerMenu(items, Modifier.weight(1f))
    }
}

@Composable internal fun BackgroundGuideDialog(topic: BackgroundTopic, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val statuses = rememberBackgroundStatuses()
    val status = statuses[topic]
    var feedback by rememberSaveable(topic) { mutableStateOf<String?>(null) }
    fun openSettings() {
        val result = BackgroundGuide.open(context, BackgroundGuide.intents(context, topic))
        feedback = when {
            result < 0 -> AppText.get(R.string.ui_this_shortcut_is_unavailable_follow_the_steps_above_to)
            result > 0 -> AppText.get(R.string.ui_opened_an_alternative_settings_page_follow_the_steps_above)
            else -> null
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(topic.title) }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            status?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackgroundStatusBadge(it)
                        Text(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(AppText.get(R.string.ui_app_to_configure, context.applicationInfo.loadLabel(context.packageManager)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BackgroundGuide.steps(topic), style = MaterialTheme.typography.bodyMedium)
            if (topic.manual) {
                Row(Modifier.fillMaxWidth().toggleable(value = status!!.complete, role = Role.Checkbox,
                    onValueChange = { BackgroundGuide.setConfirmed(context, topic, it) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = status.complete, onCheckedChange = null)
                    Text(AppText.get(R.string.background_confirm_checkbox), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (topic == BackgroundTopic.UNRESTRICTED || topic == BackgroundTopic.AUTOSTART)
                Text(AppText.get(R.string.background_route_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (topic != BackgroundTopic.HELP) ManagerActionButton(
                BackgroundGuide.buttonLabel(context, topic), onClick = {
                    if (topic == BackgroundTopic.TASKS) {
                        if (context is Activity) context.moveTaskToBack(true)
                    } else openSettings()
                }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) }
        }
    })
}
