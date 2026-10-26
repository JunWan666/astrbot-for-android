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
        !state.componentsReady -> AppText.get(R.string.ui_runtime_not_ready)
        !connection.enabled -> AppText.get(R.string.ui_webui_is_disabled, id.label)
        connection.endpoint == null -> AppText.get(R.string.ui_check_the_webui_address)
        preparing -> if (service.process == ProcessState.STOPPING) AppText.get(R.string.ui_stopping_984e8, id.label) else AppText.get(R.string.ui_preparing, id.label)
        service.process == ProcessState.STOPPED -> AppText.get(R.string.ui_is_stopped, id.label)
        service.process == ProcessState.CRASHED -> AppText.get(R.string.ui_crashed_8f2c0, id.label)
        !service.canOpenWebUi -> if (service.health == HealthState.UNRESPONSIVE) AppText.get(R.string.ui_webui_is_not_responding) else AppText.get(R.string.ui_waiting_for_webui_readiness)
        addressChanged -> AppText.get(R.string.ui_webui_address_changed)
        failure == WebUiFailure.CERTIFICATE -> AppText.get(R.string.ui_webui_certificate_verification_failed)
        failure == WebUiFailure.TIMEOUT -> AppText.get(R.string.ui_webui_loading_timed_out)
        else -> AppText.get(R.string.ui_unable_to_load_the_webui)
    }
    val description = when {
        !state.componentsReady -> AppText.get(R.string.ui_return_to_home_to_check_or_initialize_the_runtime)
        !connection.enabled || connection.endpoint == null -> connection.note ?: AppText.get(R.string.ui_return_to_home_to_check_webui_settings_existing_configuration)
        preparing -> if (state.busy) state.phase else AppText.get(R.string.ui_processing_your_request_the_webui_opens_automatically_when_ready)
        service.process == ProcessState.STOPPED -> AppText.get(R.string.ui_start_this_service_to_continue_using_the_webui)
        service.process == ProcessState.CRASHED -> AppText.get(R.string.ui_try_restarting_the_service_or_view_logs_to_investigate)
        !service.canOpenWebUi -> AppText.get(R.string.ui_the_service_process_is_still_running_the_webui_opens)
        addressChanged -> AppText.get(R.string.ui_reopen_the_webui_to_use_its_current_configured_address)
        failure == WebUiFailure.CERTIFICATE -> AppText.get(R.string.ui_check_the_webui_https_certificate_or_use_an_external)
        failure == WebUiFailure.TIMEOUT -> AppText.get(R.string.ui_the_page_did_not_finish_loading_within_45_seconds)
        failure == WebUiFailure.HTTP -> AppText.get(R.string.ui_the_webui_did_not_return_a_page_retry_or)
        else -> AppText.get(R.string.ui_connection_incomplete_please_retry_view_service_status_on_home)
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
    Surface(Modifier.fillMaxSize().semantics { contentDescription = AppText.get(R.string.ui_webui_recovery_page, id.label) }, color = MaterialTheme.colorScheme.background) {
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
                    WebUiRecoveryAction.START -> AppText.get(R.string.ui_start_service)
                    WebUiRecoveryAction.RESTART -> AppText.get(R.string.ui_restart_service)
                    WebUiRecoveryAction.RELOAD -> AppText.get(R.string.ui_retry_connection)
                    WebUiRecoveryAction.REOPEN -> AppText.get(R.string.ui_reopen_webui)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onHome, shape = MaterialTheme.shapes.small) { Text(AppText.get(R.string.ui_back_to_home)) }
                TextButton(onClick = onLogs) { Text(AppText.get(R.string.ui_view_logs)) }
            }
            ManagerSection(AppText.get(R.string.ui_recent_logs)) {
                Text(excerpt.ifBlank { AppText.get(R.string.ui_no_logs_yet_check_the_runtime_on_home) }, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
