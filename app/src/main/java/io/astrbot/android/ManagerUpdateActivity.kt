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

class ManagerUpdateActivity : LocalizedActivity() {
    private val repository by lazy { ManagerApkRepository(applicationContext) }
    private var status by mutableStateOf(ManagerApkStatus(null, false, 0, AppText.get(R.string.ui_loading_update_information)))
    private var busy by mutableStateOf(false)
    private var allowed by mutableStateOf(false)
    private var operation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runOperation(AppText.get(R.string.ui_read_apk_update_information)) { status = repository.cached() }
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
                        TextButton(onClick = { finish() }) { Text(AppText.get(R.string.ui_back)) }
                        Text(AppText.get(R.string.ui_check_for_updates), Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleLarge)
                    }
                    Surface(Modifier.fillMaxWidth().clickable { detail = status.message }, shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(AppText.get(R.string.ui_manager_b28f4, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                            Text(status.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    val candidate = status.candidate
                    FixedManagerMenu(listOf(
                        ManagerMenuItem(AppText.get(R.string.ui_check_app_updates), R.drawable.ic_update, AppText.get(R.string.ui_find_verified_apk_releases), !busy && repository.configured) {
                            runOperation(AppText.get(R.string.ui_check_app_updates)) { status = repository.check(::progress) }
                        },
                        ManagerMenuItem(AppText.get(R.string.ui_release_notes), R.drawable.ic_document, candidate?.versionName ?: AppText.get(R.string.ui_no_applicable_updates), candidate != null) {
                            detail = candidate?.let { "${it.versionName}\n${it.bytes / 1024 / 1024} MiB\n\n${it.notes}" }
                        },
                        ManagerMenuItem(if (status.downloadedBytes > 0 && !status.prepared) AppText.get(R.string.ui_resume_apk_download) else AppText.get(R.string.ui_download_apk),
                            R.drawable.ic_import, AppText.get(R.string.ui_verify_after_downloading_installation_is_separate), !busy && candidate != null && !status.prepared) {
                            candidate?.let { info -> runOperation(AppText.get(R.string.ui_download_app_apk)) { status = repository.download(info.id, ::progress) } }
                        },
                        ManagerMenuItem(AppText.get(R.string.ui_install_verified_apk), R.drawable.ic_update,
                            if (allowed) AppText.get(R.string.ui_confirm_installation_in_the_system_installer) else AppText.get(R.string.ui_allow_this_app_to_install_updates_first), !busy && status.prepared) { install = true },
                        ManagerMenuItem(AppText.get(R.string.ui_clear_apk_downloads), R.drawable.ic_backup, AppText.get(R.string.ui_keep_the_current_app_bot_runtime_and_data), !busy) { clear = true }
                    ), Modifier.weight(1f))
                    if (busy) TextButton(onClick = { operation?.cancel() }) { Text(AppText.get(R.string.ui_cancel_this_operation)) }
                    Text(AppText.get(R.string.ui_for_astrbot_updates_return_to_the_corresponding_entry_under), style = MaterialTheme.typography.bodySmall)
                }
            }
            detail?.let { text -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(AppText.get(R.string.ui_update_information)) },
                text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { detail = null }) { Text(AppText.get(R.string.ui_close)) } }) }
            if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(AppText.get(R.string.ui_clear_apk_downloads)) },
                text = { Text(AppText.get(R.string.ui_delete_incomplete_and_completed_app_apk_downloads_to_free)) },
                confirmButton = { TextButton(onClick = { clear = false; runOperation(AppText.get(R.string.ui_clear_apk_downloads)) { repository.clearDownload(); status = repository.cached() } }) { Text(AppText.get(R.string.ui_clear)) } },
                dismissButton = { TextButton(onClick = { clear = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            if (install) AlertDialog(onDismissRequest = { install = false }, title = { Text(AppText.get(R.string.ui_install_app_update)) },
                text = { Text(AppText.get(R.string.ui_services_will_briefly_stop_to_create_a_data_snapshot)) },
                confirmButton = { TextButton(onClick = {
                    install = false
                    if (!packageManager.canRequestPackageInstalls()) permission = true else openInstaller()
                }) { Text(AppText.get(R.string.ui_continue_installation)) } }, dismissButton = { TextButton(onClick = { install = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            if (permission) AlertDialog(onDismissRequest = { permission = false }, title = { Text(AppText.get(R.string.ui_allow_update_installation)) },
                text = { Text(AppText.get(R.string.ui_allow_this_app_to_install_apks_in_system_settings)) },
                confirmButton = { TextButton(onClick = {
                    permission = false
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { status = status.copy(message = AppText.get(R.string.ui_unable_to_open_installation_permissions_allow_unknown_app_installation)) }
                }) { Text(AppText.get(R.string.ui_open_settings)) } }, dismissButton = { TextButton(onClick = { permission = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        } }
    }

    override fun onResume() { super.onResume(); allowed = packageManager.canRequestPackageInstalls() }
    private fun progress(message: String) { status = status.copy(message = message) }
    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = lifecycleScope.launch {
            busy = true
            val log = RuntimeStore.log(applicationContext, LogCategory.MANAGER)
            log.append(AppText.get(R.string.ui_started, label))
            try { block(); log.append(AppText.get(R.string.ui_finished, label)) }
            catch (failure: CancellationException) {
                log.append(AppText.get(R.string.ui_cancelled_download_progress_retained, label))
                progress(AppText.get(R.string.ui_operation_cancelled_download_progress_is_retained_so_you_can))
                throw failure
            } catch (failure: Exception) {
                log.append(AppText.get(R.string.ui_incomplete, label, failure.javaClass.simpleName))
                // No server responses, arbitrary URLs or TLS details enter the user flow.
                val message = failure.userFacingMessage()?.takeIf { it.length in 1..180 }
                progress(message ?: AppText.get(R.string.ui_update_incomplete_check_your_network_or_retry_later_the))
            } finally { busy = false }
        }
    }
    private fun openInstaller() {
        val id = status.candidate?.id ?: return
        runOperation(AppText.get(R.string.ui_verify_apk_and_open_installer)) {
            checkLocalized(!RuntimeStore.state.value.busy) { AppText.get(R.string.ui_wait_for_the_current_runtime_operation_to_finish_before) }
            progress(AppText.get(R.string.ui_verifying_apk_again))
            repository.forInstaller(id)
            ManagerApkInstallBackup.createIfInstalled(applicationContext, ::progress)
            checkLocalized(!RuntimeStore.state.value.busy) { AppText.get(R.string.ui_wait_for_the_current_runtime_operation_to_finish_before) }
            // Backup may take minutes: validate metadata, bytes and remaining installer space once more.
            val file = repository.forInstaller(id)
            val uri = FileProvider.getUriForFile(this@ManagerUpdateActivity, "$packageName.manager-apk", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(AppText.get(R.string.ui_apk_update), uri) }
            startActivity(intent)
            progress(AppText.get(R.string.ui_opened_the_system_installer_if_you_cancel_select_install))
        }
    }
    override fun onDestroy() { operation?.cancel(); repository.close(); super.onDestroy() }
}
