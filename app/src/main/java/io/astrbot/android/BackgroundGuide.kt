package io.astrbot.android

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal enum class BackgroundTopic(private val titleResource: Int) {
    NOTIFICATIONS(R.string.ui_service_notifications), BATTERY(R.string.ui_battery_optimization), UNRESTRICTED(R.string.ui_background_battery_limits),
    AUTOSTART(R.string.ui_auto_start_permissions), TASKS(R.string.ui_lock_recent_task), HELP(R.string.ui_background_operation_guide)
;
    val title: String get() = AppText.get(titleResource)
}

internal object BackgroundGuide {
    private const val CHANNEL = "local-runtime"
    fun status(context: Context, topic: BackgroundTopic): String = when (topic) {
        BackgroundTopic.NOTIFICATIONS -> context.getSystemService(NotificationManager::class.java).let {
            when {
                !it.areNotificationsEnabled() -> AppText.get(R.string.ui_notifications_are_blocked_enable_them)
                it.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> AppText.get(R.string.ui_app_notifications_are_allowed_but_the_service_notification_channel)
                else -> AppText.get(R.string.ui_service_notifications_are_allowed)
            }
        }
        BackgroundTopic.BATTERY -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) AppText.get(R.string.ui_exempt_from_system_battery_optimization) else AppText.get(R.string.ui_system_battery_optimization_still_applies)
        BackgroundTopic.UNRESTRICTED -> AppText.get(R.string.ui_allow_background_activity_and_remove_app_battery_limits)
        BackgroundTopic.AUTOSTART -> AppText.get(R.string.ui_check_manufacturer_permissions_in_system_settings)
        BackgroundTopic.TASKS -> AppText.get(R.string.ui_keep_the_app_task_to_reduce_accidental_cleanup)
        BackgroundTopic.HELP -> AppText.get(R.string.ui_view_setup_steps_and_limitations, Build.MANUFACTURER)
    }

    fun steps(topic: BackgroundTopic, manufacturer: String = Build.MANUFACTURER): String {
        val vendor = manufacturer.lowercase(java.util.Locale.ROOT)
        val path = when {
            "vivo" in vendor || "iqoo" in vendor -> AppText.get(R.string.ui_vivo_iqoo_settings_battery_background_power_consumption_management_find)
            "xiaomi" in vendor || "redmi" in vendor || "poco" in vendor -> AppText.get(R.string.ui_xiaomi_redmi_settings_apps_manage_apps_this_app_enable)
            "huawei" in vendor || "honor" in vendor -> AppText.get(R.string.ui_huawei_honor_settings_apps_app_launch_disable_automatic_management)
            "oppo" in vendor || "realme" in vendor || "oneplus" in vendor -> AppText.get(R.string.ui_oppo_realme_oneplus_settings_apps_app_management_this_app)
            "samsung" in vendor -> AppText.get(R.string.ui_samsung_settings_apps_this_app_battery_unrestricted_in_device)
            else -> AppText.get(R.string.ui_settings_apps_this_app_battery_choose_unrestricted_or_allow)
        }
        val introduction = when (topic) {
            BackgroundTopic.NOTIFICATIONS -> AppText.get(R.string.ui_1_open_notification_settings_for_this_app_and_allow)
            BackgroundTopic.BATTERY -> AppText.get(R.string.ui_1_use_the_button_below_to_request_exemption_from)
            BackgroundTopic.UNRESTRICTED -> AppText.get(R.string.ui_1_open_manufacturer_background_battery_management_and_find_this)
            BackgroundTopic.AUTOSTART -> AppText.get(R.string.ui_allowing_auto_start_and_secondary_launch_helps_the_system)
            BackgroundTopic.TASKS -> AppText.get(R.string.ui_1_return_to_the_home_screen_then_open_recent)
            BackgroundTopic.HELP -> AppText.get(R.string.ui_check_notifications_battery_optimization_background_battery_limits_auto)
        }
        return AppText.get(R.string.ui_n_nsetting_names_vary_by_system_version_auto_start, introduction, if (topic in setOf(BackgroundTopic.AUTOSTART, BackgroundTopic.UNRESTRICTED, BackgroundTopic.HELP)) path + "\n\n" else "")
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
        if (topic == BackgroundTopic.NOTIFICATIONS) return listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), detail)
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

@Composable internal fun BackgroundGuideDialog(topic: BackgroundTopic, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(topic) { mutableStateOf(topic) }
    var feedback by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(candidates: List<Intent>) {
        val result = BackgroundGuide.open(context, candidates)
        feedback = when {
            result < 0 -> AppText.get(R.string.ui_this_shortcut_is_unavailable_follow_the_steps_above_to)
            result > 0 -> AppText.get(R.string.ui_opened_an_alternative_settings_page_follow_the_steps_above)
            else -> null
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(selected.title) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(R.string.ui_device, Build.MANUFACTURER, Build.MODEL), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(AppText.get(R.string.ui_app_to_configure, context.applicationInfo.loadLabel(context.packageManager)), style = MaterialTheme.typography.bodyMedium)
            Text(remember(selected, revision) { BackgroundGuide.status(context, selected) }, color = MaterialTheme.colorScheme.primary)
            Text(BackgroundGuide.steps(selected))
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (selected == BackgroundTopic.HELP) BackgroundTopic.entries.filter { it != BackgroundTopic.HELP }.forEach { item ->
                OutlinedButton(onClick = { selected = item }, Modifier.fillMaxWidth()) { Text(item.title + " ›") }
            }
            if (selected == BackgroundTopic.UNRESTRICTED)
                OutlinedButton(onClick = { open(listOf(BackgroundGuide.appBattery(context), BackgroundGuide.appDetails(context))) }, Modifier.fillMaxWidth()) { Text(AppText.get(R.string.ui_open_app_battery_settings)) }
            // Current vivo firmware redirects the channel action to the same app-wide page.
            if (selected == BackgroundTopic.NOTIFICATIONS && !Build.MANUFACTURER.equals("vivo", true) &&
                !Build.MANUFACTURER.equals("iqoo", true) && context.getSystemService(NotificationManager::class.java).getNotificationChannel("local-runtime") != null)
                OutlinedButton(onClick = { open(listOf(BackgroundGuide.channelSettings(context), BackgroundGuide.appDetails(context))) }, Modifier.fillMaxWidth()) { Text(AppText.get(R.string.ui_open_runtime_notification_settings)) }
            if (selected != BackgroundTopic.TASKS && selected != BackgroundTopic.HELP)
                TextButton(onClick = { open(listOf(BackgroundGuide.appDetails(context))) }) { Text(AppText.get(R.string.ui_open_app_info_alternative)) }
            if (topic == BackgroundTopic.HELP && selected != topic)
                TextButton(onClick = { selected = topic }) { Text(AppText.get(R.string.ui_back_to_background_settings)) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            when (selected) {
                BackgroundTopic.HELP -> onDismiss()
                BackgroundTopic.TASKS -> {
                    if (context is Activity) context.moveTaskToBack(true)
                    feedback = AppText.get(R.string.ui_find_this_app_in_recent_apps_open_its_card)
                }
                else -> open(BackgroundGuide.intents(context, selected))
            }
        }) {
            Text(remember(selected, revision) { BackgroundGuide.buttonLabel(context, selected) })
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) } })
}
