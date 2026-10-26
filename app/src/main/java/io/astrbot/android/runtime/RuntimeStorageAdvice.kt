package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import org.json.JSONObject
import java.io.File

/** A conservative preparation estimate, never a reason to reject a user's start request. */
data class RuntimeStorageAdvice(val availableBytes: Long, val recommendedBytes: Long) {
    val shouldWarn: Boolean get() = availableBytes < recommendedBytes
    val message: String get() = AppText.get(R.string.runtime_storage_warning_message,
        availableBytes / (1024 * 1024), recommendedBytes / (1024 * 1024))

    companion object {
        fun fromManifest(manifest: JSONObject, availableBytes: Long): RuntimeStorageAdvice? {
            val unpacked = manifest.optLong("unpackedBytes", 0)
            if (unpacked !in 1..(12L * 1024 * 1024 * 1024)) return null
            return RuntimeStorageAdvice(availableBytes.coerceAtLeast(0), unpacked * 2 + 64L * 1024 * 1024)
        }

        fun beforeOperation(context: Context, operation: String): RuntimeStorageAdvice? = runCatching {
            val runtime = BootstrapRuntime(context)
            val preparesRuntime = operation in setOf("apply-runtime", "repair-runtime") ||
                operation in setOf("initialize", "start", "restart") &&
                !File(runtime.rootfs, ".astrbot-bootstrap.json").isFile
            if (preparesRuntime) fromManifest(runtime.bundledManifest(), context.filesDir.usableSpace) else null
        }.getOrNull()
    }
}
