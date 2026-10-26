package io.astrbot.android.runtime

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SAF transport and prepared imports; live data changes only through BackupRepository's journal. */
class PortableBackupRepository(private val context: Context) {
    private val files = context.filesDir
    private val imports = File(files, "staging/portable-import")
    private val export = File(files, "staging/portable-export")
    private val manifest = File(imports, "verified.json")
    private val data = File(imports, "data")
    private val archive = PortableBackupArchive()
    val hasInterruptedWork: Boolean get() = export.exists() || (imports.exists() && !DurableFiles.exists(manifest))
    val preparedInfo: PortableBackupInfo? get() = runCatching {
        DurableFiles.read(manifest)?.takeIf { data.isDirectory }?.let(archive::info)
    }.getOrNull()

    suspend fun prepareImport(uri: Uri, versions: RuntimeVersions, progress: (String) -> Unit): PortableBackupInfo = withContext(Dispatchers.IO) {
        discardImport()
        check(imports.mkdirs()) { "无法创建便携备份暂存目录" }
        val inputFile = File(imports, "source.zip")
        try {
            progress("正在读取所选便携备份")
            require(imports.usableSpace >= RESERVE) { "便携备份暂存空间不足" }
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选便携备份" }.use { input ->
                FileOutputStream(inputFile).use { output -> transfer(input, output, imports); output.fd.sync() }
            }
            progress("正在校验便携备份范围、版本与文件摘要")
            archive.read(inputFile, data, versions).also { inputFile.delete() }
        } catch (failure: Throwable) { discardImport(); throw failure }
    }

    suspend fun verifyPrepared(versions: RuntimeVersions): PortableBackupInfo = withContext(Dispatchers.IO) {
        archive.verify(data, requireNotNull(DurableFiles.read(manifest)) { "请先导入便携备份" }, versions, exclusive = true)
    }

    suspend fun beginRestore(beforeSnapshot: BackupInfo, versions: RuntimeVersions) {
        val metadata = withContext(Dispatchers.IO) { requireNotNull(DurableFiles.read(manifest)) { "便携备份清单缺失" } }
        BackupRepository(files).beginPortableRestore(beforeSnapshot.id, versions, data) { candidate ->
            archive.verify(candidate, metadata, versions, exclusive = candidate == data)
        }
    }

    suspend fun prepareExport(versions: RuntimeVersions, progress: (String) -> Unit): PortableBackupInfo = withContext(Dispatchers.IO) {
        discardExport()
        check(export.mkdirs()) { "无法创建便携备份导出目录" }
        progress("正在制作不含 QQ 登录会话的便携备份")
        try { archive.write(File(files, "persistent"), File(export, "backup.zip"), versions) }
        catch (failure: Throwable) { discardExport(); throw failure }
    }

    suspend fun deliverExport(uri: Uri, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val file = File(export, "backup.zip")
        require(file.isFile && file.length() in 1..PortableBackupArchive.MAX_ARCHIVE) { "便携备份导出文件缺失" }
        progress("服务已恢复原状态，正在写入所选备份文件")
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入所选备份文件" }.use { output ->
            file.inputStream().use { input ->
                check(transfer(input, output, null) == file.length()) { "便携备份写入不完整，请重新导出" }
            }
            output.flush()
        }
    }

    fun discardImport() = DurableFiles.delete(imports)
    fun discardExport() = DurableFiles.delete(export)
    fun cleanupInterruptedWork() {
        discardExport()
        if (!DurableFiles.exists(manifest)) discardImport()
    }

    private suspend fun transfer(input: InputStream, output: OutputStream, local: File?): Long {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= PortableBackupArchive.MAX_ARCHIVE) { "便携备份文件超过 2 GiB 上限" }
            require(local == null || local.usableSpace >= RESERVE) { "便携备份暂存空间不足" }
            output.write(buffer, 0, count)
        }
        return size
    }

    companion object { private const val RESERVE = 64L * 1024 * 1024 }
}
