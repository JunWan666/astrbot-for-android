package io.astrbot.android

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.debug.DebugAccess
import io.astrbot.android.device.*
import kotlinx.coroutines.*
import java.io.File

class DeviceAccessActivity : LocalizedActivity() {
    private var transferBusy by mutableStateOf(false)
    private var exporting: File? = null
    private fun shared() = File(getExternalFilesDir(null) ?: filesDir, "shared").apply { mkdirs() }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            transferBusy = true
            val success = withContext(Dispatchers.IO) { runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")?.take(120)?.takeUnless { it == "." || it == ".." || it.isBlank() } ?: "shared-file"
                val root = shared()
                var target = File(root, name)
                var suffix = 1
                while (target.exists()) target = File(root, "${suffix++}-$name")
                val pending = File(root, ".import-${java.util.UUID.randomUUID()}")
                try {
                    requireNotNull(contentResolver.openInputStream(uri)).use { input -> pending.outputStream().use { output ->
                        val buffer = ByteArray(65536); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            total += count; check(total <= 512L * 1024 * 1024); output.write(buffer, 0, count) }
                    } }
                    check(pending.renameTo(target))
                } finally { pending.delete() }
            }.isSuccess }
            transferBusy = false
            Toast.makeText(this@DeviceAccessActivity, AppText.get(if (success) R.string.device_share_imported else R.string.device_share_failed), Toast.LENGTH_LONG).show()
        }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exporting; exporting = null
        if (uri != null && source != null) lifecycleScope.launch {
            transferBusy = true
            val success = withContext(Dispatchers.IO) { runCatching {
                check(source.canonicalFile.parentFile == shared().canonicalFile && !java.nio.file.Files.isSymbolicLink(source.toPath()))
                source.inputStream().use { input -> requireNotNull(contentResolver.openOutputStream(uri, "w")).use { input.copyTo(it) } }
            }.isSuccess }
            transferBusy = false
            Toast.makeText(this@DeviceAccessActivity, AppText.get(if (success) R.string.device_share_exported else R.string.device_share_failed), Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { ManagerTheme {
            val state by DeviceBridge.state.collectAsStateWithLifecycle()
            var backend by remember { mutableStateOf(DebugAccess.status(this) == DebugAccess.Status.READY) }
            var accessibility by remember { mutableStateOf(DeviceAccessibility.instance != null) }
            var confirm by remember { mutableStateOf(false) }
            var exportChoices by remember { mutableStateOf<List<File>?>(null) }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
                    backend = DebugAccess.status(this@DeviceAccessActivity) == DebugAccess.Status.READY
                    accessibility = DeviceAccessibility.instance != null
                } }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            Scaffold(topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(22.dp)) }
                    Text(AppText.get(R.string.device_access_title), style = MaterialTheme.typography.titleLarge)
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ManagerSection(AppText.get(R.string.group_device_bridge)) {
                        DeviceToggle(AppText.get(R.string.device_access_title), AppText.get(R.string.device_access_private), state.enabled, true) {
                            if (it) confirm = true else DeviceBridgeService.setEnabled(this@DeviceAccessActivity, false)
                        }
                        HorizontalDivider()
                        DeviceToggle(AppText.get(R.string.device_access_screen), AppText.get(R.string.device_access_screen_detail), state.screen, state.enabled && (backend || accessibility)) {
                            DeviceBridge.permissions(screen = it)
                        }
                        HorizontalDivider()
                        DeviceToggle(AppText.get(R.string.device_access_control), AppText.get(R.string.device_access_control_detail), state.control, state.enabled && (backend || accessibility)) {
                            DeviceBridge.permissions(control = it)
                        }
                        if (state.enabled) Text(AppText.get(R.string.device_access_requests, state.requests), style = MaterialTheme.typography.labelSmall)
                    }
                    ManagerSection(AppText.get(R.string.group_privilege)) {
                        Text("Shizuku · ${AppText.get(if (backend) R.string.debug_state_ready else R.string.device_backend_unavailable)}", style = MaterialTheme.typography.bodyMedium)
                        Text(AppText.get(R.string.device_backend_detail), style = MaterialTheme.typography.bodySmall)
                    }
                    ManagerSection(AppText.get(R.string.group_accessibility), padded = false) {
                        ManagerSettingsRow(AppText.get(R.string.device_accessibility_title), R.drawable.ic_settings,
                            AppText.get(if (accessibility) R.string.device_accessibility_on else R.string.device_accessibility_detail), divider = false) {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    ManagerSection(AppText.get(R.string.group_shared_files)) {
                        Text("/android-share", style = MaterialTheme.typography.titleMedium)
                        Text(AppText.get(R.string.device_share_detail), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }, enabled = !transferBusy, modifier = Modifier.weight(1f)) { Text(AppText.get(R.string.device_share_import)) }
                            OutlinedButton(onClick = {
                                exportChoices = shared().listFiles()?.filter { it.isFile && !it.name.startsWith('.') &&
                                    it.canonicalFile.parentFile == shared().canonicalFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }?.sortedBy { it.name }?.take(100).orEmpty()
                            }, enabled = !transferBusy, modifier = Modifier.weight(1f)) { Text(AppText.get(R.string.device_share_export)) }
                        }
                        if (transferBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ManagerSection(AppText.get(R.string.device_cli_title)) {
                        Text("android-device info\nandroid-device app-status\nandroid-device tree\nandroid-device screenshot /android-share/screen.jpg\nandroid-device tap 200 500\nandroid-device input '你好'", style = MaterialTheme.typography.bodySmall)
                        Text(AppText.get(R.string.device_cli_detail), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(AppText.get(R.string.device_access_title)) },
                text = { Text(AppText.get(R.string.device_access_confirm)) }, confirmButton = { TextButton(onClick = {
                    confirm = false; DeviceBridgeService.setEnabled(this@DeviceAccessActivity, true)
                }) { Text(AppText.get(R.string.ui_ok)) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(AppText.get(R.string.ui_cancel)) } })
            exportChoices?.let { files -> AlertDialog(onDismissRequest = { exportChoices = null }, title = { Text(AppText.get(R.string.device_share_export)) },
                text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (files.isEmpty()) Text(AppText.get(R.string.device_share_empty))
                    files.forEach { file -> TextButton(onClick = { exporting = file; exportChoices = null; exportFile.launch(file.name) }) { Text(file.name) } }
                } }, confirmButton = { TextButton(onClick = { exportChoices = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
        } }
    }
}

@Composable private fun DeviceToggle(title: String, detail: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = changed, enabled = enabled)
    }
}
