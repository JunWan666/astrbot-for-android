package io.astrbot.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*

@Composable internal fun AstrBotHome(state: ManagerState, network: NetworkSnapshot, resources: ResourceSnapshot,
    platforms: List<MessagePlatformStatus>, onCommand: (String) -> Unit, onOpen: (String) -> Unit,
    onDetails: () -> Unit, onLogs: () -> Unit, onPlatforms: (MessagePlatform) -> Unit, onNotice: (String, String) -> Unit) {
    val status = state.services.getValue(ServiceId.ASTRBOT)
    val stop = status.desiredRunning || status.process != ProcessState.STOPPED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ManagerStatusStrip(state, onNotice)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ManagerServiceLogo(ServiceId.ASTRBOT)
                    Column(Modifier.weight(1f)) {
                        Text("AstrBot", style = MaterialTheme.typography.titleMedium)
                        Text(state.versions["AstrBot"] ?: AppText.get(R.string.home_core_version_pending),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ManagerProcessBadge(status.displayedProcess)
                }
                Text(if (!state.componentsReady) AppText.get(R.string.home_initialize_hint) else status.localizedSummary(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!state.componentsReady) {
                    ManagerActionButton(AppText.get(R.string.ui_initialize_runtime), { onCommand("initialize") },
                        Modifier.fillMaxWidth(), enabled = !state.busy)
                } else {
                    ManagerActionButton(AppText.get(R.string.home_dashboard), { onOpen("") },
                        Modifier.fillMaxWidth(), enabled = status.canOpenWebUi)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ManagerActionButton(AppText.get(if (stop) R.string.ui_stop else R.string.ui_start),
                            { onCommand(if (stop) "stop" else "start") }, Modifier.weight(1f),
                            if (stop) ActionTone.STOP else ActionTone.START, enabled = if (stop) state.canStop(ServiceId.ASTRBOT) else !state.busy)
                        ManagerActionButton(AppText.get(R.string.ui_restart), { onCommand("restart") }, Modifier.weight(1f),
                            ActionTone.RESTART, enabled = !state.busy && stop)
                        ManagerActionButton(AppText.get(R.string.ui_details), onDetails, Modifier.weight(1f), ActionTone.NEUTRAL)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(R.string.home_models, R.drawable.ic_ai) { onOpen("providers") },
                Triple(R.string.home_plugins, R.drawable.ic_bot) { onOpen("extension") },
                Triple(R.string.home_personas, R.drawable.ic_persona) { onOpen("persona") },
                Triple(R.string.home_logs, R.drawable.ic_logs, onLogs)).forEach { (label, icon, action) ->
                Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.clickable(enabled = label == R.string.home_logs || status.canOpenWebUi, onClick = action)
                        .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val enabled = label == R.string.home_logs || status.canOpenWebUi
                        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        Text(AppText.get(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                    }
                }
            }
        }
        ManagerResources(resources, compact = true)
        PlatformSummaryCards(platforms, onPlatforms)
        if (network.state != InternetState.VALIDATED) Text(network.title + " · " + network.detail,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
