package io.astrbot.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Public build-time assets only. Viewing or exporting never starts the runtime. */
class ThirdPartyNoticesActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { NoticesScreen() } } }
    }

    @Composable private fun NoticesScreen() {
        var names by remember { mutableStateOf<List<String>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<String?>(null) }
        var text by remember { mutableStateOf<String?>(null) }
        var exporting by remember { mutableStateOf(false) }
        var page by remember { mutableIntStateOf(0) }
        val scroll = rememberScrollState()
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) lifecycleScope.launch {
                exporting = true
                val success = withContext(Dispatchers.IO) { runCatching {
                    contentResolver.openOutputStream(uri, "w").use { output ->
                        requireNotNull(output)
                        assets.open("third-party/notices.zip").use { it.copyTo(output) }
                    }
                }.isSuccess }
                exporting = false
                Toast.makeText(this@ThirdPartyNoticesActivity, if (success) AppText.get(R.string.ui_license_materials_saved) else AppText.get(R.string.ui_unable_to_save_please_retry), Toast.LENGTH_LONG).show()
            }
        }
        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) { runCatching {
                assets.open("third-party/manifest.json").bufferedReader().use { reader ->
                    val array = JSONObject(reader.readText()).getJSONArray("names")
                    List(array.length()) { array.getString(it) }
                }
            } }
            names = result.getOrNull()
            if (result.isFailure) error = AppText.get(R.string.ui_license_materials_are_unavailable_check_the_project_releases_or)
        }
        LaunchedEffect(selected) {
            text = null
            page = 0
            selected?.let { name ->
                text = withContext(Dispatchers.IO) { runCatching { readNotice(name) }.getOrElse { AppText.get(R.string.ui_unable_to_read_this_file_export_the_license_materials) } }
            }
        }
        LaunchedEffect(page, selected) { scroll.scrollTo(0) }
        BackHandler(enabled = selected != null) { selected = null }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back_to_manager), Modifier.size(21.dp)) }
                Text(AppText.get(R.string.ui_third_party_licenses), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { export.launch("astrbot-third-party-notices.zip") }, enabled = names != null && !exporting) { Text(AppText.get(R.string.ui_export_license_materials)) }
            }
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selected == null) {
                Text(AppText.get(R.string.ui_license_texts_and_source_records_bundled_with_this_apk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it) }
                if (names == null && error == null) CircularProgressIndicator()
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(AppText.get(R.string.ui_filter_filenames)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = MaterialTheme.shapes.medium, textStyle = MaterialTheme.typography.bodyMedium)
                val filtered = names.orEmpty().filter { it.contains(query, ignoreCase = true) }
                Text(AppText.get(R.string.ui_files, filtered.size), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it }) { name ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            ManagerSettingsRow(name, R.drawable.ic_document, divider = false) { selected = name }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = null }) { Text(AppText.get(R.string.ui_back_to_file_list)) }
                    Text(selected!!, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val content = text
                if (content == null) CircularProgressIndicator()
                else {
                    val pages = maxOf(1, (content.length + PAGE_SIZE - 1) / PAGE_SIZE)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page-- }, enabled = page > 0) { Text(AppText.get(R.string.ui_previous)) }
                        Text(AppText.get(R.string.ui_page_a43ad, page + 1, pages), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { page++ }, enabled = page + 1 < pages) { Text(AppText.get(R.string.ui_next)) }
                    }
                    Surface(Modifier.weight(1f).padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        SelectionContainer(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp)) {
                            Text(content.substring(page * PAGE_SIZE, minOf(content.length, (page + 1) * PAGE_SIZE)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    private suspend fun readNotice(name: String): String {
        ZipInputStream(assets.open("third-party/notices.zip")).use { archive ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = archive.nextEntry ?: break
                if (entry.name == name) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = archive.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_NOTICE_BYTES)
                        output.write(buffer, 0, count)
                    }
                    return output.toString(Charsets.UTF_8.name())
                }
            }
        }
        error("Notice unavailable")
    }

    companion object {
        private const val PAGE_SIZE = 12_000
        private const val MAX_NOTICE_BYTES = 16 * 1024 * 1024
    }
}
