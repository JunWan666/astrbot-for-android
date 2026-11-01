package io.astrbot.android.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.edit
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Restores saved service intentions, never terminal commands or device-control permissions. */
internal object BackgroundRecovery {
    private fun preferences(context: Context) = context.getSharedPreferences("background-recovery", Context.MODE_PRIVATE)
    val changed = MutableStateFlow(0L)
    private var requested = false

    fun enabled(context: Context): Boolean = preferences(context).getBoolean("restore-services", false)
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val saved = preferences(context).edit().putBoolean("restore-services", enabled).commit()
        if (saved) changed.update { it + 1 }
        return saved
    }

    fun hasDesiredServices(context: Context): Boolean = context.getSharedPreferences("manager", Context.MODE_PRIVATE).let { saved ->
        ServiceId.entries.any { saved.getBoolean("desired_${it.name}", false) } ||
            saved.getBoolean("desired_cpolar", false) || saved.getBoolean("desired_harness", false) ||
            saved.getBoolean("desired_desktop", false) ||
            saved.getBoolean(SshRepository.DESIRED, false) && saved.getBoolean(SshRepository.INSTALLED, true)
    }

    fun lastAttempt(context: Context): Pair<Long, Boolean>? = preferences(context).let { saved ->
        saved.getLong("attempt-time", 0).takeIf { it > 0 }?.let { it to saved.getBoolean("attempt-accepted", false) }
    }

    /** Both system callbacks can arrive together. Only one request may be pending. */
    @Synchronized fun request(context: Context, source: String) {
        // Credential-encrypted preferences and the Ubuntu tree are unavailable before first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        if (!enabled(context) || requested || RuntimeStore.owner != null || !hasDesiredServices(context)) return
        if (!File(BootstrapRuntime(context).rootfs, ".astrbot-bootstrap.json").isFile) return
        requested = true
        val accepted = runCatching {
            RuntimeService.command(context, "reconcile")
        }.isSuccess
        if (!accepted) requested = false
        preferences(context).edit {
            putLong("attempt-time", System.currentTimeMillis())
            putBoolean("attempt-accepted", accepted)
        }
        changed.update { it + 1 }
        runCatching { RuntimeStore.log(context, LogCategory.MANAGER).append(
            "${java.time.Instant.now()} background-recovery source=$source request=${if (accepted) "accepted" else "blocked"}") }
    }

    @Synchronized fun runtimeCreated() { requested = false }
}

/** Android delivers BOOT_COMPLETED after the user first unlocks; no direct-boot file access. */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> BackgroundRecovery.request(context, "boot")
            Intent.ACTION_MY_PACKAGE_REPLACED -> BackgroundRecovery.request(context, "package-replaced")
        }
    }
}
