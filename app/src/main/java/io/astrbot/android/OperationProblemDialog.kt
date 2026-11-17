package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.*

@Composable internal fun ProblemDetails(details: List<ProblemDetail>) {
    details.forEach { detail ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail.name, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AppText.get(R.string.problem_current, detail.current), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(AppText.get(R.string.problem_expected, detail.expected), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                if (detail.reason != AppText.get(R.string.problem_version_different))
                    Text(detail.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun OperationProblemDialog(problem: OperationProblem, busy: Boolean,
    onCheck: () -> Unit, onShare: () -> Unit, onLogs: () -> Unit, onDismiss: () -> Unit) {
    var detailsVisible by remember(problem) { mutableStateOf(problem.code in setOf("REPAIR_COMBINATION_MISMATCH", "REPAIR_IDENTITY_UNAVAILABLE")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(problem.title) }, text = {
        Column(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(problem.summary, style = MaterialTheme.typography.bodyMedium)
                Text(problem.nextStep, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { detailsVisible = !detailsVisible }, contentPadding = PaddingValues(0.dp)) {
                    Text(AppText.get(if (detailsVisible) R.string.problem_hide_details else R.string.problem_show_details))
                }
                if (detailsVisible) {
                    ProblemDetails(problem.details)
                    Text("${problem.code}\n${problem.operation} · ${problem.stage}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (ProblemAction.CHECK_ENVIRONMENT in problem.actions) ManagerActionButton(AppText.get(R.string.problem_check_environment),
                onCheck, Modifier.fillMaxWidth(), enabled = !busy)
            if (ProblemAction.SHARE_DIAGNOSTICS in problem.actions) ManagerActionButton(AppText.get(R.string.problem_share_diagnostics),
                onShare, Modifier.fillMaxWidth(), tone = ActionTone.NEUTRAL, enabled = !busy)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppText.get(R.string.ui_close)) } },
        dismissButton = { TextButton(onClick = onLogs) { Text(AppText.get(R.string.ui_view_manager_logs)) } })
}
