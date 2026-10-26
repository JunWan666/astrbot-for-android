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

internal enum class BackgroundTopic(val title: String) {
    NOTIFICATIONS("运行通知"), BATTERY("忽略电池优化"), UNRESTRICTED("后台耗电与无限制"),
    AUTOSTART("自启动与关联启动"), TASKS("锁定最近任务"), HELP("后台运行说明")
}

internal object BackgroundGuide {
    private const val CHANNEL = "local-runtime"
    fun status(context: Context, topic: BackgroundTopic): String = when (topic) {
        BackgroundTopic.NOTIFICATIONS -> context.getSystemService(NotificationManager::class.java).let {
            when {
                !it.areNotificationsEnabled() -> "通知未允许，建议开启"
                it.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "应用通知已允许，但运行通知类别已关闭"
                else -> "运行通知已允许"
            }
        }
        BackgroundTopic.BATTERY -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "已不受系统电池优化限制" else "仍受系统电池优化限制"
        BackgroundTopic.UNRESTRICTED -> "允许后台活动，关闭应用省电限制"
        BackgroundTopic.AUTOSTART -> "厂商权限，需到系统中确认"
        BackgroundTopic.TASKS -> "保留任务卡片，减少一键清理影响"
        BackgroundTopic.HELP -> "查看 ${Build.MANUFACTURER} 设置步骤与限制"
    }

    fun steps(topic: BackgroundTopic, manufacturer: String = Build.MANUFACTURER): String {
        val vendor = manufacturer.lowercase(java.util.Locale.ROOT)
        val path = when {
            "vivo" in vendor || "iqoo" in vendor -> "vivo / iQOO：设置 → 电池 → 后台耗电管理，找到本应用并选择“允许后台高耗电”；在 i 管家 → 应用管理 → 权限管理 → 自启动，允许本应用。不同系统版本也可能位于“应用与权限”。"
            "xiaomi" in vendor || "redmi" in vendor || "poco" in vendor -> "小米 / Redmi：设置 → 应用设置 → 应用管理 → 本应用，开启“自启动”；在“省电策略”选择“无限制”。HyperOS 可在“后台自启动”管理中开启。"
            "huawei" in vendor || "honor" in vendor -> "华为 / 荣耀：设置 → 应用 → 应用启动管理，关闭本应用的自动管理，手动允许“自启动、关联启动、后台活动”；电池设置中允许后台运行。"
            "oppo" in vendor || "realme" in vendor || "oneplus" in vendor -> "OPPO / realme / 一加：设置 → 应用 → 应用管理 → 本应用，开启“允许自启动、允许关联启动、允许后台活动”；在电池耗电管理中选择“不限制”。"
            "samsung" in vendor -> "三星：设置 → 应用程序 → 本应用 → 电池，选择“不受限制”；在设备维护 → 电池 → 后台使用限制中，将本应用加入“从不休眠的应用程序”。"
            else -> "设置 → 应用 → 本应用 → 电池，选择“不受限制”或“允许后台活动”。如果系统有自启动、后台耗电或应用启动管理，请允许本应用。"
        }
        val introduction = when (topic) {
            BackgroundTopic.NOTIFICATIONS -> "1. 打开本应用通知设置，允许发送通知。\n2. 向下查看通知类别，保持“本地服务运行”开启。部分系统把类别设置合并在应用通知页中。\n3. 服务启动后检查通知栏，可查看状态和停止服务。"
            BackgroundTopic.BATTERY -> "1. 点击下方按钮，直接申请本应用不受系统电池优化限制。\n2. 在系统确认窗口选择“允许”。若已经允许，将打开优化列表，可切换“所有应用”查看。\n3. 返回后本页自动刷新系统状态。厂商后台耗电还需单独设置。"
            BackgroundTopic.UNRESTRICTED -> "1. 打开厂商后台耗电管理，找到本应用。\n2. 选择“允许后台高耗电 / 无限制 / 不限制”，或允许后台活动。\n3. 也可打开“本应用电池设置”，检查单应用限制。"
            BackgroundTopic.AUTOSTART -> "允许本应用自启动、关联启动，有助于系统在后台恢复应用。该授权不等于本应用已经设置开机运行；设备重启后请解锁并打开应用确认服务。"
            BackgroundTopic.TASKS -> "1. 点击下方按钮返回桌面，再使用最近任务键或手势打开任务列表。\n2. 找到本应用卡片，通过长按、菜单或下拉卡片选择“锁定”。\n3. 以后清理任务时保留此卡片。\n\n系统没有通用的直接锁定接口，需在任务列表中操作。"
            BackgroundTopic.HELP -> "推荐依次检查通知、电池优化、后台耗电、自启动与最近任务锁定。运行时保持服务通知，不要在系统应用详情中强行停止。"
        }
        return "$introduction\n\n${if (topic in setOf(BackgroundTopic.AUTOSTART, BackgroundTopic.UNRESTRICTED, BackgroundTopic.HELP)) path + "\n\n" else ""}设置名称随系统版本不同。自启动、后台耗电和任务锁定通常没有公开查询接口，请在系统页面确认；它们不能保证系统强行停止后自动恢复。"
    }

    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun appBattery(context: Context) = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:${context.packageName}"))
    fun channelSettings(context: Context) = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)

    fun buttonLabel(context: Context, topic: BackgroundTopic): String = when (topic) {
        BackgroundTopic.NOTIFICATIONS -> "打开本应用通知设置"
        BackgroundTopic.BATTERY -> if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "查看电池优化列表" else "申请本应用不优化"
        BackgroundTopic.UNRESTRICTED -> "打开后台耗电设置"
        BackgroundTopic.AUTOSTART -> if (intents(context, topic).firstOrNull()?.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            "打开应用详情并按步骤设置" else "打开自启动管理"
        BackgroundTopic.TASKS -> "返回桌面后锁定任务"
        BackgroundTopic.HELP -> "知道了"
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
            result < 0 -> "系统未开放此入口，请按上方路径手动设置。"
            result > 0 -> "直达入口不可用，已打开备用设置页；请按上方路径继续。"
            else -> null
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(selected.title) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前手机：${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("要设置的应用：${context.applicationInfo.loadLabel(context.packageManager)}", style = MaterialTheme.typography.bodyMedium)
            Text(remember(selected, revision) { BackgroundGuide.status(context, selected) }, color = MaterialTheme.colorScheme.primary)
            Text(BackgroundGuide.steps(selected))
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (selected == BackgroundTopic.HELP) BackgroundTopic.entries.filter { it != BackgroundTopic.HELP }.forEach { item ->
                OutlinedButton(onClick = { selected = item }, Modifier.fillMaxWidth()) { Text(item.title + " ›") }
            }
            if (selected == BackgroundTopic.UNRESTRICTED)
                OutlinedButton(onClick = { open(listOf(BackgroundGuide.appBattery(context), BackgroundGuide.appDetails(context))) }, Modifier.fillMaxWidth()) { Text("打开本应用电池设置") }
            // Current vivo firmware redirects the channel action to the same app-wide page.
            if (selected == BackgroundTopic.NOTIFICATIONS && !Build.MANUFACTURER.equals("vivo", true) &&
                !Build.MANUFACTURER.equals("iqoo", true) && context.getSystemService(NotificationManager::class.java).getNotificationChannel("local-runtime") != null)
                OutlinedButton(onClick = { open(listOf(BackgroundGuide.channelSettings(context), BackgroundGuide.appDetails(context))) }, Modifier.fillMaxWidth()) { Text("打开运行通知类别") }
            if (selected != BackgroundTopic.TASKS && selected != BackgroundTopic.HELP)
                TextButton(onClick = { open(listOf(BackgroundGuide.appDetails(context))) }) { Text("打开应用详情（备用）") }
            if (topic == BackgroundTopic.HELP && selected != topic)
                TextButton(onClick = { selected = topic }) { Text("返回后台设置清单") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            when (selected) {
                BackgroundTopic.HELP -> onDismiss()
                BackgroundTopic.TASKS -> {
                    if (context is Activity) context.moveTaskToBack(true)
                    feedback = "请使用最近任务键或手势找到本应用，打开卡片菜单并锁定。"
                }
                else -> open(BackgroundGuide.intents(context, selected))
            }
        }) {
            Text(remember(selected, revision) { BackgroundGuide.buttonLabel(context, selected) })
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
