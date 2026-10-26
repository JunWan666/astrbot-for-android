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
                modifier = Modifier.semantics { contentDescription = AppText.get(R.string.ui_service_page, item.title) })
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
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_service_details_content) }) {
    val short = maxHeight < 400.dp || LocalDensity.current.fontScale > 1.25f
    val sideBySide = maxWidth >= 580.dp && maxHeight < 450.dp
    @Composable fun statusActions() {
        ManagerSection(id.label) {
            Text(status.localizedSummary(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!short) Text(AppText.get(R.string.ui_version, state.versions[id.label] ?: AppText.get(R.string.ui_unknown)), style = MaterialTheme.typography.bodySmall)
        }
        ManagerActionButton(AppText.get(R.string.ui_webui), onOpen, Modifier.fillMaxWidth(), enabled = status.canOpenWebUi, description = AppText.get(R.string.ui_open_webui, id.label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerActionButton(AppText.get(R.string.ui_start), { onCommand("start") }, Modifier.weight(1f), ActionTone.START, state.componentsReady && !state.busy, AppText.get(R.string.ui_start_a820c, id.label))
            ManagerActionButton(AppText.get(R.string.ui_stop), { onCommand("stop") }, Modifier.weight(1f), ActionTone.STOP, state.canStop(id), AppText.get(R.string.ui_stop_b02dd, id.label))
            ManagerActionButton(AppText.get(R.string.ui_restart), { onCommand("restart") }, Modifier.weight(1f), ActionTone.RESTART, state.componentsReady && !state.busy, AppText.get(R.string.ui_restart_e3383, id.label))
        }
    }
    @Composable fun runtimeStatus() {
        fun time(value: Long?) = value?.let { java.text.SimpleDateFormat("MM-dd HH:mm:ss", AppText.locale).format(java.util.Date(it)) } ?: AppText.get(R.string.ui_none)
        fun record(event: ServiceEvent?) = event?.let { "${time(it.atMillis)} · ${it.summary}" } ?: AppText.get(R.string.ui_no_records)
        ManagerSection(AppText.get(R.string.ui_runtime_status), padded = false) {
          Column(Modifier.fillMaxWidth().clickable {
              onNotice(AppText.get(R.string.ui_history, id.label), AppText.get(R.string.ui_last_checked_n_nlast_issue_n_nlast_recovery_n, time(status.checkedAtMillis), record(status.history.lastAnomaly), record(status.history.lastRecovery)))
          }.semantics { contentDescription = AppText.get(R.string.ui_history, id.label) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("OneBot：${when (status.oneBot) {
                OneBotState.UNCONFIGURED -> AppText.get(R.string.ui_not_configured); OneBotState.CONNECTING -> AppText.get(R.string.ui_connecting); OneBotState.CONNECTED -> AppText.get(R.string.ui_connected)
                OneBotState.DISCONNECTED -> AppText.get(R.string.ui_disconnected); OneBotState.UNKNOWN -> AppText.get(R.string.ui_pending)
            }}", style = MaterialTheme.typography.bodySmall)
            Text(AppText.get(R.string.ui_last_check, time(status.checkedAtMillis)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(AppText.get(R.string.ui_last_recovery, record(status.history.lastRecovery)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(AppText.get(R.string.ui_last_issue, record(status.history.lastAnomaly)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
    }
    @Composable fun localConnection() {
        ManagerSection(AppText.get(R.string.ui_local_webui_address)) {
            Text(endpoint?.origin ?: AppText.get(R.string.ui_generated_on_first_start), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(AppText.get(R.string.ui_copy_address), { endpoint?.let { onCopy(AppText.get(R.string.ui_webui_address), it.origin) } }, Modifier.weight(1f), ActionTone.NEUTRAL, endpoint != null)
                ManagerActionButton(AppText.get(R.string.ui_browser), { endpoint?.let { onExternal(it.origin) } }, Modifier.weight(1f), enabled = status.canOpenWebUi && endpoint != null)
            }
        }
    }
    @Composable fun lanConnection() {
        ManagerSection(AppText.get(R.string.ui_lan_access)) {
            Text(if (connection.lanEnabled) AppText.get(R.string.ui_on_accessible_on_the_same_network) else AppText.get(R.string.ui_access_restricted_to_this_phone), style = MaterialTheme.typography.bodyMedium)
            ManagerActionButton(if (connection.lanEnabled) AppText.get(R.string.ui_disable_lan_access, id.label) else AppText.get(R.string.ui_enable_lan_access, id.label),
                { onLan(!connection.lanEnabled) }, Modifier.fillMaxWidth(), if (connection.lanEnabled) ActionTone.STOP else ActionTone.PRIMARY,
                state.componentsReady && !state.busy)
            if (connection.lanEnabled && endpoint != null) {
                val urls = addresses.map { "${endpoint.scheme}://$it:${endpoint.port}" }
                if (urls.isEmpty()) Text(AppText.get(R.string.ui_no_address_available_connect_to_wi_fi_or_ethernet), style = MaterialTheme.typography.bodySmall)
                else ManagerActionButton(AppText.get(R.string.ui_copy_lan_address), { onCopy(AppText.get(R.string.ui_lan_address, id.label), urls.joinToString("\n")) }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                urls.firstOrNull()?.let { Text(it + if (urls.size > 1) AppText.get(R.string.ui_total, urls.size) else "", style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp)) {
        when (route) {
            ServiceRoute.STATUS, ServiceRoute.LOGS -> {
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { statusActions() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        runtimeStatus(); ManagerActionButton(AppText.get(R.string.ui_view_logs), onLogs, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                    }
                } else {
                    statusActions(); runtimeStatus()
                    if (!short) ManagerActionButton(AppText.get(R.string.ui_view_logs), onLogs, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                }
            }
            ServiceRoute.CONNECTION, ServiceRoute.LAN -> {
                if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { localConnection() }; Column(Modifier.weight(1f)) { lanConnection() }
                } else { localConnection(); lanConnection() }
                connection.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            ServiceRoute.CREDENTIALS -> {
                ManagerSection(AppText.get(R.string.ui_login_credentials, id.label)) { credentialContent() }
                Text(if (id == ServiceId.ASTRBOT) AppText.get(R.string.ui_initial_username_astrbot_initial_credentials_are_prefilled_on_first)
                    else AppText.get(R.string.ui_the_login_page_prefills_the_current_access_token_select),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}
