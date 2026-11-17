package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.*
import java.text.DateFormat
import java.util.Date

@Composable internal fun EnvironmentCheckDialog(state: ManagerState, onCheck: () -> Unit, onRepair: () -> Unit,
    onMaintenance: () -> Unit, onDismiss: () -> Unit, onShare: () -> Unit) {
    val report = state.environmentCheck
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.get(R.string.ui_environment_check_and_repair)) }, text = {
        Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) Text(state.phase, color = MaterialTheme.colorScheme.primary)
            if (report == null) Text(AppText.get(R.string.ui_checking_runtime_resources_and_webui_configuration_this_does_not))
            else {
                val targets = report.repairTargets
                val failures = report.items.any { it.status == EnvironmentCheckStatus.FAILED }
                if (targets.isNotEmpty()) {
                    Text(AppText.get(R.string.repair_available, targets.size), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(targets.joinToString("、") { target -> AppText.get(when (target) {
                        EnvironmentRepairTarget.RUNTIME_FILES -> R.string.repair_target_runtime
                        EnvironmentRepairTarget.PYTHON_DEPENDENCIES -> R.string.repair_target_python
                        EnvironmentRepairTarget.ASTRBOT_DASHBOARD -> R.string.repair_target_dashboard
                    }) }, style = MaterialTheme.typography.bodySmall)
                } else Text(AppText.get(if (failures) R.string.repair_no_safe_target else R.string.repair_nothing_to_do),
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(AppText.get(R.string.ui_checked_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppText.locale).format(Date(report.checkedAtMillis))),
                    style = MaterialTheme.typography.bodySmall)
                report.items.forEach { item ->
                    val color = when (item.status) {
                        EnvironmentCheckStatus.OK -> MaterialTheme.colorScheme.primary
                        EnvironmentCheckStatus.ATTENTION -> MaterialTheme.colorScheme.onSurfaceVariant
                        EnvironmentCheckStatus.FAILED -> MaterialTheme.colorScheme.error
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title + " · " + when (item.status) {
                            EnvironmentCheckStatus.OK -> AppText.get(R.string.ui_passed); EnvironmentCheckStatus.ATTENTION -> AppText.get(R.string.ui_notice); EnvironmentCheckStatus.FAILED -> AppText.get(R.string.ui_action_needed)
                        }, color = color, style = MaterialTheme.typography.labelLarge)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (targets.isEmpty() && failures) report.repairProblem?.let { problem ->
                    HorizontalDivider()
                    ProblemDetails(problem.details)
                }
                }
                if (targets.isNotEmpty()) ManagerActionButton(AppText.get(R.string.repair_start), onRepair,
                    Modifier.fillMaxWidth(), enabled = !state.busy)
                if (failures) ManagerActionButton(AppText.get(R.string.problem_share_diagnostics), onShare,
                    Modifier.fillMaxWidth(), tone = ActionTone.NEUTRAL, enabled = !state.busy)
                if (targets.isEmpty() && failures) TextButton(onClick = onMaintenance, enabled = !state.busy) {
                    Text(AppText.get(R.string.ui_runtime_upgrade_and_rollback))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) } },
        dismissButton = { TextButton(onClick = onCheck, enabled = !state.busy) { Text(AppText.get(R.string.ui_check_again)) } })
}
