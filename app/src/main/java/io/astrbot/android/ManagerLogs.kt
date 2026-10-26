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
                ManagerActionButton(if (paused) AppText.get(R.string.ui_resume) else AppText.get(R.string.ui_pause), onPause, Modifier.weight(1f), ActionTone.NEUTRAL)
                ManagerActionButton(AppText.get(R.string.ui_copy), onCopy, Modifier.weight(1f), ActionTone.NEUTRAL, text.isNotBlank())
                ManagerActionButton(AppText.get(R.string.ui_export), onExport, Modifier.weight(1f))
            }
            if (!compact) Text(if (paused) AppText.get(R.string.ui_paused_select_text_to_copy) else AppText.get(R.string.ui_following_latest_logs_refreshing_every_2_seconds),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = AppText.get(R.string.ui_log_content) },
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                SelectionContainer {
                    Text(text.ifBlank { AppText.get(R.string.ui_no_logs_yet) }, Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (!compact) Text(AppText.get(R.string.ui_copy_the_displayed_content_or_export_all_retained_redacted),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
