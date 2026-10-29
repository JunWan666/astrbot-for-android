package io.astrbot.android

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class WebsiteBookmark(val id: String, val title: String, val url: String)

internal class WebsiteBookmarks(context: Context) {
    private val preferences = context.getSharedPreferences("website-bookmarks", Context.MODE_PRIVATE)
    fun read(): List<WebsiteBookmark> {
        val saved = preferences.getString("items", null)
            ?: return listOf(WebsiteBookmark("deepseek", "DeepSeek", "https://platform.deepseek.com/usage"))
        return runCatching {
            val rows = JSONArray(saved)
            (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                val url = it.getString("url")
                WebsiteBookmark(it.getString("id"), it.getString("title"), normalize(
                    if (it.getString("id") == "deepseek" && url == "https://chat.deepseek.com/")
                        "https://platform.deepseek.com/usage" else url))
            } }
        }.getOrDefault(emptyList())
    }
    fun save(rows: List<WebsiteBookmark>) {
        require(rows.size <= 100)
        val data = JSONArray().apply { rows.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", normalize(it.url))) } }
        check(preferences.edit().putString("items", data.toString()).commit())
    }
    companion object {
        fun normalize(input: String): String {
            val value = input.trim().let { if ("://" in it) it else "https://$it" }
            require(value.length in 1..4096 && value.none { it.isISOControl() })
            val uri = URI(value)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            require(uri.port == -1 || uri.port in 1..65535)
            return uri.toASCIIString()
        }
    }
}

@Composable internal fun WebsiteBookmarksScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { WebsiteBookmarks(context) }
    var rows by remember { mutableStateOf(repository.read()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<WebsiteBookmark?>(null) }
    fun edit(item: WebsiteBookmark?) {
        editingId = item?.id ?: ""; title = item?.title.orEmpty(); url = item?.url.orEmpty(); error = null
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(R.string.websites_intro), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ManagerActionButton(AppText.get(R.string.websites_add), { edit(null) }, enabled = rows.size < 100)
        }
        if (rows.isEmpty()) Text(AppText.get(R.string.websites_empty))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(rows, key = { it.id }) { item ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth().clickable {
                            context.startActivity(Intent(context, WebsiteBrowserActivity::class.java).putExtra("bookmarkId", item.id))
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WebsiteAvatar(item)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManagerActionButton(AppText.get(R.string.websites_open), {
                                context.startActivity(Intent(context, WebsiteBrowserActivity::class.java).putExtra("bookmarkId", item.id))
                            }, Modifier.weight(1.4f))
                            ManagerActionButton(AppText.get(R.string.websites_edit), { edit(item) }, Modifier.weight(.8f), ActionTone.NEUTRAL)
                            ManagerActionButton(AppText.get(R.string.websites_delete_short), { deleting = item }, Modifier.weight(.8f), ActionTone.STOP)
                        }
                    }
                }
            }
        }
    }
    editingId?.let { id -> AlertDialog(onDismissRequest = { editingId = null },
        title = { Text(AppText.get(if (id.isEmpty()) R.string.websites_add else R.string.websites_edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it.take(80) }, label = { Text(AppText.get(R.string.websites_name)) }, singleLine = true)
            OutlinedTextField(url, { url = it.take(4096) }, label = { Text(AppText.get(R.string.websites_address)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri), maxLines = 3)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val normalized = runCatching { WebsiteBookmarks.normalize(url) }.getOrNull()
            if (title.isBlank() || normalized == null) error = AppText.get(R.string.websites_invalid)
            else {
                val item = WebsiteBookmark(id.ifEmpty { UUID.randomUUID().toString() }, title.trim(), normalized)
                val changed = if (id.isEmpty()) rows + item else rows.map { if (it.id == id) item else it }
                if (runCatching { repository.save(changed) }.isSuccess) { rows = changed; editingId = null }
                else error = AppText.get(R.string.websites_save_failed)
            }
        }) { Text(AppText.get(R.string.websites_save)) } },
        dismissButton = { TextButton(onClick = { editingId = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(AppText.get(R.string.websites_delete)) }, text = { Text(item.title) },
        confirmButton = { TextButton(onClick = {
            val changed = rows.filterNot { it.id == item.id }
            if (runCatching { repository.save(changed) }.isSuccess) { rows = changed; deleting = null }
            else android.widget.Toast.makeText(context, AppText.get(R.string.websites_save_failed), android.widget.Toast.LENGTH_LONG).show()
        }) { Text(AppText.get(R.string.websites_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
}

@Composable private fun WebsiteAvatar(item: WebsiteBookmark) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.url) {
        value = WebsiteIcons.load(context, item.url)
    }
    Box(Modifier.size(42.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        val icon = bitmap
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(30.dp))
        else Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}
