package io.astrbot.android

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.astrbot.android.core.ProcessState
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.ManagerState
import io.astrbot.android.runtime.NetworkSnapshot
import io.astrbot.android.runtime.InternetState
import io.astrbot.android.runtime.ResourceSnapshot
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date

internal enum class ManagerPage(val label: String, val icon: Int) {
    HOME("首页", R.drawable.ic_home),
    PERSONAS("人设", R.drawable.ic_persona),
    SETTINGS("设置", R.drawable.ic_settings),
}

@Composable internal fun ManagerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF91B2FF), onPrimary = Color(0xFF0B2860),
        primaryContainer = Color(0xFF24365B), onPrimaryContainer = Color(0xFFDCE6FF),
        background = Color(0xFF111319), surface = Color(0xFF111319),
        onSurface = Color(0xFFE9EBF1), onSurfaceVariant = Color(0xFFA0A7B7),
        surfaceContainer = Color(0xFF252A34), surfaceContainerLow = Color(0xFF1B1F28),
        outlineVariant = Color(0xFF303642),
    ) else lightColorScheme(
        primary = Color(0xFF386BF6), onPrimary = Color.White,
        primaryContainer = Color(0xFFEBF1FF), onPrimaryContainer = Color(0xFF305BC9),
        background = Color(0xFFF5F6FA), surface = Color(0xFFF5F6FA),
        onSurface = Color(0xFF202536), onSurfaceVariant = Color(0xFF7B8294),
        surfaceContainer = Color(0xFFF1F3F8), surfaceContainerLow = Color.White,
        outlineVariant = Color(0xFFEDF0F5),
    )
    val typography = Typography(
        headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = content)
}

@Composable internal fun ManagerHeader(page: ManagerPage, state: ManagerState, title: String? = null, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = if (onBack == null) 20.dp else 8.dp, vertical = if (onBack == null) 12.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "返回上一级", Modifier.size(20.dp)) }
            if (page == ManagerPage.HOME && title == null) ManagerAppLogo(Modifier.size(30.dp))
            Text(title ?: if (page == ManagerPage.HOME) "AstrBot" else page.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (title == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
            if (page == ManagerPage.HOME && title == null) Text("运行管理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.busy) {
            Text(state.phase, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable internal fun ManagerBottomBar(page: ManagerPage, onSelect: (ManagerPage) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
                ManagerPage.entries.forEach { destination ->
                    val active = page == destination
                    Column(Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { onSelect(destination) })
                        .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(painterResource(destination.icon), null, Modifier.size(22.dp),
                            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(destination.label, Modifier.clearAndSetSemantics { contentDescription = "${destination.label}页面" },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun ManagerGlyph(icon: Int, color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(34.dp).background(color.copy(alpha = 0.09f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(19.dp), tint = color)
    }
}

@Composable internal fun ManagerAppLogo(modifier: Modifier = Modifier.size(32.dp)) {
    Image(painterResource(R.drawable.app_logo), "应用图标", modifier.clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
}

@Composable internal fun ManagerServiceLogo(id: ServiceId) {
    val color = if (id == ServiceId.ASTRBOT) Color(0xFF2F86BD) else Color(0xFF9270D7)
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center) {
        Image(painterResource(if (id == ServiceId.ASTRBOT) R.drawable.logo_astrbot else R.drawable.logo_napcat),
            contentDescription = "${id.label} 标识", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(if (id == ServiceId.ASTRBOT) 2.dp else 1.dp))
    }
}

@Composable internal fun ManagerNotice(title: String, text: String, error: Boolean = false, onClick: () -> Unit) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.07f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(17.dp), tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = color)
                Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(14.dp), tint = color)
        }
    }
}

@Composable internal fun ManagerSection(title: String, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(if (padded) Modifier.padding(16.dp) else Modifier,
                verticalArrangement = Arrangement.spacedBy(if (padded) 10.dp else 0.dp), content = content)
        }
    }
}

@Composable internal fun ManagerSettingsRow(title: String, icon: Int, subtitle: String? = null, value: String? = null,
    enabled: Boolean = true, divider: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagerGlyph(icon, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable internal fun ManagerProcessBadge(process: ProcessState) {
    val color = when (process) {
        ProcessState.RUNNING -> if (isSystemInDarkTheme()) Color(0xFF73D6AE) else Color(0xFF159B71)
        ProcessState.CRASHED -> MaterialTheme.colorScheme.error
        ProcessState.STARTING, ProcessState.STOPPING -> Color(0xFFC68B27)
        ProcessState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.09f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(5.dp).background(color, RoundedCornerShape(5.dp)))
            Text(when (process) {
                ProcessState.STOPPED -> "已停止"; ProcessState.STARTING -> "启动中"; ProcessState.RUNNING -> "运行中"
                ProcessState.STOPPING -> "停止中"; ProcessState.CRASHED -> "异常退出"
            }, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable internal fun ManagerOverview(state: ManagerState, network: NetworkSnapshot, onCommand: (String) -> Unit) {
    val running = state.services.values.count { it.process == ProcessState.RUNNING }
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF386BF6), Color(0xFF5686FF))),
        RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("服务概览", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(if (running == 2) "两个服务进程运行中" else if (running == 0) "启动服务，开启手机上的机器人" else "部分服务正在运行",
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$running", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 34.sp, lineHeight = 38.sp), color = Color.White)
                Text("/ 2", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(network.title, Modifier.semantics { contentDescription = "系统网络状态" },
                style = MaterialTheme.typography.labelMedium, color = Color.White)
            if (network.state != InternetState.VALIDATED) Text(network.detail,
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val font = MaterialTheme.typography.labelMedium.fontSize
            val labelWidth = with(LocalDensity.current) { font.toDp() * 4 }
            val column = maxWidth < labelWidth * 3 + 16.dp
            val stackedIcons = !column && maxWidth < (labelWidth + 18.dp) * 3 + 16.dp
            val commands = listOf(Triple("start", "启动全部", R.drawable.ic_play), Triple("stop", "停止全部", R.drawable.ic_stop),
                Triple("restart", "重启全部", R.drawable.ic_restart))
            if (column) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                commands.forEach { (operation, label, icon) ->
                    ManagerOverviewCommand(operation, label, icon, state, false, Modifier.fillMaxWidth(), onCommand)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                commands.forEach { (operation, label, icon) ->
                    ManagerOverviewCommand(operation, label, icon, state, stackedIcons, Modifier.weight(1f), onCommand)
                }
            }
        }
    }
}

@Composable internal fun ManagerResources(snapshot: ResourceSnapshot, compact: Boolean = false) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
    val memory = snapshot.memory
    val amount = memory.totalKiB ?: memory.partialKiB
    Surface(Modifier.fillMaxWidth().clickable { details = true }, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本应用内存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(size(amount?.let { it * 1024 }), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("手机总内存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(size(snapshot.physicalMemoryBytes), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("应用占比", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(snapshot.memoryPercent?.let { String.format(java.util.Locale.getDefault(), "%.1f%%", it) } ?: "—",
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                }
            }
            val status = when {
                snapshot.checkedAtMillis == null -> "正在读取资源信息"
                memory.complete -> "已统计 ${memory.measured} 个进程"
                memory.partialKiB != null -> "部分统计 · ${memory.measured}/${memory.observed} 个进程"
                else -> "暂时无法读取内存"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                snapshot.checkedAtMillis?.takeIf { !compact }?.let {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_info), "资源统计说明", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("资源统计说明") }, text = {
        Text("本应用内存为管理器及同一应用用户下托管进程的 PSS 合计，共享内存按比例计算。包含 AstrBot、NapCat 和运行中的扩展；不含系统隔离的 WebView 渲染进程。\n\n手机总内存是 Android 报告的物理内存，不含存储和内存扩展。应用占比为完整 PSS 合计除以手机总内存；部分统计或读取失败时不显示占比。\n\n首页可见时每 15 秒更新，离开首页或进入后台暂停。存储容量可在设置的存储使用页查看。")
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("知道了") } })
}

@Composable internal fun ManagerOverviewCommand(operation: String, label: String, icon: Int, state: ManagerState,
    stackedIcons: Boolean, modifier: Modifier, onCommand: (String) -> Unit) {
    val enabled = if (operation == "stop") state.canStop()
        else (state.componentsReady || operation == "start" && BuildConfig.BUNDLED_RUNTIME) && !state.busy
    Button(onClick = { onCommand(operation) }, modifier.heightIn(min = 44.dp), enabled = enabled,
        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (operation == "start") Color.White else Color.White.copy(alpha = .20f),
            contentColor = if (operation == "start") Color(0xFF386BF6) else Color.White,
            disabledContainerColor = Color.White.copy(alpha = .12f), disabledContentColor = Color.White.copy(alpha = .55f))) {
        if (stackedIcons) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(icon), null, Modifier.size(16.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), null, Modifier.size(14.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
        }
    }
}
