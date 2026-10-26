// SPDX-License-Identifier: MIT
// Copyright (c) 2026 JunWan666
package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.ManagerState
import io.astrbot.android.runtime.InstalledVersionInspection

@Composable internal fun ProjectAboutDialog(state: ManagerState, onDismiss: () -> Unit, onAuthor: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("应用与组件版本") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManagerAppLogo(Modifier.size(48.dp))
                Column {
                    Text(ProjectIdentity.NAME, style = MaterialTheme.typography.titleMedium)
                    Text("版本：${BuildConfig.VERSION_NAME}")
                }
            }
            Text("开发与维护：${ProjectIdentity.AUTHOR}")
            Text("项目许可：${ProjectIdentity.LICENSE}")
            Text(ProjectIdentity.COPYRIGHT, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAuthor) { Text("作者 GitHub 主页") }
            HorizontalDivider()
            state.versions.filterKeys { it != "App" }.forEach { (component, version) ->
                Text("$component：$version", style = MaterialTheme.typography.bodySmall)
            }
            state.versionNotice?.let {
                Text(it, color = if (it == InstalledVersionInspection.MATCHED_NOTICE)
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            Text("AstrBot 与 NapCat 支持独立在线更新，保留配置并提供回退。Linux QQ、Python 或底层依赖变化时，再升级匹配的运行环境。",
                style = MaterialTheme.typography.bodySmall)
            Text("第三方项目保留各自作者、许可与商标，完整声明见“第三方许可”。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
