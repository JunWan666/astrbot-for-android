package io.astrbot.android.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** One-time retirement of App-managed integrations. Account and terminal files stay in place. */
internal object LegacyIntegrationMigration {
    @Synchronized fun apply(context: Context) {
        val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
        if (preferences.getBoolean("legacy-integrations-retired-v1", false)) return
        check(preferences.edit().putBoolean("desired_NAPCAT", false).commit())
        val files = context.filesDir.canonicalFile
        val profile = File(files, "manager/extensions/desktop/compat-profile.json")
        val value = read(profile)
        val library = value?.optString("library").orEmpty()
        if (value?.optString("symbol") == "snowluma_linux_hook_start_dynamic" &&
            value.optString("waitModule") == "wrapper.node" && library.startsWith("/persistent/") &&
            library.endsWith("/native/snowluma-linux-arm64.so")) {
            val directory = File(files, library.removePrefix("/").removeSuffix("/native/snowluma-linux-arm64.so"))
            // The receipt distinguishes our former installer from a user's manual profile.
            val receipt = File(directory, ".astrbot-archive.json")
            if (safe(files, directory) && read(receipt)?.optString("sha256")?.matches(Regex("[a-f0-9]{64}")) == true) {
                val retired = File(files, "manager/retired-integrations/compat-profile.json")
                check(safe(files, retired))
                if (!retired.exists()) DurableFiles.write(retired, requireNotNull(value))
                check(DurableFiles.read(retired).toString() == value.toString())
                DurableFiles.removeRecord(profile)
            }
        }
        // Only the old immutable, App-provisioned client is eligible for removal.
        // QQ sessions live outside this directory, as do the independent desktop QQ files.
        val oldClient = File(files, "runtime-data/ubuntu/opt/qq")
        val marker = read(File(oldClient, ".astrbot-components.json"))
        val packageInfo = read(File(oldClient, "resources/app/package.json"))
        if (marker?.has("napcat") == true && marker.has("qq") &&
            packageInfo?.optString("main") == "./loadNapCat.js" && safe(files, oldClient)) {
            DurableFiles.delete(oldClient, writableGuestDirectories = true)
        }
        check(preferences.edit().putBoolean("legacy-integrations-retired-v1", true).commit())
    }

    private fun safe(root: File, file: File): Boolean = runCatching {
        var current: File? = file
        while (current != null && current != root) {
            if (Files.isSymbolicLink(current.toPath())) return false
            current = current.parentFile
        }
        current == root && file.canonicalFile.toPath().startsWith(root.toPath())
    }.getOrDefault(false)

    private fun read(file: File): JSONObject? = runCatching {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 64 * 1024)
        JSONObject(file.readText())
    }.getOrNull()
}
