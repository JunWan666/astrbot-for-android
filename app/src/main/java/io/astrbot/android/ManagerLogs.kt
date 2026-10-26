package io.astrbot.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable internal fun ManagerLogViewer(text: String, paused: Boolean, onPause: () -> Unit, onCopy: () -> Unit,
    onExport: () -> Unit, modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState()) {
    BoxWithConstraints(modifier) {
        LaunchedEffect(text, paused, scroll.maxValue) { if (!paused) scroll.scrollTo(scroll.maxValue) }
        val compact = maxHeight < 320.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerActionButton(if (paused) "继续刷新" else "暂停刷新", onPause, Modifier.weight(1f), ActionTone.NEUTRAL)
                ManagerActionButton("复制当前", onCopy, Modifier.weight(1f), ActionTone.NEUTRAL, text.isNotBlank())
                ManagerActionButton("导出", onExport, Modifier.weight(1f))
            }
            if (!compact) Text(if (paused) "已暂停 · 可选择文字复制" else "实时跟随最新日志 · 每 2 秒刷新",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "日志内容" },
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                SelectionContainer {
                    Text(text.ifBlank { "暂无日志" }, Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (!compact) Text("复制当前显示内容；导出保留范围内的完整脱敏日志。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
