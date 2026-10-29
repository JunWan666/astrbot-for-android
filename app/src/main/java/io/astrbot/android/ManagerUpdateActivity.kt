package io.astrbot.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

class ManagerUpdateActivity : LocalizedActivity() {
    private val repository by lazy { ManagerApkRepository(applicationContext) }
    private var status by mutableStateOf(ManagerApkStatus(null, false, 0, AppText.get(R.string.ui_loading_update_information)))
    private var busy by mutableStateOf(false)
    private var review by mutableStateOf(false)
    private var information by mutableStateOf<String?>(null)
    private var permission by mutableStateOf(false)
    private var pendingInstallId: String? = null
    private var awaitingPermission = false
    private var operation: Job? = null
    private val permissionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingPermission = false
        if (packageManager.canRequestPackageInstalls()) continueInstallation()
        else {
            pendingInstallId = null
            progress(AppText.get(R.string.manager_update_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstallId = savedInstanceState?.getString("pendingInstallId")
        awaitingPermission = savedInstanceState?.getBoolean("awaitingPermission") ?: false
        enableEdgeToEdge()
        runOperation(AppText.get(R.string.ui_read_apk_update_information)) { status = repository.cached() }
        setContent { ManagerTheme {
            var menu by remember { mutableStateOf(false) }
            var clear by remember { mutableStateOf(false) }
            BackHandler { finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { finish() }) { Text(AppText.get(R.string.ui_back)) }
                        Text(AppText.get(R.string.ui_check_for_updates), Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleLarge)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), AppText.get(R.string.ui_more_actions)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(AppText.get(R.string.ui_clear_apk_downloads)) }, enabled = !busy,
                                    onClick = { menu = false; clear = true })
                            }
                        }
                    }
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(AppText.get(R.string.ui_manager_b28f4, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                            Text(status.message, style = MaterialTheme.typography.bodySmall)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    ManagerActionButton(AppText.get(R.string.ui_check_app_updates), {
                        runOperation(AppText.get(R.string.ui_check_app_updates)) {
                            status = repository.check(::progress)
                            if (status.candidate != null) review = true else information = status.message
                        }
                    }, Modifier.fillMaxWidth(), ActionTone.PRIMARY, !busy && repository.configured)
                    if (busy) TextButton(onClick = { operation?.cancel() }) { Text(AppText.get(R.string.ui_cancel_this_operation)) }
                    Spacer(Modifier.weight(1f))
                    Text(AppText.get(R.string.ui_for_astrbot_updates_return_to_the_corresponding_entry_under), style = MaterialTheme.typography.bodySmall)
                }
            }
            val candidate = status.candidate
            if (review && candidate != null) AlertDialog(onDismissRequest = { review = false },
                title = { Text(AppText.get(R.string.manager_update_found, candidate.versionName)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${candidate.bytes / 1024 / 1024} MiB")
                        Text(candidate.notes.ifBlank { AppText.get(R.string.manager_update_no_notes) })
                        Text(AppText.get(R.string.manager_update_flow_notice), style = MaterialTheme.typography.bodySmall)
                        ManagerActionButton(AppText.get(R.string.manager_update_browser), {
                            review = false
                            runOperation(AppText.get(R.string.manager_update_browser)) {
                                val url = repository.browserDownloadUrl(candidate.id)
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                                information = AppText.get(R.string.manager_update_browser_notice)
                            }
                        }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, !busy)
                    }
                }, confirmButton = { TextButton(onClick = {
                    review = false
                    runOperation(AppText.get(R.string.ui_download_app_apk)) {
                        if (!status.prepared) status = repository.download(candidate.id, ::progress)
                        pendingInstallId = candidate.id
                    }
                }, enabled = !busy) { Text(AppText.get(when {
                    status.prepared -> R.string.ui_continue_installation
                    status.downloadedBytes > 0 -> R.string.manager_update_resume_install
                    else -> R.string.manager_update_download_install
                })) } }, dismissButton = { TextButton(onClick = { review = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            information?.let { value -> AlertDialog(onDismissRequest = { information = null },
                title = { Text(AppText.get(R.string.ui_check_for_updates)) }, text = { Text(value) },
                confirmButton = { TextButton(onClick = { information = null }) { Text(AppText.get(R.string.ui_ok)) } }) }
            if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(AppText.get(R.string.ui_clear_apk_downloads)) },
                text = { Text(AppText.get(R.string.ui_delete_incomplete_and_completed_app_apk_downloads_to_free)) },
                confirmButton = { TextButton(onClick = {
                    clear = false; pendingInstallId = null
                    runOperation(AppText.get(R.string.ui_clear_apk_downloads)) { repository.clearDownload(); status = repository.cached() }
                }) { Text(AppText.get(R.string.ui_clear)) } },
                dismissButton = { TextButton(onClick = { clear = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            if (permission) AlertDialog(onDismissRequest = { permission = false; pendingInstallId = null },
                title = { Text(AppText.get(R.string.ui_allow_update_installation)) },
                text = { Text(AppText.get(R.string.manager_update_permission_notice)) },
                confirmButton = { TextButton(onClick = {
                    permission = false; awaitingPermission = true
                    runCatching { permissionResult.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure {
                            awaitingPermission = false; pendingInstallId = null
                            progress(AppText.get(R.string.ui_unable_to_open_installation_permissions_allow_unknown_app_installation))
                        }
                }) { Text(AppText.get(R.string.ui_open_settings)) } },
                dismissButton = { TextButton(onClick = { permission = false; pendingInstallId = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingInstallId", pendingInstallId)
        outState.putBoolean("awaitingPermission", awaitingPermission)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); window.decorView.post { continueInstallation() } }
    private fun continueInstallation() {
        if (busy || operation?.isActive == true || awaitingPermission || permission ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val id = pendingInstallId ?: return
        if (!packageManager.canRequestPackageInstalls()) { permission = true; return }
        runOperation(AppText.get(R.string.ui_verify_apk_and_open_installer)) {
            pendingInstallId = null
            checkLocalized(!RuntimeStore.state.value.busy) { AppText.get(R.string.ui_wait_for_the_current_runtime_operation_to_finish_before) }
            progress(AppText.get(R.string.ui_verifying_apk_again))
            repository.forInstaller(id)
            ManagerApkInstallBackup.createIfInstalled(applicationContext, ::progress)
            checkLocalized(!RuntimeStore.state.value.busy) { AppText.get(R.string.ui_wait_for_the_current_runtime_operation_to_finish_before) }
            val file = repository.forInstaller(id)
            val uri = FileProvider.getUriForFile(this@ManagerUpdateActivity, "$packageName.manager-apk", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(AppText.get(R.string.ui_apk_update), uri) }
            startActivity(intent)
            progress(AppText.get(R.string.manager_update_installer_opened))
        }
    }
    private fun progress(message: String) { status = status.copy(message = message) }
    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = lifecycleScope.launch {
            busy = true
            val log = RuntimeStore.log(applicationContext, LogCategory.MANAGER)
            log.append(AppText.get(R.string.ui_started, label))
            try { block(); log.append(AppText.get(R.string.ui_finished, label)) }
            catch (failure: CancellationException) {
                pendingInstallId = null
                log.append(AppText.get(R.string.ui_cancelled_download_progress_retained, label))
                progress(AppText.get(R.string.ui_operation_cancelled_download_progress_is_retained_so_you_can))
                throw failure
            } catch (failure: Exception) {
                pendingInstallId = null
                log.append(AppText.get(R.string.ui_incomplete, label, failure.javaClass.simpleName))
                val message = failure.userFacingMessage()?.takeIf { it.length in 1..180 }
                progress(message ?: AppText.get(R.string.ui_update_incomplete_check_your_network_or_retry_later_the))
            } finally {
                busy = false
                window.decorView.post { continueInstallation() }
            }
        }
    }
    override fun onDestroy() { operation?.cancel(); repository.close(); super.onDestroy() }
}
