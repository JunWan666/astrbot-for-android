package io.astrbot.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

@Composable internal fun rememberMessagePlatforms(state: ManagerState): List<MessagePlatformStatus> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(state)
    var values by remember { mutableStateOf(MessagePlatform.entries.map { MessagePlatformStatus(it) }) }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val reader = MessagePlatformStatusReader(context)
            while (isActive) {
                val current = latest
                val service = current.services.getValue(ServiceId.ASTRBOT)
                values = withContext(Dispatchers.IO) {
                    reader.read(current.platformObservationNonce, service.process == ProcessState.RUNNING,
                        service.displayedProcess == ProcessState.STARTING)
                }
                delay(2000)
            }
        }
    }
    return values
}

internal val MessagePlatform.title: String get() = AppText.get(if (this == MessagePlatform.QQ) R.string.platform_qq else R.string.platform_wechat)
internal fun MessagePlatformStatus.summary(): String {
    val status = when (state) {
    AdapterState.UNCONFIGURED -> AppText.get(R.string.platform_not_bound)
    AdapterState.DISABLED -> AppText.get(R.string.platform_disabled)
    AdapterState.STOPPED -> AppText.get(R.string.ui_stopped)
    AdapterState.STARTING -> AppText.get(R.string.platform_connecting)
    AdapterState.RUNNING -> if (total > 1) AppText.get(R.string.platform_running_count, running, total) else AppText.get(R.string.platform_adapter_running)
    AdapterState.ERROR -> AppText.get(R.string.platform_error)
    AdapterState.UNKNOWN -> AppText.get(R.string.platform_unknown)
    AdapterState.UNSUPPORTED -> AppText.get(R.string.platform_update_needed)
    }
    return if (total > 0) AppText.get(R.string.platform_bound_status, status) else status
}

@Composable internal fun PlatformIcon(platform: MessagePlatform) {
    // Keep platform identities distinct without reusing protocol-client branding.
    Surface(shape = MaterialTheme.shapes.medium,
        color = if (platform == MessagePlatform.QQ) Color(0xFFEAF3FF) else Color(0xFFE8F6ED)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (platform == MessagePlatform.QQ) R.drawable.platform_qq else R.drawable.ic_platform_wechat),
                null, Modifier.size(28.dp), tint = Color.Unspecified)
        }
    }
}

@Composable internal fun PlatformSummaryCards(values: List<MessagePlatformStatus>, onOpen: (MessagePlatform) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppText.get(R.string.platform_title), Modifier.padding(start = 4.dp, top = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            values.forEach { value ->
                Surface(onClick = { onOpen(value.platform) }, shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlatformIcon(value.platform)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value.platform.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(value.summary(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = when (value.state) {
                                    AdapterState.ERROR -> MaterialTheme.colorScheme.error
                                    AdapterState.RUNNING -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                        }
                        Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
