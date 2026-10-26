package io.astrbot.android.runtime

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

/** Reuses the service-owned consistent snapshot; no backup work is owned by the Activity. */
internal object ManagerApkInstallBackup {
    suspend fun createIfInstalled(context: Context, progress: (String) -> Unit) {
        if (!withContext(Dispatchers.IO) { File(context.filesDir, "runtime-data/ubuntu/.astrbot-bootstrap.json").isFile }) return
        check(!RuntimeStore.state.value.busy) { "请等待当前环境操作完成后再安装" }
        progress("正在创建 APK 更新前的数据快照")
        val request = RuntimeService.command(context, "backup-apk")
        val result = withTimeout(15 * 60 * 1000L) {
            RuntimeStore.state.first { it.completedOperation == request && !it.busy }
        }
        check(result.error == null) { "更新前备份未完成，请在日志与备份页面处理后重试安装" }
    }
}
