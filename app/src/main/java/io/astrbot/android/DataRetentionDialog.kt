package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun DataRetentionDialog(onPortableBackup: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("数据与卸载说明") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("卸载应用或在系统中“清除数据”，会删除运行环境、配置、数据库、QQ 登录资料，以及应用内的全部本机备份。")
            Text("准备卸载、清除数据或换手机前，请先导出便携备份到应用外，并确认文件已保存。")
            Text("便携备份包含业务配置和密钥，不含 QQ 登录会话，只能导入相同运行组合。换机或卸载重装后需要重新登录 QQ。请妥善保管备份文件。",
                style = MaterialTheme.typography.bodySmall)
            Text("正常更新请使用同包名、同签名的 APK 覆盖安装，保留应用数据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onPortableBackup) { Text("前往便携备份") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
