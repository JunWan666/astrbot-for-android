package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*

@Composable internal fun ServiceBottomBar(route: ServiceRoute, onSelect: (ServiceRoute) -> Unit) {
    NavigationBar {
        listOf(ServiceRoute.STATUS to R.drawable.ic_info, ServiceRoute.CONNECTION to R.drawable.ic_link,
            ServiceRoute.CREDENTIALS to R.drawable.ic_visibility, ServiceRoute.LOGS to R.drawable.ic_logs).forEach { (item, icon) ->
            NavigationBarItem(selected = (if (route == ServiceRoute.LAN) ServiceRoute.CONNECTION else route) == item,
                onClick = { onSelect(item) }, icon = { Icon(painterResource(icon), null) }, label = { Text(item.title) },
                modifier = Modifier.semantics { contentDescription = "服务${item.title}页面" })
        }
    }
}

@Composable internal fun FixedServiceDetails(id: ServiceId, route: ServiceRoute, state: ManagerState, addresses: List<String>,
    onNavigate: (ServiceRoute) -> Unit, onCommand: (String) -> Unit, onOpen: () -> Unit, onLogs: () -> Unit,
    onLan: (Boolean) -> Unit, onExternal: (String) -> Unit, onCopy: (String, String) -> Unit,
    onNotice: (String, String) -> Unit, credentialContent: @Composable () -> Unit) {
    val status = state.services.getValue(id)
    val connection = state.webUis.getValue(id)
    val endpoint = connection.endpoint
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "服务详情内容" }) {
    val short = maxHeight < 400.dp || LocalDensity.current.fontScale > 1.25f
    val sideBySide = maxWidth >= 580.dp && maxHeight < 450.dp
    @Composable fun statusActions() {
        ManagerSection(id.label) {
            Text(status.summary(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!short) Text("版本 ${state.versions[id.label] ?: "未知"}", style = MaterialTheme.typography.bodySmall)
        }
        ManagerActionButton("打开后台", onOpen, Modifier.fillMaxWidth(), enabled = status.canOpenWebUi, description = "${id.label} 打开后台")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerActionButton("启动", { onCommand("start") }, Modifier.weight(1f), ActionTone.START, state.componentsReady && !state.busy, "启动 ${id.label}")
            ManagerActionButton("停止", { onCommand("stop") }, Modifier.weight(1f), ActionTone.STOP, state.canStop(id), "停止 ${id.label}")
            ManagerActionButton("重启", { onCommand("restart") }, Modifier.weight(1f), ActionTone.RESTART, state.componentsReady && !state.busy, "重启 ${id.label}")
        }
    }
    @Composable fun runtimeStatus() {
        fun time(value: Long?) = value?.let { java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: "暂无"
        fun record(event: ServiceEvent?) = event?.let { "${time(it.atMillis)} · ${it.summary}" } ?: "暂无记录"
        ManagerSection("运行状态", padded = false) {
          Column(Modifier.fillMaxWidth().clickable {
              onNotice("${id.label} 运行记录", "最近检查：${time(status.checkedAtMillis)}\n\n最近异常：${record(status.history.lastAnomaly)}\n\n最近恢复：${record(status.history.lastRecovery)}\n\n记录仅表示当时的观测结果，当前运行情况以上方状态为准。")
          }.semantics { contentDescription = "${id.label} 运行记录" }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("OneBot：${when (status.oneBot) {
                OneBotState.UNCONFIGURED -> "尚未配置"; OneBotState.CONNECTING -> "连接中"; OneBotState.CONNECTED -> "已连接"
                OneBotState.DISCONNECTED -> "未连接"; OneBotState.UNKNOWN -> "待确认"
            }}", style = MaterialTheme.typography.bodySmall)
            Text("最近检查 ${time(status.checkedAtMillis)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("最近恢复 ${record(status.history.lastRecovery)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("最近异常 ${record(status.history.lastAnomaly)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
    }
    @Composable fun localConnection() {
        ManagerSection("本机后台地址") {
            Text(endpoint?.origin ?: "首次启动后生成", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton("复制地址", { endpoint?.let { onCopy("后台地址", it.origin) } }, Modifier.weight(1f), ActionTone.NEUTRAL, endpoint != null)
                ManagerActionButton("外部浏览器", { endpoint?.let { onExternal(it.origin) } }, Modifier.weight(1f), enabled = status.canOpenWebUi && endpoint != null)
            }
        }
    }
    @Composable fun lanConnection() {
        ManagerSection("局域网访问") {
            Text(if (connection.lanEnabled) "已开启 · 同一网络可访问" else "仅允许手机本机访问", style = MaterialTheme.typography.bodyMedium)
            ManagerActionButton(if (connection.lanEnabled) "关闭 ${id.label} 局域网访问" else "开启 ${id.label} 局域网访问",
                { onLan(!connection.lanEnabled) }, Modifier.fillMaxWidth(), if (connection.lanEnabled) ActionTone.STOP else ActionTone.PRIMARY,
                state.componentsReady && !state.busy)
            if (connection.lanEnabled && endpoint != null) {
                val urls = addresses.map { "${endpoint.scheme}://$it:${endpoint.port}" }
                if (urls.isEmpty()) Text("暂无地址，请连接 Wi-Fi 或以太网", style = MaterialTheme.typography.bodySmall)
                else ManagerActionButton("复制局域网地址", { onCopy("${id.label} 局域网地址", urls.joinToString("\n")) }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                urls.firstOrNull()?.let { Text(it + if (urls.size > 1) "（共 ${urls.size} 个）" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp)) {
        when (route) {
            ServiceRoute.STATUS, ServiceRoute.LOGS -> {
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { statusActions() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        runtimeStatus(); ManagerActionButton("查看日志", onLogs, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                    }
                } else {
                    statusActions(); runtimeStatus()
                    if (!short) ManagerActionButton("查看日志", onLogs, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                }
            }
            ServiceRoute.CONNECTION, ServiceRoute.LAN -> {
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { localConnection() }; Column(Modifier.weight(1f)) { lanConnection() }
                } else { localConnection(); lanConnection() }
                connection.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            ServiceRoute.CREDENTIALS -> {
                ManagerSection("${id.label} 登录凭据") { credentialContent() }
                Text(if (id == ServiceId.ASTRBOT) "初始用户名：astrbot。首次登录会自动预填初始凭据；修改用户名或密码后不再预填，请使用你设置的账号。"
                    else "登录页会预填当前访问口令，点击登录即可。离开此页或进入后台后，显示的口令会自动隐藏。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}
