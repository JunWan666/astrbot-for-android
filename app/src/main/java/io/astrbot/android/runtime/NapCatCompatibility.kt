package io.astrbot.android.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Compatibility for the pinned NapCat status contract; no fabricated /proc data or utilization. */
internal class NapCatCompatibility(private val context: Context) {
    fun prepare() {
        val root = File(context.filesDir, "runtime-data/ubuntu/opt/qq/resources/app/app_launcher/napcat")
        runCatching {
            val server = File(root, "napcat.mjs")
            val page = File(root, "static/assets/index-B2mk4QMJ.js")
            val originalServer = read(server); val originalPage = read(page)
            val patchedServer = backend(originalServer); val patchedPage = frontend(originalPage)
            update(page, originalPage, patchedPage)
            update(server, originalServer, patchedServer)
        }.onFailure { RuntimeStore.log(context, LogCategory.MANAGER).append("NapCat 资源显示兼容未能完整应用；可在管理器首页查看托管资源信息") }
    }

    private fun read(file: File): String {
        require(file.isFile && file.length() < 20L * 1024 * 1024)
        return AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    }

    private fun update(file: File, original: String, result: String) {
        if (result == original) return
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(result.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    companion object {
        private const val MARKER = "/* astrbot-android-cpu-v1 */"
        private const val MEMORY_MARKER = "/* astrbot-android-memory-v1 */"
        private val memoryChanges = listOf(
            "memory: ((e.heapTotal + e.external + e.arrayBuffers) / 1024 / 1024).toFixed(2)" to
                "$MEMORY_MARKER memory: Number.isFinite(e.rss) && e.rss >= 0 ? (e.rss / 1024 / 1024).toFixed(2) : \"不可读取\""
        )
        private val changes = listOf(
                "usage: this.replaceNaN(r / n * 100).toFixed(2)," to "$MARKER usage: n > 0 && Number.isFinite(r / n) ? Math.max(0, Math.min(100, r / n * 100)).toFixed(2) : \"不可读取\",",
                "model: at.cpus()[0]?.model ?? \"none\"," to "model: at.cpus()[0]?.model ?? \"Android 受限\",",
                "core: at.cpus().length" to "core: at.cpus().length || (typeof at.availableParallelism === 'function' ? at.availableParallelism() : 0)",
                "const e = process.memoryUsage(), n = at.cpus().length, r = process.cpuUsage(this.psCpuUsage), i = process.hrtime(this.psCurrentTime);" to
                    "const e = process.memoryUsage(), n = at.cpus().length || (typeof at.availableParallelism === 'function' ? at.availableParallelism() : 0), r = process.cpuUsage(this.psCpuUsage), i = process.hrtime(this.psCurrentTime);",
                "cpu: this.replaceNaN(a).toFixed(2)," to "cpu: n > 0 && s > 0 && Number.isFinite(a) ? Math.max(0, Math.min(100, a)).toFixed(2) : \"不可读取\","
            )
        internal fun backend(source: String): String {
            val result = applyChanges(source, MARKER, changes)
            return applyChanges(result, MEMORY_MARKER, memoryChanges)
        }

        private fun applyChanges(source: String, marker: String, changes: List<Pair<String, String>>): String {
            if (source.contains(marker)) return source
            var result = source
            changes.forEach { (old, new) ->
                require(result.indexOf(old) >= 0 && result.indexOf(old) == result.lastIndexOf(old))
                result = result.replace(old, new)
            }
            return result
        }

        /** A display fix does not change the data/backup compatibility of this runtime. */
        internal fun originalBackend(source: String): String =
            (memoryChanges + changes).asReversed().fold(source) { text, (original, patched) -> text.replace(patched, original) }

        internal fun frontend(source: String): String {
            return memoryFrontend(cpuFrontend(source))
        }

        private fun cpuFrontend(source: String): String {
            if (source.contains(MARKER)) return source
            val ring = "e.jsx(se,{systemUsage:Number(s==null?void 0:s.cpu.usage.system)||0,processUsage:Number(s==null?void 0:s.cpu.usage.qq)||0,title:\"CPU占用\",hasBackground:t})"
            require(source.indexOf(ring) >= 0 && source.indexOf(ring) == source.lastIndexOf(ring))
            val replacement = "$MARKER (Number.isFinite(parseFloat(s?.cpu?.usage?.system))&&Number.isFinite(parseFloat(s?.cpu?.usage?.qq))?$ring:e.jsxs(\"div\",{className:\"text-xs text-center p-3 rounded-xl bg-default-100\",children:[e.jsx(\"div\",{children:\"系统 CPU 暂不可读取\"}),e.jsx(\"div\",{children:\"Android 限制了系统统计接口\"}),e.jsx(\"div\",{children:\"QQ主线程：\"+(s?.cpu?.usage?.qq??\"不可读取\")+(Number.isFinite(parseFloat(s?.cpu?.usage?.qq))?\"%\":\"\")})]}))"
            return source.replace(ring, replacement)
                .replace("value:s==null?void 0:s.cpu.usage.system,unit:\"%\"", "value:s==null?void 0:s.cpu.usage.system,unit:Number.isFinite(parseFloat(s?.cpu?.usage?.system))?\"%\":\"\"")
        }

        private fun memoryFrontend(source: String): String {
            if (source.contains(MEMORY_MARKER)) return source
            val start = source.indexOf("rs=({data:s})=>")
            val end = source.indexOf(",as=", start)
            require(start >= 0 && end > start)
            val original = source.substring(start, end)
            val changes = listOf(
                "children:\"内存\"" to "children:\"手机内存（含缓存）\"",
                "title:\"总量\",value:s==null?void 0:s.memory.total" to "title:\"手机总量\",value:s==null?void 0:s.memory.total",
                "title:\"使用量\",value:s==null?void 0:s.memory.usage.system" to "title:\"系统已用\",value:s==null?void 0:s.memory.usage.system",
                "title:\"QQ主线程\",value:s==null?void 0:s.memory.usage.qq" to "title:\"QQ主进程 RSS\",value:s==null?void 0:s.memory.usage.qq",
                "unit:\"MB\"" to "unit:\"MiB\"",
                "title:\"内存占用\"" to "title:\"手机内存\"",
                "value:s==null?void 0:s.memory.usage.qq,unit:\"MiB\",hasBackground:t})]})" to
                    "value:s==null?void 0:s.memory.usage.qq,unit:\"MiB\",hasBackground:t})]}),$MEMORY_MARKER e.jsx(\"p\",{className:\"text-xs text-default-500 mt-2 leading-relaxed\",children:\"系统含其他应用和缓存；QQ 仅主进程 RSS。完整托管占用见管理器首页。\"})"
            )
            var patched = original
            changes.forEach { (old, new) ->
                require(patched.contains(old))
                patched = patched.replace(old, new)
            }
            return source.substring(0, start) + patched + source.substring(end)
        }
    }
}
