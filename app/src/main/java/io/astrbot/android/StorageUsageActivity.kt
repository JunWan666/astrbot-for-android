package io.astrbot.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*

/** On-demand, read-only usage. A closed or background page cancels directory enumeration. */
class StorageUsageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme {
            var report by remember { mutableStateOf<StorageUsageReport?>(null) }
            var busy by remember { mutableStateOf(false) }
            var phase by remember { mutableStateOf("正在读取存储信息") }
            var refresh by remember { mutableIntStateOf(0) }
            var detail by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
            LaunchedEffect(refresh) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    busy = true
                    try {
                        report = StorageUsageRepository(this@StorageUsageActivity).scan { phase = it }
                        phase = "统计已完成；运行中的文件可能继续变化"
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { phase = "本次统计未完成，请刷新重试；现有数据已保留" }
                    finally { busy = false }
                }
            }
            BackHandler { finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { finish() }) { Text("返回") }
                        Text("存储使用", Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { refresh++ }, enabled = !busy) { Text("刷新统计") }
                    }
                    Text(phase, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val current = report
                    val rows = mutableListOf<ManagerMenuItem>()
                    fun bytes(value: Long) = Formatter.formatShortFileSize(this@StorageUsageActivity, value)
                    fun info(title: String, subtitle: String, text: String) {
                        rows += ManagerMenuItem(title, R.drawable.ic_info, subtitle) { detail = title to text }
                    }
                    current?.let { result ->
                        val available = result.space.availableBytes?.let(::bytes) ?: "暂不可读取"
                        info("手机内部可用", available + " · " + result.space.pressure.title,
                            "当前可用 $available\n\n${StorageSpace.HELP}")
                        result.app?.let { app ->
                            info("应用总占用", bytes(Math.addExact(app.app, app.data)),
                                "系统记账：\n安装代码 ${bytes(app.app)}\n应用数据 ${bytes(app.data)}\n其中缓存 ${bytes(app.cache)}\n\n" +
                                    "缓存已包含在应用数据中，不再重复相加。系统统计可能延迟，与下方各目录文件的逻辑大小口径不同。")
                        } ?: info("应用总占用", "系统暂未提供", "系统记账暂不可读取。下方目录仍可单独统计；不能把部分目录相加当作完整应用大小。")
                        result.groups.forEach { group ->
                            val value = (if (group.complete) "" else "至少 ") + bytes(group.bytes)
                            info(group.title, value + if (group.complete) "" else " · 部分统计",
                                "$value\n${group.files} 个普通文件\n\n${group.scope}\n\n" +
                                    "这是文件逻辑大小，每组内相同 inode 只计算一次；不跟随链接、不计算特殊文件和目录自身占用。" +
                                    "已跳过 ${group.skippedLinks} 个链接、${group.skippedSpecial} 个特殊文件。" +
                                    (if (!group.complete) "\n\n部分路径不可读、文件变化或达到扫描预算，当前值只表示已读取部分，请稍后刷新。" else "") +
                                    "\n\n统计不会暂停服务，也不提供删除此目录的操作。")
                        }
                    }
                    rows += ManagerMenuItem("统计与清理说明", R.drawable.ic_document, "两种统计口径及处理建议") {
                        detail = "统计与清理说明" to (StorageSpace.HELP + "\n\n" +
                            "系统总量涵盖当前应用的安装代码、数据和缓存；目录统计只覆盖列出的普通文件，不应与系统总量相加。" +
                            "每次目录扫描最多 10 万项、8 秒，超限或不可读时显示“部分统计”。" +
                            "统计期间不锁住服务维护，文件会变化，这些读数并非同一时刻的快照。")
                    }
                    rows += ManagerMenuItem("系统存储设置", R.drawable.ic_settings, "查看整台手机的空间使用") {
                        runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
                    }
                    FixedManagerMenu(rows, Modifier.weight(1f))
                }
            }
            detail?.let { (title, text) -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(title) },
                text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
        } }
    }
}
