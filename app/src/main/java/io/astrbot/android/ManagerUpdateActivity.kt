package io.astrbot.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

class ManagerUpdateActivity : ComponentActivity() {
    private val repository by lazy { ManagerApkRepository(applicationContext) }
    private var status by mutableStateOf(ManagerApkStatus(null, false, 0, "正在读取更新信息"))
    private var busy by mutableStateOf(false)
    private var allowed by mutableStateOf(false)
    private var operation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runOperation("读取 APK 更新信息") { status = repository.cached() }
        setContent { ManagerTheme {
            var detail by rememberSaveable { mutableStateOf<String?>(null) }
            var install by rememberSaveable { mutableStateOf(false) }
            var permission by rememberSaveable { mutableStateOf(false) }
            var clear by rememberSaveable { mutableStateOf(false) }
            BackHandler { finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { finish() }) { Text("返回") }
                        Text("检查更新", Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleLarge)
                    }
                    Surface(Modifier.fillMaxWidth().clickable { detail = status.message }, shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("管理器 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                            Text(status.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    val candidate = status.candidate
                    FixedManagerMenu(listOf(
                        ManagerMenuItem("检查管理器更新", R.drawable.ic_update, "获取已验证的 APK 版本", !busy && repository.configured) {
                            runOperation("检查管理器更新") { status = repository.check(::progress) }
                        },
                        ManagerMenuItem("版本说明", R.drawable.ic_document, candidate?.versionName ?: "暂无适用的新版本", candidate != null) {
                            detail = candidate?.let { "${it.versionName}\n${it.bytes / 1024 / 1024} MiB\n\n${it.notes}" }
                        },
                        ManagerMenuItem(if (status.downloadedBytes > 0 && !status.prepared) "继续下载 APK" else "下载 APK",
                            R.drawable.ic_import, "下载后校验，暂不安装", !busy && candidate != null && !status.prepared) {
                            candidate?.let { info -> runOperation("下载管理器 APK") { status = repository.download(info.id, ::progress) } }
                        },
                        ManagerMenuItem("安装已校验 APK", R.drawable.ic_update,
                            if (allowed) "交给系统安装器确认" else "安装前需允许此应用安装更新", !busy && status.prepared) { install = true },
                        ManagerMenuItem("清理 APK 下载", R.drawable.ic_backup, "保留当前应用、机器人环境与数据", !busy) { clear = true }
                    ), Modifier.weight(1f))
                    if (busy) TextButton(onClick = { operation?.cancel() }) { Text("取消本次操作") }
                    Text("AstrBot 程序更新请返回“关于”中的对应入口。", style = MaterialTheme.typography.bodySmall)
                }
            }
            detail?.let { text -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("更新信息") },
                text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
            if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理 APK 下载") },
                text = { Text("删除未完成和已下载的管理器安装包，释放手机空间。") },
                confirmButton = { TextButton(onClick = { clear = false; runOperation("清理 APK 下载") { repository.clearDownload(); status = repository.cached() } }) { Text("清理") } },
                dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
            if (install) AlertDialog(onDismissRequest = { install = false }, title = { Text("安装管理器更新") },
                text = { Text("安装前先短暂停止服务创建数据快照，再恢复原启停设置。系统安装会重启应用，服务可能再次中断；更新后请重新打开应用。是否继续？") },
                confirmButton = { TextButton(onClick = {
                    install = false
                    if (!packageManager.canRequestPackageInstalls()) permission = true else openInstaller()
                }) { Text("继续安装") } }, dismissButton = { TextButton(onClick = { install = false }) { Text("取消") } })
            if (permission) AlertDialog(onDismissRequest = { permission = false }, title = { Text("允许安装更新") },
                text = { Text("在系统设置中允许本应用安装 APK，返回后再点击安装。系统仍会要求确认。") },
                confirmButton = { TextButton(onClick = {
                    permission = false
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { status = status.copy(message = "无法打开安装权限设置，请在系统应用设置中允许安装未知应用") }
                }) { Text("前往设置") } }, dismissButton = { TextButton(onClick = { permission = false }) { Text("取消") } })
        } }
    }

    override fun onResume() { super.onResume(); allowed = packageManager.canRequestPackageInstalls() }
    private fun progress(message: String) { status = status.copy(message = message) }
    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = lifecycleScope.launch {
            busy = true
            val log = RuntimeStore.log(applicationContext, LogCategory.MANAGER)
            log.append("$label · 开始")
            try { block(); log.append("$label · 完成") }
            catch (failure: CancellationException) {
                log.append("$label · 取消，保留下载进度")
                progress("本次操作已取消，下载进度保留，可稍后继续")
                throw failure
            } catch (failure: Exception) {
                log.append("$label · 未完成（${failure.javaClass.simpleName}）")
                // No server responses, arbitrary URLs or TLS details enter the user flow.
                val message = failure.message?.takeIf { it.length in 1..180 && it.any { c -> c in '\u4e00'..'\u9fff' } }
                progress(message ?: "更新操作未完成，请检查网络或稍后重试，现有应用保持不变")
            } finally { busy = false }
        }
    }
    private fun openInstaller() {
        val id = status.candidate?.id ?: return
        runOperation("校验并打开 APK 安装器") {
            check(!RuntimeStore.state.value.busy) { "请等待当前环境操作完成后再安装" }
            progress("正在重新校验 APK")
            repository.forInstaller(id)
            ManagerApkInstallBackup.createIfInstalled(applicationContext, ::progress)
            check(!RuntimeStore.state.value.busy) { "请等待当前环境操作完成后再安装" }
            // Backup may take minutes: validate metadata, bytes and remaining installer space once more.
            val file = repository.forInstaller(id)
            val uri = FileProvider.getUriForFile(this@ManagerUpdateActivity, "$packageName.manager-apk", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("APK 更新", uri) }
            startActivity(intent)
            progress("已交给系统安装器；若取消，可再次点击安装")
        }
    }
    override fun onDestroy() { operation?.cancel(); repository.close(); super.onDestroy() }
}
