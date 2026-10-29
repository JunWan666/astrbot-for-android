package io.astrbot.android.debug

import android.os.Build
import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shizuku UserService. Only fixed system-setting operations are exposed over Binder. */
@androidx.annotation.Keep
class ProcessLimitService : IProcessLimitService.Stub() {
    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val output = reader.submit<String> {
                process.inputStream.bufferedReader().use { stream ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(result.length + count <= 4 * 1024 * 1024) { "System response is too large" }
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
            }
            check(process.waitFor(8, TimeUnit.SECONDS)) { "System command timed out" }
            val text = output.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "System command was refused (${process.exitValue()}): ${text.take(240)}" }
            return text.trim()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            reader.shutdownNow()
        }
    }

    private fun probe() = ProcessLimitProbe(android.os.Process.myUid(), android.os.Binder.getCallingUid()) { command(*it) }
    private fun background() = BackgroundExecutionProbe(BuildConfig.APPLICATION_ID) { command(*it) }
    @Synchronized override fun inspect(): String = (if (Build.VERSION.SDK_INT >= 31) probe().inspect()
        else JSONObject().put("uid", android.os.Process.myUid()).put("sdk", Build.VERSION.SDK_INT))
        .put("background", background().inspect()).put("transport", "user-service").toString()
    @Synchronized override fun setLimit(restoreDefault: Boolean): String = probe().setLimit(restoreDefault)
        .put("background", background().inspect()).put("transport", "user-service").toString()
    @Synchronized override fun setBatteryExemption(enabled: Boolean): String = background().setExemption(enabled).put("transport", "user-service").toString()

    @Synchronized override fun deviceOperation(operation: String, arguments: String): String =
        io.astrbot.android.device.DeviceCommands.run(operation, JSONObject(arguments)) { args ->
            val process = ProcessBuilder(*args).start()
            val readers = Executors.newFixedThreadPool(2)
            try {
                val output = readers.submit<ByteArray> { io.astrbot.android.device.DeviceCommands.readOutput(process.inputStream) }
                val error = readers.submit { io.astrbot.android.device.DeviceCommands.discard(process.errorStream) }
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Device operation timed out" }
                val bytes = output.get(2, TimeUnit.SECONDS); error.get(2, TimeUnit.SECONDS)
                check(process.exitValue() == 0 && bytes.size <= 8 * 1024 * 1024) { "Device operation refused" }
                bytes
            } finally { if (process.isAlive) process.destroyForcibly(); readers.shutdownNow() }
        }.toString()

    override fun destroy() { System.exit(0) }
}
