package io.astrbot.android.debug

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.astrbot.android.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku

internal object DebugAccess {
    private val requests = Mutex()
    private var compatibilityUntil = 0L
    const val MANAGER = "moe.shizuku.privileged.api"
    const val REQUEST = 7101
    enum class Status { NOT_INSTALLED, STOPPED, NEED_PERMISSION, DENIED, READY, UNSUPPORTED }

    fun status(context: Context): Status = runCatching {
        if (Shizuku.pingBinder()) {
            when {
                Shizuku.isPreV11() || Shizuku.getVersion() < 12 -> Status.UNSUPPORTED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
                Shizuku.shouldShowRequestPermissionRationale() -> Status.DENIED
                else -> Status.NEED_PERMISSION
            }
        } else {
            try { context.packageManager.getApplicationInfo(MANAGER, 0); Status.STOPPED }
            catch (_: PackageManager.NameNotFoundException) { Status.NOT_INSTALLED }
        }
    }.getOrDefault(Status.STOPPED)

    // Never run the Ubuntu runtime as shell or root.
    suspend fun inspect(context: Context, restoreDefault: Boolean? = null): JSONObject = access(context,
        { if (restoreDefault == null) it.inspect() else it.setLimit(restoreDefault) },
        { ShizukuCompatibility.inspect(restoreDefault) })

    suspend fun setBatteryExemption(context: Context, enabled: Boolean): JSONObject = access(context,
        { it.setBatteryExemption(enabled) }, { ShizukuCompatibility.setBatteryExemption(enabled) })

    suspend fun deviceOperation(context: Context, operation: String, args: JSONObject, allowed: () -> Boolean = { true }): JSONObject = access(context,
        { check(allowed()) { "Device permission revoked" }; it.deviceOperation(operation, args.toString()) },
        { check(allowed()) { "Device permission revoked" }; ShizukuCompatibility.deviceOperation(operation, args) })

    private suspend fun access(context: Context, operation: (IProcessLimitService) -> String, compatibility: () -> JSONObject): JSONObject = requests.withLock { withContext(Dispatchers.IO) {
        check(status(context) == Status.READY)
        if (android.os.SystemClock.elapsedRealtime() < compatibilityUntil && Shizuku.getVersion() in 12..13) return@withContext compatibility()
        val connected = CompletableDeferred<IProcessLimitService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IProcessLimitService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Debug service disconnected"))
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ProcessLimitService::class.java))
            // Removing a UserService is asynchronous. A fresh tag cannot reconnect to
            // the dying Binder of the previous read or a page that was just closed.
            .tag("process-limit-${java.util.UUID.randomUUID()}").version(BuildConfig.VERSION_CODE)
            .daemon(false).processNameSuffix("debug_settings")
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val service = try { withTimeout(15_000) { connected.await() } }
            catch (failure: TimeoutCancellationException) {
                if (status(context) != Status.READY || Shizuku.getVersion() !in 12..13) throw failure
                compatibilityUntil = android.os.SystemClock.elapsedRealtime() + 5 * 60_000
                return@withContext compatibility()
                    .put("userServiceDiagnostic", "UserService connection timed out; compatibility transport used")
            }
            JSONObject(operation(service))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    } }
}
