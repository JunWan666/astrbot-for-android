package io.astrbot.android.debug

import android.os.Build
import io.astrbot.android.BuildConfig
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** API 12/13 fallback when a ROM blocks launching the manager's UserService provider.
 * This legacy SDK transport is optional and may disappear in API 14; UserService stays primary.
 * Only the fixed settings probes supply commands. No arbitrary shell or guest commands are accepted.
 */
internal object ShizukuCompatibility {
    private fun background() = BackgroundExecutionProbe(BuildConfig.APPLICATION_ID, ::command)
    fun inspect(restoreDefault: Boolean?) = ProcessLimitProbe(Shizuku.getUid(), android.os.Process.myUid(), ::command).let { probe ->
        if (restoreDefault != null) probe.setLimit(restoreDefault)
        else if (Build.VERSION.SDK_INT >= 31) probe.inspect()
        else JSONObject().put("uid", Shizuku.getUid()).put("sdk", Build.VERSION.SDK_INT)
    }.put("background", background().inspect()).put("transport", "compatibility")

    fun setBatteryExemption(enabled: Boolean) = background().setExemption(enabled).put("transport", "compatibility")

    fun deviceOperation(operation: String, args: JSONObject): JSONObject = io.astrbot.android.device.DeviceCommands.run(operation, args) { arguments ->
        check(Shizuku.getVersion() in 12..13) { "Compatibility transport unavailable" }
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java).apply { isAccessible = true }
        val process = method.invoke(null, arguments, null, null) as ShizukuRemoteProcess
        val readers = Executors.newFixedThreadPool(2)
        try {
            process.outputStream.close()
            val output = readers.submit<ByteArray> { io.astrbot.android.device.DeviceCommands.readOutput(process.inputStream) }
            val error = readers.submit { io.astrbot.android.device.DeviceCommands.discard(process.errorStream) }
            check(process.waitForTimeout(15, TimeUnit.SECONDS)) { "Device operation timed out" }
            val bytes = output.get(2, TimeUnit.SECONDS); error.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0 && bytes.size <= 8 * 1024 * 1024) { "Device operation refused" }
            bytes
        } finally { runCatching { process.destroy() }; readers.shutdownNow() }
    }

    private fun command(arguments: Array<out String>): String {
        check(Shizuku.getVersion() in 12..13) { "Compatibility transport unavailable" }
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
        val process = method.invoke(null, arguments, null, null) as ShizukuRemoteProcess
        val readers = Executors.newFixedThreadPool(2)
        fun read(input: java.io.InputStream) = readers.submit<String> {
            input.bufferedReader().use { stream ->
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
        try {
            process.outputStream.close()
            val output = read(process.inputStream)
            val error = read(process.errorStream)
            check(process.waitForTimeout(8, TimeUnit.SECONDS)) { "System command timed out" }
            val text = output.get(2, TimeUnit.SECONDS)
            val detail = error.get(2, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { "System command was refused (${process.exitValue()}): ${(text + detail).take(240)}" }
            return text.trim()
        } finally {
            runCatching { process.destroy() }
            readers.shutdownNow()
        }
    }
}
