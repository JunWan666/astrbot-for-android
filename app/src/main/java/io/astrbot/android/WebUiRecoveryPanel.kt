package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.core.*
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

internal enum class WebUiFailure { NETWORK, HTTP, CERTIFICATE, TIMEOUT }
internal enum class WebUiRecoveryAction { START, RESTART, RELOAD, REOPEN }

internal fun webUiNeedsRecovery(id: ServiceId, state: ManagerState, origin: String,
    failure: WebUiFailure?, pending: String?): Boolean =
    !state.componentsReady || !state.services.getValue(id).canOpenWebUi ||
        !state.webUis.getValue(id).enabled || state.webUis.getValue(id).endpoint?.origin != origin ||
        failure != null || pending != null

/** Native recovery controls never infer business connectivity from a loaded page. */
@Composable internal fun WebUiRecoveryPanel(id: ServiceId, state: ManagerState, origin: String,
    failure: WebUiFailure?, pending: String?, onAction: (WebUiRecoveryAction) -> Unit,
    onHome: () -> Unit, onLogs: () -> Unit) {
    val service = state.services.getValue(id)
    val connection = state.webUis.getValue(id)
    val preparing = state.busy || pending != null || service.process in setOf(ProcessState.STARTING, ProcessState.STOPPING)
    val addressChanged = connection.endpoint != null && connection.endpoint.origin != origin
    val title = when {
        !state.componentsReady -> "运行环境尚未就绪"
        !connection.enabled -> "${id.label} 后台已关闭"
        connection.endpoint == null -> "后台地址需要检查"
        preparing -> if (service.process == ProcessState.STOPPING) "正在停止 ${id.label}" else "正在准备 ${id.label}"
        service.process == ProcessState.STOPPED -> "${id.label} 已停止"
        service.process == ProcessState.CRASHED -> "${id.label} 异常退出"
        !service.canOpenWebUi -> if (service.health == HealthState.UNRESPONSIVE) "后台暂时无响应" else "正在等待后台就绪"
        addressChanged -> "后台地址已更新"
        failure == WebUiFailure.CERTIFICATE -> "后台证书未通过验证"
        failure == WebUiFailure.TIMEOUT -> "后台页面加载超时"
        else -> "后台页面加载失败"
    }
    val description = when {
        !state.componentsReady -> "返回首页检查或完成运行环境初始化。"
        !connection.enabled || connection.endpoint == null -> connection.note ?: "请返回首页检查后台设置，现有配置会保留。"
        preparing -> if (state.busy) state.phase else "正在处理请求，后台就绪后会自动打开。"
        service.process == ProcessState.STOPPED -> "启动此服务后继续使用后台。"
        service.process == ProcessState.CRASHED -> "可尝试重新启动，或查看日志了解退出原因。"
        !service.canOpenWebUi -> "服务进程仍在运行。后台就绪后会自动打开，也可以先查看日志。"
        addressChanged -> "重新打开后台以使用当前配置的地址。"
        failure == WebUiFailure.CERTIFICATE -> "请检查后台 HTTPS 证书，或使用外部浏览器处理。"
        failure == WebUiFailure.TIMEOUT -> "页面在 45 秒内未完成加载，可重试连接或查看日志。"
        failure == WebUiFailure.HTTP -> "后台未能返回页面，可重试连接或查看日志。"
        else -> "连接未完成，请重试；服务运行状态可在首页查看。"
    }
    val action = when {
        !state.componentsReady || !connection.enabled || connection.endpoint == null || preparing -> null
        service.process in setOf(ProcessState.STOPPED, ProcessState.CRASHED) -> WebUiRecoveryAction.START
        !service.canOpenWebUi -> if (service.health == HealthState.UNRESPONSIVE) WebUiRecoveryAction.RESTART else null
        addressChanged -> WebUiRecoveryAction.REOPEN
        else -> WebUiRecoveryAction.RELOAD
    }
    var excerpt by remember(id) { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(id, owner, context) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                excerpt = withContext(Dispatchers.IO) {
                    RuntimeStore.log(context, id).tail().takeLast(1500)
                }
                delay(5000)
            }
        }
    }
    Surface(Modifier.fillMaxSize().semantics { contentDescription = "${id.label} 后台恢复页" }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(10.dp))
            ManagerServiceLogo(id)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (preparing || service.process == ProcessState.RUNNING && service.health in setOf(HealthState.UNKNOWN, HealthState.WAITING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (action != null) Button(onClick = { onAction(action) }, shape = MaterialTheme.shapes.small) {
                Text(when (action) {
                    WebUiRecoveryAction.START -> "启动服务"
                    WebUiRecoveryAction.RESTART -> "重启服务"
                    WebUiRecoveryAction.RELOAD -> "重试连接"
                    WebUiRecoveryAction.REOPEN -> "重新打开后台"
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onHome, shape = MaterialTheme.shapes.small) { Text("返回首页") }
                TextButton(onClick = onLogs) { Text("查看日志") }
            }
            ManagerSection("最近日志") {
                Text(excerpt.ifBlank { "暂无日志，可在首页检查运行环境。" }, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
