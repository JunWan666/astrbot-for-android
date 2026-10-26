package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Reuses the service-owned consistent snapshot; no backup work is owned by the Activity. */
internal object ManagerApkInstallBackup {
    suspend fun createIfInstalled(context: Context, progress: (String) -> Unit) {
        if (!withContext(Dispatchers.IO) { File(context.filesDir, "runtime-data/ubuntu/.astrbot-bootstrap.json").isFile }) return
        checkLocalized(!RuntimeStore.state.value.busy) { AppText.get(R.string.ui_wait_for_the_current_runtime_operation_to_finish_before) }
        progress(AppText.get(R.string.ui_creating_a_data_snapshot_before_the_apk_update))
        val request = RuntimeService.command(context, "backup-apk")
        val result = withTimeout(15 * 60 * 1000L) {
            RuntimeStore.state.first { it.completedOperation == request && !it.busy }
        }
        checkLocalized(result.error == null) { AppText.get(R.string.ui_the_pre_update_backup_is_incomplete_resolve_it_in) }
    }
}
