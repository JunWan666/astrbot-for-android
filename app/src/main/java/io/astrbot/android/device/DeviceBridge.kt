package io.astrbot.android.device

import android.app.KeyguardManager
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.system.Os
import io.astrbot.android.BuildConfig
import io.astrbot.android.debug.DebugAccess
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class DeviceBridgeState(val enabled: Boolean = false, val screen: Boolean = false, val control: Boolean = false,
    val requests: Long = 0, val lastOperation: String? = null)

/** Private Unix socket, same Android UID only. No TCP listener or generic privileged shell. */
internal object DeviceBridge {
    val state = MutableStateFlow(DeviceBridgeState())
    private val gate = Mutex()
    private var server: LocalServerSocket? = null
    private var session: String? = null
    private var listener: Job? = null
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()
    private fun configuration(context: Context) = File(context.filesDir, "manager/device-access/session.json")

    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        if (server != null) return
        prepareGuest(context)
        val socketName = "astrbot-device-${UUID.randomUUID()}"
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val socket = LocalServerSocket(socketName)
        try {
            val file = configuration(context)
            file.parentFile!!.mkdirs(); Os.chmod(file.parentFile!!.path, 448)
            val temporary = File(file.parentFile, "session.new")
            temporary.writeText(JSONObject().put("socket", socketName).put("token", token).toString())
            Os.chmod(temporary.path, 384)
            check(temporary.renameTo(file))
            session = token; server = socket
            state.value = DeviceBridgeState(enabled = true)
            listener = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    clients.add(client)
                    // Serial handling bounds memory and prevents competing screen/control actions.
                    try { handle(context, client, token) } finally { clients.remove(client); runCatching { client.close() } }
                }
            }
        } catch (failure: Exception) { socket.close(); configuration(context).delete(); throw failure }
    }

    @Synchronized fun stop(context: Context) {
        session = null; state.value = DeviceBridgeState()
        configuration(context).delete()
        runCatching { server?.close() }; server = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        listener?.cancel(); listener = null
    }

    fun permissions(screen: Boolean = state.value.screen, control: Boolean = state.value.control) {
        state.update { if (it.enabled) it.copy(screen = screen, control = control) else it }
    }

    private suspend fun handle(context: Context, socket: LocalSocket, token: String) {
        socket.soTimeout = 5000
        if (socket.peerCredentials.uid != android.os.Process.myUid()) return
        val response = try {
            val input = DataInputStream(socket.inputStream)
            val length = input.readInt()
            require(length in 2..16384) { "Invalid request size" }
            val bytes = ByteArray(length); input.readFully(bytes)
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            require(MessageDigest.isEqual(token.toByteArray(), request.optString("token").toByteArray())) { "Access denied" }
            gate.withLock {
                check(session == token && state.value.enabled) { "Access disabled" }
                val operation = request.getString("operation")
                val result = execute(context, operation, request.optJSONObject("arguments") ?: JSONObject())
                check(session == token) { "Session revoked" }
                state.update { it.copy(requests = it.requests + 1, lastOperation = operation) }
                JSONObject().put("ok", true).put("result", result)
            }
        } catch (_: CancellationException) { return }
        catch (failure: Exception) {
            JSONObject().put("ok", false).put("error", when (failure) {
                is IllegalArgumentException, is IllegalStateException -> failure.message?.take(180) ?: "Request refused"
                else -> "Device operation unavailable; check permissions and the active screen"
            })
        }
        val output = response.toString().toByteArray()
        if (session != token || output.size > 700000) return
        runCatching { DataOutputStream(socket.outputStream).apply { writeInt(output.size); write(output); flush() } }
    }

    private suspend fun execute(context: Context, operation: String, args: JSONObject): JSONObject {
        @Suppress("DEPRECATION")
        val display = android.util.DisplayMetrics().also { context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        when (operation) {
            "info" -> return JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("screenWidth", display.widthPixels).put("screenHeight", display.heightPixels)
                .put("locked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                .put("sharedDirectory", "/android-share")
            "app-status" -> return JSONObject().put("version", BuildConfig.VERSION_NAME)
                .put("services", JSONArray(RuntimeStore.state.value.services.values.map {
                    JSONObject().put("name", it.id.name).put("state", it.process.name).put("desired", it.desiredRunning)
                })).put("desktop", DesktopStore.state.value.running).put("harness", HarnessStore.state.value.ready)
                .put("terminalSessions", TerminalStore.state.value.runningCount).put("ssh", SshStore.state.value.running)
                .put("screenAllowed", state.value.screen).put("controlAllowed", state.value.control)
        }
        require(operation in setOf("tree", "screenshot", "tap", "swipe", "input", "key")) { "Unsupported operation" }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked) { "Unlock the phone before screen access" }
        if (operation in setOf("tree", "screenshot")) check(state.value.screen) { "Screen access disabled" }
        else check(state.value.control) { "Device control disabled" }
        fun coordinate(name: String, limit: Int) { require(args.getInt(name) in 0 until limit) { "Invalid $name" } }
        if (operation == "tap") { coordinate("x", display.widthPixels); coordinate("y", display.heightPixels) }
        if (operation == "swipe") {
            coordinate("x1", display.widthPixels); coordinate("x2", display.widthPixels)
            coordinate("y1", display.heightPixels); coordinate("y2", display.heightPixels)
            require(args.getInt("duration") in 50..2000) { "Invalid duration" }
        }
        if (operation == "input") require(args.getString("text").length in 1..2000) { "Invalid text length" }
        if (operation == "key") require(args.getString("key") in DeviceCommands.keys) { "Unsupported key" }
        val accessibility = DeviceAccessibility.instance
        if (accessibility != null && (operation in setOf("tree", "input", "tap", "swipe") ||
            operation == "key" && args.optString("key") in setOf("back", "home", "recents"))) {
            return withTimeout(12000) { accessibility!!.perform(operation, args) }.put("backend", "accessibility")
        }
        check(DebugAccess.status(context) == DebugAccess.Status.READY) { "Authorize and start Shizuku first" }
        return DebugAccess.deviceOperation(context, operation, args) {
            state.value.enabled && (if (operation in setOf("tree", "screenshot")) state.value.screen else state.value.control) &&
                !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        }.put("backend", "shizuku")
    }

    @Synchronized fun prepareGuest(context: Context) {
        val directory = File(context.filesDir, "manager/bin").apply { mkdirs() }
        val target = File(directory, "android-device")
        val bytes = context.assets.open("runtime/device/android-device.py").use { it.readBytes() }
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
            val temporary = File(directory, "android-device.new")
            temporary.writeBytes(bytes); Os.chmod(temporary.path, 448)
            check(temporary.renameTo(target))
        }
    }
}
