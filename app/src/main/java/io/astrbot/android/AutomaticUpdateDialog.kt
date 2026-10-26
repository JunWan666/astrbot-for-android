package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun AutomaticUpdateDialog(enabled: Boolean, configured: Boolean,
    onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自动检查更新") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (enabled) "已开启" else "已关闭", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = configured || enabled,
                    modifier = Modifier.semantics { contentDescription = "自动检查更新开关" })
            }
            Text("打开应用且网络可用时，最多每天检查一次兼容的 AstrBot 更新。检查失败或中断后，至少间隔一小时再尝试。",
                style = MaterialTheme.typography.bodySmall)
            Text("发现更新后可查看版本说明，下载和安装仍由你确认。", style = MaterialTheme.typography.bodySmall)
            if (!configured) Text("当前安装包尚未配置在线更新通道，暂不可开启。可在程序更新页导入可信维护包。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
