package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

enum class EnvironmentCheckStatus { OK, ATTENTION, FAILED }
data class EnvironmentCheckItem(val title: String, val status: EnvironmentCheckStatus, val detail: String)
data class EnvironmentCheckReport(val checkedAtMillis: Long, val items: List<EnvironmentCheckItem>,
    val canRepair: Boolean, val repairExplanation: String)

/** Explicit local checks; never starts either server, rewrites configuration or recovers journals. */
internal class EnvironmentChecks(private val context: Context,
    private val pythonCheck: suspend (BootstrapRuntime) -> Boolean = { runtime ->
        runtime.execute("/opt/astrbot-venv/bin/python", "-I", "-B", "-c",
            "import quart, fastapi, faiss; print('environment-check-ok')", timeoutSeconds = 45).let {
            it.exitCode == 0 && it.output.lineSequence().any { line -> line.trim() == "environment-check-ok" }
        }
    }) {
    suspend fun check(): EnvironmentCheckReport = withContext(Dispatchers.IO) {
        val runtime = BootstrapRuntime(context)
        val root = runtime.rootfs
        val items = mutableListOf<EnvironmentCheckItem>()
        fun item(title: String, status: EnvironmentCheckStatus, detail: String) { items += EnvironmentCheckItem(title, status, detail) }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val launchFiles = listOf("libprocess-runner.so", "libproot.so", "libproot-loader.so")
        val nativeReady = launchFiles.all { File(native, it).isFile && File(native, it).canExecute() }
        item("APK 启动组件", if (nativeReady) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (nativeReady) "内置启动文件可读取和执行" else "APK 启动组件缺失或不可执行，请覆盖安装同签名 APK，保留应用数据")
        val bundled = readJson { runtime.bundledManifest() }
        val marker = readJson {
            val file = File(root, ".astrbot-bootstrap.json")
            require(file.isFile && file.length() <= 64 * 1024)
            JSONObject(file.readText())
        }
        val installed = marker?.optString("kind") == "complete" && marker.optString("abi") == runtime.abi
        item("环境安装记录", if (installed) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (installed) "已识别完整运行环境" else "未完成完整环境初始化，或安装记录无法识别；已有文件会保留")
        val pending = listOf("restore.json", "update.json", "runtime-update.json", "persona.json", "backup-retention.json").any {
            DurableFiles.exists(File(context.filesDir, "operations/$it"))
        }
        item("维护恢复记录", if (pending) EnvironmentCheckStatus.ATTENTION else EnvironmentCheckStatus.OK,
            if (pending) "存在未完成操作，请返回管理页处理恢复后重新检查；本次检查不更改操作记录" else "未发现待恢复的维护记录")
        val resources = listOf(
            "Ubuntu Shell" to "usr/bin/dash", "Python" to "usr/bin/python3.12",
            "Python 虚拟环境" to "opt/astrbot-venv/pyvenv.cfg", "AstrBot 入口" to "opt/astrbot/main.py",
            "AstrBot 后台" to "opt/astrbot/astrbot/dashboard/dist/index.html", "Linux QQ" to "opt/qq/qq",
            "NapCat" to "opt/qq/resources/app/app_launcher/napcat/napcat.mjs", "Xvfb" to "usr/bin/Xvfb")
        val missing = resources.filterNot { File(root, it.second).let { file -> file.isFile && file.canRead() } }.map { it.first }
        item("运行资源文件", if (missing.isEmpty()) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (missing.isEmpty()) "关键入口文件存在；不等同于完整文件摘要或业务验证" else "缺失或不可读取：${missing.joinToString("、")}")
        val versions = if (installed) runCatching { InstalledVersions.read(root) }.getOrNull() else null
        item("版本与备份标识", if (versions != null) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
            if (versions != null) "可读取当前组件组合和备份兼容标识" else "版本依据不完整，不能安全地创建修复事务；请导出诊断并使用匹配的维护资源")
        var configurationFailed = false
        for (id in ServiceId.entries) {
            val file = File(context.filesDir, if (id == ServiceId.ASTRBOT) "persistent/astrbot/data/cmd_config.json" else "persistent/napcat/config/webui.json")
            val connection = RuntimeConfiguration(context).read(id, recoverPendingWrite = false)
            val status = when {
                !file.exists() && !File(file.path + ".bak").exists() -> EnvironmentCheckStatus.ATTENTION
                connection.endpoint == null && connection.enabled -> EnvironmentCheckStatus.FAILED
                !connection.enabled -> EnvironmentCheckStatus.ATTENTION
                else -> EnvironmentCheckStatus.OK
            }
            configurationFailed = configurationFailed || status == EnvironmentCheckStatus.FAILED
            item("${id.label} 后台配置", status, when {
                !file.exists() && !File(file.path + ".bak").exists() -> "尚未创建，首次启动会准备默认配置"
                status == EnvironmentCheckStatus.FAILED -> "配置格式、监听地址或端口无法识别；修复运行资源不会重置此配置"
                !connection.enabled -> "用户已在项目配置中关闭后台，属于配置状态"
                else -> "本机入口配置可识别；本项不验证登录、模型、QQ 或 OneBot 消息"
            })
        }
        var dependenciesReady = false
        if (installed && nativeReady && !pending && missing.isEmpty()) {
            dependenciesReady = try { pythonCheck(runtime) }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { false }
            item("Python 基础依赖", if (dependenciesReady) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.FAILED,
                if (dependenciesReady) "隔离模式加载 quart、fastapi、faiss 成功，未启动机器人服务"
                else "Python 或基础依赖加载失败，查看环境日志；未修改用户配置")
        } else item("Python 基础依赖", EnvironmentCheckStatus.ATTENTION,
            "需先满足安装、启动文件与维护条件，本次未执行依赖加载")
        val sameImage = installed && bundled?.optString("kind") == "complete" &&
            marker?.optString("sha256") == bundled.optString("sha256")
        val required = bundled?.optLong("unpackedBytes", 0)?.takeIf { it in 1..12L * 1024 * 1024 * 1024 }
            ?.let { it * 2 + 64L * 1024 * 1024 }
        val enoughSpace = required != null && context.filesDir.usableSpace >= required
        item("修复暂存空间", if (enoughSpace) EnvironmentCheckStatus.OK else EnvironmentCheckStatus.ATTENTION,
            required?.let { "可用 ${context.filesDir.usableSpace / 1024 / 1024} MiB；环境准备至少预留 ${it / 1024 / 1024} MiB，数据快照另行精确检查" }
                ?: "当前 APK 的完整资源大小无法识别")
        val damaged = missing.isNotEmpty() || installed && !dependenciesReady
        val reason = when {
            pending -> "先完成未结束的维护操作，再重新检查"
            !nativeReady -> "请覆盖安装同签名 APK，修复内置启动组件；不要卸载清除数据"
            !installed -> "请先完成初始化；无法识别的已有环境不会被自动覆盖"
            versions == null -> "版本依据不完整，先导出诊断；不会用猜测的版本覆盖环境"
            !sameImage -> "当前环境与 APK 内置资源不同，请使用运行环境升级或匹配版本的 APK"
            configurationFailed -> "请先处理后台配置，资源重部署不会修复配置内容"
            !enoughSpace -> "请先释放暂存空间，修复前还会检查数据快照容量"
            !damaged -> "未发现此检查可定位的资源损坏；业务问题请在对应后台处理"
            else -> "可重新部署 APK 内置的同版本资源；准备后还会核对完整版本组合，保留配置、数据库和 QQ 会话"
        }
        EnvironmentCheckReport(System.currentTimeMillis(), items,
            pending.not() && nativeReady && installed && versions != null && sameImage && !configurationFailed && enoughSpace && damaged, reason)
    }

    private fun readJson(read: () -> JSONObject): JSONObject? = runCatching(read).getOrNull()
}
