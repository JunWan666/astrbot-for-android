package io.astrbot.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

/** On-demand, read-only usage. A closed or background page cancels directory enumeration. */
class StorageUsageActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme {
            var report by remember { mutableStateOf<StorageUsageReport?>(null) }
            var busy by remember { mutableStateOf(false) }
            var phase by remember { mutableStateOf(AppText.get(R.string.ui_reading_storage_information)) }
            var refresh by remember { mutableIntStateOf(0) }
            var detail by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(refresh) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    busy = true
                    try {
                        report = StorageUsageRepository(this@StorageUsageActivity).scan { phase = it }
                        phase = AppText.get(R.string.ui_measurement_complete_files_in_use_may_continue_to_change)
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { phase = AppText.get(R.string.ui_statistics_could_not_be_completed_refresh_to_try_again) }
                    finally { busy = false }
                }
            }
            BackHandler { finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { finish() }) { Text(AppText.get(R.string.ui_back)) }
                        Text(AppText.get(R.string.ui_storage_usage), Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { refresh++ }, enabled = !busy) { Text(AppText.get(R.string.ui_refresh_statistics)) }
                    }
                    Text(phase, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val current = report
                    val rows = mutableListOf<ManagerMenuItem>()
                    fun bytes(value: Long) = Formatter.formatShortFileSize(this@StorageUsageActivity, value)
                    fun info(title: String, subtitle: String, text: String) {
                        rows += ManagerMenuItem(title, R.drawable.ic_info, subtitle) { detail = title to text }
                    }
                    current?.let { result ->
                        val available = result.space.availableBytes?.let(::bytes) ?: AppText.get(R.string.ui_unavailable)
                        info(AppText.get(R.string.ui_free_internal_storage), available + " · " + result.space.pressure.title,
                            AppText.get(R.string.ui_currently_available_n_n, available, StorageSpace.HELP))
                        result.app?.let { app ->
                            info(AppText.get(R.string.ui_total_app_storage), bytes(Math.addExact(app.app, app.data)),
                                AppText.get(R.string.ui_system_accounting_napp_code_napp_data_ncache_included_n, bytes(app.app), bytes(app.data), bytes(app.cache)) +
                                    AppText.get(R.string.ui_cache_is_already_included_in_app_data_and_is))
                        } ?: info(AppText.get(R.string.ui_total_app_storage), AppText.get(R.string.ui_not_provided_by_the_system), AppText.get(R.string.ui_system_accounting_is_unavailable_individual_directories_can_still_be))
                        result.groups.forEach { group ->
                            val value = (if (group.complete) "" else AppText.get(R.string.ui_at_least)) + bytes(group.bytes)
                            info(group.title, value + if (group.complete) "" else AppText.get(R.string.ui_partial_scan),
                                AppText.get(R.string.ui_n_regular_files_n_n_n_n, value, group.files, group.scope) +
                                    AppText.get(R.string.ui_logical_file_sizes_each_inode_is_counted_once_per) +
                                    AppText.get(R.string.ui_skipped_links_and_special_files, group.skippedLinks, group.skippedSpecial) +
                                    (if (!group.complete) AppText.get(R.string.ui_n_nsome_paths_were_unreadable_files_changed_or_the) else "") +
                                    AppText.get(R.string.ui_n_nscanning_does_not_pause_services_this_directory_cannot))
                        }
                    }
                    rows += ManagerMenuItem(AppText.get(R.string.ui_about_storage_and_cleanup), R.drawable.ic_document, AppText.get(R.string.ui_accounting_methods_and_recommendations)) {
                        detail = AppText.get(R.string.ui_about_storage_and_cleanup) to (StorageSpace.HELP + "\n\n" +
                            AppText.get(R.string.ui_the_system_total_includes_app_code_data_and_cache) +
                            AppText.get(R.string.ui_each_directory_scan_is_limited_to_100_000_entries) +
                            AppText.get(R.string.ui_services_keep_running_during_scanning_so_files_can_change))
                    }
                    rows += ManagerMenuItem(AppText.get(R.string.ui_system_storage_settings), R.drawable.ic_settings, AppText.get(R.string.ui_view_storage_usage_for_the_whole_phone)) {
                        runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
                    }
                    FixedManagerMenu(rows, Modifier.weight(1f))
                }
            }
            detail?.let { (title, text) -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(title) },
                text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { detail = null }) { Text(AppText.get(R.string.ui_close)) } }) }
        } }
    }
}
