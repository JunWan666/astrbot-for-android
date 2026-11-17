package io.astrbot.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.astrbot.android.runtime.LogCategory
import io.astrbot.android.runtime.RuntimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ExportFileProvider : FileProvider()

/** Only completed exports in this dedicated cache directory can be shared. */
internal object SharedExports {
    private const val RETENTION_MS = 24 * 60 * 60 * 1000L

    fun destination(context: Context, name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9._-]{1,160}")))
        val root = File(context.cacheDir, "shared-exports").apply { check(mkdirs() || isDirectory) }
        root.listFiles()?.filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > RETENTION_MS }
            ?.forEach { it.deleteRecursively() }
        return File(File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }, name)
    }

    suspend fun log(context: Context, category: LogCategory): File = withContext(Dispatchers.IO) {
        val file = destination(context, "astrbot-${category.name.lowercase()}-${System.currentTimeMillis()}.log")
        try {
            file.bufferedWriter(Charsets.UTF_8).use { RuntimeStore.log(context, category).exportTo(it) }
            file
        } catch (failure: Throwable) { file.parentFile?.deleteRecursively(); throw failure }
    }

    fun intent(context: Context, file: File, mimeType: String): Intent {
        val root = File(context.cacheDir, "shared-exports").canonicalFile
        require(file.isFile && file.canonicalFile.toPath().startsWith(root.toPath()))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launch(context: Context, file: File, mimeType: String) {
        context.startActivity(Intent.createChooser(intent(context, file, mimeType), AppText.get(R.string.export_share)))
    }
}
