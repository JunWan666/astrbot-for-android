package io.astrbot.android.runtime

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

enum class StoragePressure(val title: String) {
    UNKNOWN("存储待检查"), NORMAL("存储空间正常"), LOW("存储空间偏低"), CRITICAL("存储空间严重不足");
    companion object {
        private const val MIB = 1024L * 1024
        fun from(bytes: Long?): StoragePressure = when {
            bytes == null || bytes < 0 -> UNKNOWN
            bytes < 128 * MIB -> CRITICAL
            bytes < 512 * MIB -> LOW
            else -> NORMAL
        }
    }
}

data class StorageSpaceReading(val availableBytes: Long? = null, val checkedAtMillis: Long? = null) {
    val pressure get() = StoragePressure.from(availableBytes)
    val warning get() = when (pressure) {
        StoragePressure.LOW, StoragePressure.CRITICAL -> "${pressure.title}，请在设置的“日志与诊断 → 存储使用”查看占用"
        else -> null
    }
}

internal object StorageSpace {
    fun read(context: Context) = StorageSpaceReading(
        runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(), System.currentTimeMillis())
    const val HELP = "手机内部存储可用空间低于 512 MiB 时提醒，低于 128 MiB 时标记严重不足。" +
        "请先释放手机其他文件，或在备份管理中核对后删除不再需要的备份；已下载的管理器 APK 可在检查更新页清理。" +
        "不要使用系统“清除数据”：它会删除运行环境、配置和账号资料。" +
        "提醒不会自动停止服务或删除 QQ 缓存、会话、数据库及未知文件。更新和备份继续执行各自的准确空间检查。"
}

/** Cheap volume checks have their own timer; a long maintenance lock cannot delay warnings. */
internal class StorageSpaceMonitor(private val read: () -> StorageSpaceReading,
    private val intervalMillis: Long = 60_000) {
    init { require(intervalMillis > 0) }
    suspend fun watch(publish: (StorageSpaceReading) -> Unit, event: (String) -> Unit) {
        var lastKnown = StoragePressure.UNKNOWN
        fun record(message: String) {
            try { event(message) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { /* Full storage must not stop in-memory warnings. */ }
        }
        while (currentCoroutineContext().isActive) {
            val space = read()
            publish(space)
            if (space.pressure != StoragePressure.UNKNOWN && space.pressure != lastKnown) {
                space.warning?.let(::record)
                if (space.pressure == StoragePressure.NORMAL && lastKnown in setOf(StoragePressure.LOW, StoragePressure.CRITICAL))
                    record("存储可用空间已恢复到提醒阈值以上")
                lastKnown = space.pressure
            }
            delay(intervalMillis)
        }
    }
}

internal data class AppStorageBytes(val app: Long, val data: Long, val cache: Long)
internal data class StorageGroup(val id: String, val title: String, val scope: String,
    val bytes: Long, val files: Int, val complete: Boolean, val skippedLinks: Int, val skippedSpecial: Int)
internal data class StorageUsageReport(val space: StorageSpaceReading, val app: AppStorageBytes?,
    val groups: List<StorageGroup>, val checkedAtMillis: Long, val elapsedMillis: Long)

internal class StorageFileTally {
    private val seen = mutableSetOf<Pair<Long, Long>>()
    var bytes = 0L; private set
    var files = 0; private set
    fun record(device: Long, inode: Long, size: Long): Boolean {
        require(size >= 0)
        if (!seen.add(device to inode)) return false
        bytes = Math.addExact(bytes, size); files++
        return true
    }
}

/** Metadata only. Never opens file contents, changes permissions, recovers journals or removes files. */
internal class StorageUsageRepository(private val context: Context,
    private val maxEntries: Int = 100_000, private val maxDurationMillis: Long = 8_000,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    init { require(maxEntries > 0 && maxDurationMillis > 0) }

    fun appStorage(): AppStorageBytes? = runCatching {
        val manager = context.getSystemService(StorageStatsManager::class.java)
        val storage = context.getSystemService(StorageManager::class.java)
        val volume = storage.getUuidForPath(context.filesDir)
        manager.queryStatsForPackage(volume, context.packageName, Process.myUserHandle()).let {
            require(it.appBytes >= 0 && it.dataBytes >= 0 && it.cacheBytes in 0..it.dataBytes)
            Math.addExact(it.appBytes, it.dataBytes)
            AppStorageBytes(it.appBytes, it.dataBytes, it.cacheBytes)
        }
    }.getOrNull()

    suspend fun scan(progress: (String) -> Unit = {}): StorageUsageReport = withContext(Dispatchers.IO) {
        val started = elapsed()
        val coroutine = currentCoroutineContext()
        var visited = 0
        data class Category(val id: String, val title: String, val scope: String, val roots: List<File>)
        fun category(id: String, title: String, scope: String, vararg paths: String) =
            Category(id, title, scope, paths.map { File(context.filesDir, it) })
        val categories = listOf(
            category("astrbot", "AstrBot 数据", "配置、插件、数据库、追踪数据及上游日志", "persistent/astrbot"),
            category("napcat", "NapCat 数据", "后台配置及上游日志", "persistent/napcat"),
            category("qq-home", "QQ 与用户目录", "QQ 会话、缓存、附件及 Ubuntu 用户目录", "persistent/home", "persistent/qq"),
            category("backups", "本机备份", "手动备份和自动快照；受保护快照也计入", "backups"),
            category("staging", "更新与导入暂存", "下载的 APK、程序及待导入备份", "staging"),
            category("logs", "管理输出日志", "管理器、环境和两个项目的轮转输出", "logs"),
            Category("cache", "应用缓存", "导出及浏览器等应用私有缓存", listOf(context.cacheDir)),
            // Enumerate the large guest last so its budget cannot hide cache, logs or downloads.
            category("runtime", "运行资源与回退", "当前及回退运行环境、程序资源", "runtime-data", "releases"),
        )
        val groups = categories.map { category ->
            coroutine.ensureActive()
            progress("正在统计${category.title}")
            val tally = StorageFileTally()
            var complete = true
            var links = 0
            var special = 0
            fun withinBudget(): Boolean {
                coroutine.ensureActive()
                return visited < maxEntries && elapsed() - started < maxDurationMillis
            }
            for (root in category.roots) {
                if (!withinBudget()) { complete = false; break }
                try {
                    // Reject linked ancestors as well as linked leaves; Files.walkFileTree does not
                    // by itself prevent following a symlink in a starting path's parent.
                    val boundary = if (root == context.cacheDir) context.cacheDir.parentFile!! else context.filesDir
                    var current: File? = root
                    var missing = false
                    while (current != null && current.toPath().startsWith(boundary.toPath())) {
                        val info = try { Os.lstat(current.path) } catch (failure: ErrnoException) {
                            if (failure.errno == OsConstants.ENOENT) { missing = true; break } else throw failure
                        }
                        if (OsConstants.S_ISLNK(info.st_mode)) { links++; missing = true; break }
                        if (current == boundary) break
                        current = current.parentFile
                    }
                    if (missing) continue
                    Files.walkFileTree(root.toPath(), setOf(), 64, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!withinBudget()) { complete = false; return FileVisitResult.TERMINATE }
                            visited++
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!withinBudget()) { complete = false; return FileVisitResult.TERMINATE }
                            visited++
                            when {
                                attrs.isSymbolicLink -> links++
                                attrs.isDirectory -> complete = false // depth budget reached
                                attrs.isRegularFile -> {
                                    val stat = Os.lstat(file.toString())
                                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size != attrs.size()) {
                                        complete = false
                                    } else tally.record(stat.st_dev, stat.st_ino, stat.st_size)
                                }
                                else -> special++
                            }
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                            complete = false
                            return if (withinBudget()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                        }
                    })
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { complete = false }
            }
            StorageGroup(category.id, category.title, category.scope, tally.bytes, tally.files, complete, links, special)
        }
        coroutine.ensureActive()
        StorageUsageReport(StorageSpace.read(context), appStorage(), groups, System.currentTimeMillis(), elapsed() - started)
    }
}
