package io.astrbot.android

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.astrbot.android.runtime.DesktopInstallProgress
import io.astrbot.android.runtime.DesktopInstallStage

@Composable internal fun DesktopDownloadPanel(transfer: DesktopInstallProgress, canRetry: Boolean,
    onControl: (String) -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context,bytes)
    ManagerSection(AppText.get(if (transfer.kind == "qq") R.string.desktop_qq_name else R.string.desktop_name)) {
        Text(AppText.get(if (transfer.stopping) R.string.desktop_transfer_stopping else when (transfer.stage) {
            DesktopInstallStage.PREPARING -> R.string.desktop_transfer_preparing
            DesktopInstallStage.DOWNLOADING -> R.string.desktop_transfer_downloading
            DesktopInstallStage.PAUSED -> R.string.desktop_transfer_paused
            DesktopInstallStage.VERIFYING -> R.string.desktop_transfer_verifying
            DesktopInstallStage.INSTALLING -> R.string.desktop_transfer_installing
            DesktopInstallStage.STOPPED -> R.string.desktop_transfer_stopped
            DesktopInstallStage.FAILED -> R.string.desktop_transfer_failed
            DesktopInstallStage.FINISHED -> R.string.desktop_transfer_finished
        }),style = MaterialTheme.typography.bodyMedium)
        val total = transfer.total
        if (total != null && total > 0) {
            val fraction = (transfer.downloaded.toDouble()/total).coerceIn(0.0,1.0).toFloat()
            LinearProgressIndicator(progress = { fraction },modifier = Modifier.fillMaxWidth())
            Text(AppText.get(R.string.desktop_transfer_values,size(transfer.downloaded),size(total),(fraction*100).toInt()),style = MaterialTheme.typography.bodySmall)
        } else if (transfer.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (transfer.stage == DesktopInstallStage.DOWNLOADING)
            Text(AppText.get(R.string.desktop_transfer_speed,size(transfer.bytesPerSecond)),style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (transfer.canPause) TextButton(onClick = { onControl("pause") }) { Text(AppText.get(R.string.desktop_transfer_pause)) }
            if (transfer.active && transfer.stage == DesktopInstallStage.PAUSED && !transfer.stopping)
                TextButton(onClick = { onControl("resume") }) { Text(AppText.get(R.string.desktop_transfer_resume)) }
            if (transfer.canStop) TextButton(onClick = { onControl("stop") }) { Text(AppText.get(R.string.desktop_transfer_stop)) }
            if (!transfer.active && transfer.stage in setOf(DesktopInstallStage.STOPPED,DesktopInstallStage.FAILED))
                TextButton(onClick = onRetry,enabled = canRetry) { Text(AppText.get(R.string.desktop_transfer_retry)) }
        }
    }
}
