package io.astrbot.android

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.debug.DebugAccess
import io.astrbot.android.debug.ShizukuInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.shizuku.Shizuku

class DebugAccessActivity : LocalizedActivity() {
    private var installerBusy by mutableStateOf(false)
    private var installerError by mutableStateOf<String?>(null)
    private var pendingInstaller = false
    private val installationPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingInstaller && packageManager.canRequestPackageInstalls()) installManager()
        else installerError = AppText.get(R.string.manager_update_permission_denied)
        pendingInstaller = false
    }
    private fun installManager() {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstaller = true
            runCatching { installationPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { pendingInstaller = false; installerError = AppText.get(R.string.debug_open_failed) }
            return
        }
        lifecycleScope.launch {
            installerBusy = true; installerError = null
            try {
                val apk = ShizukuInstaller.prepare(this@DebugAccessActivity)
                val uri = FileProvider.getUriForFile(this@DebugAccessActivity,"$packageName.manager-apk",apk)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Shizuku",uri) })
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { installerError = AppText.get(R.string.debug_installer_failed) }
            finally { installerBusy = false }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstaller = savedInstanceState?.getBoolean("pendingInstaller") ?: false
        enableEdgeToEdge()
        setContent { ManagerTheme {
            var status by remember { mutableStateOf(DebugAccess.status(this)) }
            var report by remember { mutableStateOf<JSONObject?>(null) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var errorDetail by remember { mutableStateOf<String?>(null) }
            var outcome by remember { mutableStateOf<String?>(null) }
            var confirm by remember { mutableStateOf<Boolean?>(null) }
            var batteryConfirm by remember { mutableStateOf<Boolean?>(null) }
            var batteryExempt by remember { mutableStateOf(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) }
            val scope = rememberCoroutineScope()
            fun refreshStatus() {
                status = DebugAccess.status(this)
                batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                if (status != DebugAccess.Status.READY) report = null
            }
            fun readOrApply(restore: Boolean? = null, exemption: Boolean? = null) {
                if (busy) return
                val previous = report
                busy = true; error = null; errorDetail = null; outcome = null
                scope.launch {
                    try {
                        if (exemption != null) {
                            val result = DebugAccess.setBatteryExemption(this@DebugAccessActivity, exemption)
                            batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                            report = (previous ?: JSONObject()).put("background", result)
                            outcome = AppText.get(when {
                                !result.optBoolean("writeSucceeded") -> R.string.debug_write_refused
                                !result.optBoolean("configurationConfirmed") -> R.string.debug_battery_unconfirmed
                                !exemption -> R.string.debug_battery_removed
                                batteryExempt -> R.string.debug_battery_confirmed
                                else -> R.string.debug_battery_unconfirmed
                            })
                        } else report = DebugAccess.inspect(this@DebugAccessActivity, restore)
                        if (restore != null) outcome = AppText.get(when {
                            report?.optBoolean("writeSucceeded") != true -> R.string.debug_write_refused
                            report?.optBoolean("configurationConfirmed") != true -> R.string.debug_write_not_retained
                            restore -> R.string.debug_default_restored
                            report?.optBoolean("effectiveConfirmed") == true -> R.string.debug_effective_confirmed
                            else -> R.string.debug_saved_unconfirmed
                        })
                    } catch (failure: TimeoutCancellationException) {
                        error = AppText.get(R.string.debug_bind_timeout)
                        errorDetail = "${failure.javaClass.simpleName}: ${failure.message}".take(600)
                    }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        error = AppText.get(R.string.debug_operation_failed)
                        errorDetail = "${failure.javaClass.simpleName}: ${failure.message}".take(600)
                    }
                    finally { busy = false; refreshStatus() }
                }
            }
            DisposableEffect(Unit) {
                val received = Shizuku.OnBinderReceivedListener { refreshStatus() }
                val dead = Shizuku.OnBinderDeadListener { refreshStatus() }
                val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }
                Shizuku.addBinderReceivedListenerSticky(received)
                Shizuku.addBinderDeadListener(dead)
                Shizuku.addRequestPermissionResultListener(permission)
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
                    refreshStatus()
                    if (status == DebugAccess.Status.READY) readOrApply()
                } }
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    Shizuku.removeBinderReceivedListener(received)
                    Shizuku.removeBinderDeadListener(dead)
                    Shizuku.removeRequestPermissionResultListener(permission)
                }
            }
            LaunchedEffect(status) {
                if (status == DebugAccess.Status.READY) readOrApply()
            }
            fun open(intent: Intent) {
                runCatching { startActivity(intent) }.onFailure { error = AppText.get(R.string.debug_open_failed) }
            }
            PermissionsPage(
                status = status, report = report, busy = busy, installerBusy = installerBusy,
                batteryExempt = batteryExempt, message = outcome, error = error ?: installerError,
                onBack = { finish() }, onInstall = { installManager() },
                onAuthorize = {
                    runCatching { Shizuku.requestPermission(DebugAccess.REQUEST) }
                        .onFailure { error = AppText.get(R.string.debug_operation_failed) }
                },
                onManager = {
                    val intent = packageManager.getLaunchIntentForPackage(DebugAccess.MANAGER)
                    if (intent != null) open(intent) else error = AppText.get(R.string.debug_open_failed)
                },
                onDeveloperSettings = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
                onOfficialSite = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/"))) },
                onBatterySettings = { open(BackgroundGuide.intents(this@DebugAccessActivity, BackgroundTopic.BATTERY).first()) },
                onBatteryChange = { batteryConfirm = it }, onLimitChange = { confirm = it },
                onRefresh = {
                    refreshStatus()
                    if (status == DebugAccess.Status.READY) readOrApply()
                },
                onDeviceAccess = { startActivity(Intent(this@DebugAccessActivity, DeviceAccessActivity::class.java)) },
                onCopy = {
                    val diagnostic = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
                        .put("sdk", Build.VERSION.SDK_INT).put("connection", status.name).put("report", report ?: JSONObject.NULL)
                        .put("batteryOptimizationExempt", batteryExempt)
                        .put("error", error ?: JSONObject.NULL).put("detail", errorDetail ?: JSONObject.NULL).toString(2)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Shizuku", diagnostic))
                },
            )
            confirm?.let { restore -> AlertDialog(onDismissRequest = { confirm = null },
                title = { Text(AppText.get(if (restore) R.string.debug_limit_restore else R.string.debug_limit_raise)) },
                text = { Text(AppText.get(if (restore) R.string.debug_restore_confirm else R.string.debug_raise_confirm)) },
                confirmButton = { TextButton(onClick = { confirm = null; readOrApply(restore) }) { Text(AppText.get(R.string.ui_ok)) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
            batteryConfirm?.let { enabled -> AlertDialog(onDismissRequest = { batteryConfirm = null },
                title = { Text(AppText.get(if (enabled) R.string.debug_battery_add else R.string.debug_battery_remove)) },
                text = { Text(AppText.get(if (enabled) R.string.debug_battery_add_confirm else R.string.debug_battery_remove_confirm)) },
                confirmButton = { TextButton(onClick = { batteryConfirm = null; readOrApply(exemption = enabled) }) { Text(AppText.get(R.string.ui_ok)) } },
                dismissButton = { TextButton(onClick = { batteryConfirm = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pendingInstaller", pendingInstaller); super.onSaveInstanceState(outState) }
}
