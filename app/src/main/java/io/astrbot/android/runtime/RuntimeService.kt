package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.localizedSummary
import io.astrbot.android.AppText
import io.astrbot.android.R

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.os.PowerManager
import io.astrbot.android.MainActivity
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: RuntimeController
    private val operations = ConcurrentHashMap.newKeySet<Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, AppText.get(R.string.ui_local_services), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(AppText.get(R.string.ui_preparing_runtime_tasks)))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrbot:runtime")
            .apply { setReferenceCounted(false) }
        scope.launch {
            while (isActive) {
                wakeLock?.acquire(10 * 60 * 1000L)
                delay(9 * 60 * 1000L)
            }
        }
        controller = RuntimeController(this, scope)
        scope.launch {
            RuntimeStore.state.combine(NetworkConnectivity.observe(applicationContext)) { state, network -> state to network }
                .combine(SshStore.state) { (state, network), ssh -> Triple(state, network, ssh) }
                .combine(CpolarStore.state) { current, _ -> current }
                .combine(TerminalStore.state) { current, _ -> current }
                .combine(AppText.languageChanged) { current, _ -> current }
                .collectLatest { (state, network, ssh) ->
                val detail = if (state.busy) state.phase else state.services.values.joinToString(" · ") { "${it.id.label} ${it.localizedSummary()}" }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("${network.title} · $detail" +
                    (if (ssh.running) AppText.get(R.string.ui_ssh_running) else "") +
                    (if (CpolarStore.state.value.running) " · cpolar" else "") +
                    (if (TerminalStore.state.value.running) " · ${AppText.get(R.string.terminal_running_count, TerminalStore.state.value.runningCount)}" else "") +
                    (state.storageSpace.warning?.let { " · ${state.storageSpace.pressure.title}" } ?: "")))
            }
        }
        scope.launch {
            TerminalStore.state.map { it.running }.distinctUntilChanged().drop(1).collect { running ->
                if (!running && operations.isEmpty() && !RuntimeStore.state.value.busy && idle()) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("operation") ?: "reconcile"
        val id = intent?.getStringExtra("serviceId")?.let { raw -> ServiceId.entries.firstOrNull { it.name == raw } }
        val requestId = intent?.getStringExtra("requestId")
        if (action == "stop") {
            // Persist a user's stop before cancelling queued work or recovering maintenance.
            val edit = getSharedPreferences("manager", Context.MODE_PRIVATE).edit()
            (id?.let { listOf(it) } ?: ServiceId.entries).forEach { edit.putBoolean("desired_${it.name}", false) }
            checkLocalized(edit.commit()) { AppText.get(R.string.ui_unable_to_save_stop_preferences) }
        }
        val previous = if (action == "stop") operations.toList() else emptyList()
        previous.forEach { it.cancel() }
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            // Lifecycle and maintenance commands share one controller mutex. Stop also cancels
            // queued work, and waits for an interrupted restore to recover before stopping.
            previous.joinAll()
            controller.execute(action, id, intent?.getStringExtra("backupId"), intent?.getStringExtra("sourceUri"), intent?.getStringExtra("updateId"),
                intent?.getIntExtra("backupLimitCount", 0) ?: 0, intent?.getIntExtra("backupLimitMiB", 0) ?: 0,
                intent?.getIntExtra("sshPort", 8022) ?: 8022, intent?.getBooleanExtra("sshLan", false) ?: false, intent?.getStringExtra("sshPassword"),
                intent?.takeIf { it.hasExtra("terminalSessionId") }?.getLongExtra("terminalSessionId", -1))
            intent?.removeExtra("sshPassword")
            RuntimeStore.state.update { it.copy(completedOperation = requestId) }
            if (idle()) stopSelf(startId)
        }
        operations.add(operation)
        operation.invokeOnCompletion { operations.remove(operation) }
        operation.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun idle() = RuntimeStore.state.value.services.values.none { it.desiredRunning } &&
        !SshStore.state.value.running && !CpolarStore.state.value.running && !TerminalStore.state.value.running

    override fun onDestroy() {
        val closingOperations = operations.toList()
        closingOperations.forEach { it.cancel() }
        scope.launch {
            try { closingOperations.joinAll(); controller.close() }
            finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                scope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeService::class.java).putExtra("operation", "stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopSsh = PendingIntent.getService(this, 2, Intent(this, RuntimeService::class.java).putExtra("operation", "ssh-stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopCpolar = PendingIntent.getService(this, 3, Intent(this, RuntimeService::class.java).putExtra("operation", "cpolar-stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopTerminal = PendingIntent.getService(this, 4, Intent(this, RuntimeService::class.java).putExtra("operation", "terminal-stop-all"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.rgb(56, 103, 255))
            .setContentTitle(AppText.get(R.string.ui_astrbot_manager)).setContentText(detail).setContentIntent(open)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setOngoing(true).addAction(Notification.Action.Builder(null, AppText.get(R.string.ui_stop_bots), stop).build())
            .apply {
                if (TerminalStore.state.value.running) addAction(Notification.Action.Builder(null, AppText.get(R.string.terminal_end_all), stopTerminal).build())
                if (SshStore.state.value.running) addAction(Notification.Action.Builder(null, AppText.get(R.string.ui_stop_ssh), stopSsh).build())
                if (CpolarStore.state.value.running) addAction(Notification.Action.Builder(null, AppText.get(R.string.cpolar_stop), stopCpolar).build())
            }.build()
    }

    companion object {
        private const val CHANNEL = "local-runtime"
        private const val NOTIFICATION_ID = 2001
        fun command(context: Context, operation: String, id: ServiceId? = null, backupId: String? = null, sourceUri: String? = null, updateId: String? = null,
                    backupLimitCount: Int = 0, backupLimitMiB: Int = 0, sshPort: Int = 8022, sshLan: Boolean = false, sshPassword: String? = null,
                    terminalSessionId: Long? = null): String {
            val requestId = UUID.randomUUID().toString()
            context.startForegroundService(Intent(context, RuntimeService::class.java)
                .putExtra("operation", operation).putExtra("serviceId", id?.name).putExtra("backupId", backupId).putExtra("sourceUri", sourceUri).putExtra("updateId", updateId).putExtra("requestId", requestId)
                .putExtra("backupLimitCount", backupLimitCount).putExtra("backupLimitMiB", backupLimitMiB)
                .putExtra("sshPort", sshPort).putExtra("sshLan", sshLan).putExtra("sshPassword", sshPassword)
                .apply { if (terminalSessionId != null) putExtra("terminalSessionId", terminalSessionId) })
            return requestId
        }
    }
}
