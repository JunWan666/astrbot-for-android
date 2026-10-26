package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

enum class EnvironmentCheckStatus { OK, ATTENTION, FAILED }
data class EnvironmentCheckItem(val title: String, val status: EnvironmentCheckStatus, val detail: String)
data class EnvironmentCheckReport(val checkedAtMillis: Long, val items: List<EnvironmentCheckItem>,
    val canRepair: Boolean, val repairExplanation: String)

/** Explicit local checks; never starts either server, rewrites configuration or recovers journals. */
internal class EnvironmentChecks(private val context: Context,
    private val pythonCheck: suspend (BootstrapRuntime) -> Boolean = { runtime ->
        runtime.execute("/opt/astrbot-venv/bin/python", "-I", "-B", "-c",
            "import quart, fastapi, faiss; print('environment-check-ok')", timeoutSeconds = 45).let {
            it.exitCode == 0 && it.output.lineSequence().any { line -> line.trim() == "environment-check-ok" }
        }
    }) {
    suspend fun check(): EnvironmentCheckReport = withContext(Dispatchers.IO) {
        val runtime = BootstrapRuntime(context)
        val root = runtime.rootfs
        val items = mutableListOf<EnvironmentCheckItem>()
        fun item(title: String, status: EnvironmentCheckStatus, detail: String) { items += EnvironmentCheckItem(title, status, detail) }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val launchFiles = listOf("libprocess-runner.so", "libproot.so", "libproot-loader.so")
        val nativeReady = launchFiles.all { File(native, it).isFile && File(native, it).canExecute() }
        item(AppText.get(R.string.ui_apk_launch_components), if (nativeReady) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (nativeReady) AppText.get(R.string.ui_bundled_launch_files_are_readable_and_executable) else AppText.get(R.string.ui_apk_launch_components_are_missing_or_not_executable_install))
        val bundled = readJson { runtime.bundledManifest() }
        val marker = readJson {
            val file = File(root, ".astrbot-bootstrap.json")
            require(file.isFile && file.length() <= 64 * 1024)
            JSONObject(file.readText())
        }
        val installed = marker?.optString("kind") == "complete" && marker.optString("abi") == runtime.abi
        item(AppText.get(R.string.ui_runtime_installation_record), if (installed) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (installed) AppText.get(R.string.ui_complete_runtime_recognized) else AppText.get(R.string.ui_complete_initialization_is_unfinished_or_its_record_is_unrecognized))
        val pending = listOf("restore.json", "update.json", "runtime-update.json", "persona.json", "backup-retention.json").any {
            DurableFiles.exists(File(context.filesDir, "operations/$it"))
        }
        item(AppText.get(R.string.ui_maintenance_recovery_records), if (pending) EnvironmentCheckStatus.ATTENTION else EnvironmentCheckStatus.OK,
            if (pending) AppText.get(R.string.ui_an_unfinished_operation_exists_return_to_the_manager_to) else AppText.get(R.string.ui_no_pending_maintenance_recovery_found))
        val resources = listOf(
            "Ubuntu Shell" to "usr/bin/dash", "Python" to "usr/bin/python3.12",
            AppText.get(R.string.ui_python_virtual_environment) to "opt/astrbot-venv/pyvenv.cfg", AppText.get(R.string.ui_astrbot_entry_point) to "opt/astrbot/main.py",
            AppText.get(R.string.ui_astrbot_webui) to "opt/astrbot/astrbot/dashboard/dist/index.html", "Linux QQ" to "opt/qq/qq",
            "NapCat" to "opt/qq/resources/app/app_launcher/napcat/napcat.mjs", "Xvfb" to "usr/bin/Xvfb")
        val missing = resources.filterNot { File(root, it.second).let { file -> file.isFile && file.canRead() } }.map { it.first }
        item(AppText.get(R.string.ui_runtime_resource_files), if (missing.isEmpty()) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (missing.isEmpty()) AppText.get(R.string.ui_key_entry_files_exist_this_does_not_verify_complete) else AppText.get(R.string.ui_missing_or_unreadable, missing.joinToString("、")))
        val versions = if (installed) runCatching { InstalledVersions.read(root) }.getOrNull() else null
        item(AppText.get(R.string.ui_version_and_backup_identity), if (versions != null) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (versions != null) AppText.get(R.string.ui_component_combination_and_backup_compatibility_identity_are_readable) else AppText.get(R.string.ui_version_information_is_incomplete_so_a_repair_transaction_cannot))
        var configurationFailed = false
        for (id in ServiceId.entries) {
            val file = File(context.filesDir, if (id == ServiceId.ASTRBOT) "persistent/astrbot/data/cmd_config.json" else "persistent/napcat/config/webui.json")
            val connection = RuntimeConfiguration(context).read(id, recoverPendingWrite = false)
            val status = when {
                !file.exists() && !File(file.path + ".bak").exists() -> EnvironmentCheckStatus.ATTENTION
                connection.endpoint == null && connection.enabled -> EnvironmentCheckStatus.FAILED
                !connection.enabled -> EnvironmentCheckStatus.ATTENTION
                else -> EnvironmentCheckStatus.OK
            }
            configurationFailed = configurationFailed || status == EnvironmentCheckStatus.FAILED
            item(AppText.get(R.string.ui_webui_configuration, id.label), status, when {
                !file.exists() && !File(file.path + ".bak").exists() -> AppText.get(R.string.ui_not_created_yet_first_start_prepares_default_configuration)
                status == EnvironmentCheckStatus.FAILED -> AppText.get(R.string.ui_configuration_format_listen_address_or_port_is_unrecognized_repairing)
                !connection.enabled -> AppText.get(R.string.ui_the_webui_is_disabled_in_project_configuration)
                else -> AppText.get(R.string.ui_local_endpoint_configuration_is_recognized_login_models_qq_and)
            })
        }
        var dependenciesReady = false
        if (installed && nativeReady && !pending && missing.isEmpty()) {
            dependenciesReady = try { pythonCheck(runtime) }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { false }
            item(AppText.get(R.string.ui_python_dependencies), if (dependenciesReady) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
                if (dependenciesReady) AppText.get(R.string.ui_loaded_quart_fastapi_and_faiss_in_isolated_mode_without)
                else AppText.get(R.string.ui_python_or_a_dependency_failed_to_load_check_runtime))
        } else item(AppText.get(R.string.ui_python_dependencies), EnvironmentCheckStatus.ATTENTION,
            AppText.get(R.string.ui_installation_launch_files_and_maintenance_conditions_must_pass_first))
        val sameImage = installed && bundled?.optString("kind") == "complete" &&
            marker?.optString("sha256") == bundled.optString("sha256")
        val required = bundled?.optLong("unpackedBytes", 0)?.takeIf { it in 1..12L * 1024 * 1024 * 1024 }
            ?.let { it * 2 + 64L * 1024 * 1024 }
        val enoughSpace = required != null && context.filesDir.usableSpace >= required
        item(AppText.get(R.string.ui_repair_staging_space), if (enoughSpace) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.ATTENTION,
            required?.let { AppText.get(R.string.ui_available_mib_runtime_preparation_requires_at_least_mib_snapshot, context.filesDir.usableSpace / 1024 / 1024, it / 1024 / 1024) }
                ?: AppText.get(R.string.ui_cannot_determine_the_full_resource_size_in_this_apk))
        val damaged = missing.isNotEmpty() || installed && !dependenciesReady
        val reason = when {
            pending -> AppText.get(R.string.ui_complete_the_pending_maintenance_operation_then_check_again)
            !nativeReady -> AppText.get(R.string.ui_install_an_apk_with_the_same_signing_certificate_to)
            !installed -> AppText.get(R.string.ui_initialize_the_runtime_first_an_unrecognized_existing_runtime_will)
            versions == null -> AppText.get(R.string.ui_version_information_is_incomplete_export_diagnostics_first_the_runtime)
            !sameImage -> AppText.get(R.string.ui_the_installed_runtime_differs_from_the_bundled_resources_use)
            configurationFailed -> AppText.get(R.string.ui_resolve_dashboard_configuration_issues_first_redeploying_resources_does)
            !enoughSpace -> AppText.get(R.string.ui_free_staging_space_first_data_snapshot_space_will_also)
            !damaged -> AppText.get(R.string.ui_no_resource_damage_was_identified_by_this_check_manage)
            else -> AppText.get(R.string.ui_the_same_version_bundled_resources_can_be_redeployed_the)
        }
        EnvironmentCheckReport(System.currentTimeMillis(), items,
            pending.not() && nativeReady && installed && versions != null && sameImage && !configurationFailed && enoughSpace && damaged, reason)
    }

    private fun readJson(read: () -> JSONObject): JSONObject? = runCatching(read).getOrNull()
}
