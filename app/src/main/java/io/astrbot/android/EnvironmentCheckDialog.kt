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
    onMaintenance: () -> Unit, onDismiss: () -> Unit) {
    val report = state.environmentCheck
    AlertDialog(onDismissRequest = onDismiss, title = { Text("环境检查与修复") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) Text(state.phase, color = MaterialTheme.colorScheme.primary)
            if (report == null) Text("正在检查运行资源和后台配置，不会启动 AstrBot / NapCat 或重置配置。")
            else {
                Text("检查于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.checkedAtMillis))}",
                    style = MaterialTheme.typography.bodySmall)
                report.items.forEach { item ->
                    val color = when (item.status) {
                        EnvironmentCheckStatus.OK -> MaterialTheme.colorScheme.primary
                        EnvironmentCheckStatus.ATTENTION -> MaterialTheme.colorScheme.onSurfaceVariant
                        EnvironmentCheckStatus.FAILED -> MaterialTheme.colorScheme.error
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title + " · " + when (item.status) {
                            EnvironmentCheckStatus.OK -> "通过"; EnvironmentCheckStatus.ATTENTION -> "提示"; EnvironmentCheckStatus.FAILED -> "需处理"
                        }, color = color, style = MaterialTheme.typography.labelLarge)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Text(report.repairExplanation, style = MaterialTheme.typography.bodySmall)
                ManagerActionButton("修复运行资源", onRepair, Modifier.fillMaxWidth(), enabled = !state.busy && report.canRepair)
                TextButton(onClick = onMaintenance, enabled = !state.busy) { Text("运行环境升级与回退") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onCheck, enabled = !state.busy) { Text("重新检查") } })
}
