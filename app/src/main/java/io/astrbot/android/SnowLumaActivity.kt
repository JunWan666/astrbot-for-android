package io.astrbot.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SnowPage(val title: Int) {
    HOME(R.string.snow_name), DIRECTORY(R.string.snow_directory), GUIDE(R.string.snow_guide),
    CREDENTIALS(R.string.snow_credentials), OUTPUT(R.string.snow_output), ABOUT(R.string.snow_about)
}

class SnowLumaActivity : LocalizedActivity() {
    private val repository by lazy { SnowLumaRepository(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ManagerTheme { SnowLumaScreen() } }
    }
    override fun onStart() { super.onStart(); refresh() }
    private fun refresh() { lifecycleScope.launch { withContext(Dispatchers.IO) { repository.refresh() } } }
    private fun external(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    private fun desktop() {
        startActivity(Intent(this, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_OPEN_QQ, true))
    }
    private fun terminal() { startActivity(Intent(this, TerminalActivity::class.java)) }
    private fun copy(command: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SnowLuma", command))
        Toast.makeText(this, AppText.get(R.string.snow_copied), Toast.LENGTH_SHORT).show()
    }
    private fun copyCredential(value: String) {
        val clip = ClipData.newPlainText("SnowLuma", value)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, AppText.get(R.string.snow_credentials_copied), Toast.LENGTH_SHORT).show()
    }

    @Composable private fun SnowLumaScreen() {
        val state by SnowLumaStore.state.collectAsStateWithLifecycle()
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        val output by SnowLumaStore.output.collectAsStateWithLifecycle()
        val initialCredentials by SnowLumaStore.initialCredentials.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableStateOf(SnowPage.HOME) }
        var revealPassword by remember(page, initialCredentials) { mutableStateOf(false) }
        val credentials = initialCredentials.takeIf { state.active }
        var guideCompleted by rememberSaveable { mutableStateOf(repository.guideCompleted()) }
        var savedStep by rememberSaveable { mutableIntStateOf(0) }
        var editDirectory by remember { mutableStateOf(false) }
        var directory by remember { mutableStateOf(repository.directory()) }
        var directoryError by remember { mutableStateOf<String?>(null) }
        var errorDetail by remember { mutableStateOf<String?>(null) }
        val napcat = manager.services.getValue(ServiceId.NAPCAT).desiredRunning
        fun back() { if (page == SnowPage.HOME) finish() else page = SnowPage.HOME }
        fun command(action: String) { RuntimeService.command(this, "snowluma-$action") }
        val steps = listOf(R.string.snow_step_environment, R.string.snow_step_package, R.string.snow_step_profile,
            R.string.snow_step_astrbot)
        val step = savedStep.coerceIn(steps.indices)
        BackHandler { back() }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(22.dp)) }
                Text(AppText.get(page.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (page == SnowPage.HOME) Text(AppText.get(R.string.snow_manual), Modifier.padding(end = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    SnowPage.HOME -> {
                        ManagerSection(AppText.get(R.string.snow_runtime)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ManagerGlyph(R.drawable.ic_link, MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(AppText.get(R.string.snow_name), style = MaterialTheme.typography.titleMedium)
                                    Text(AppText.get(when {
                                        state.ready -> R.string.ui_running
                                        state.active -> R.string.snow_starting
                                        state.installed -> R.string.ui_stopped
                                        else -> R.string.snow_uninstalled
                                    }), style = MaterialTheme.typography.bodySmall,
                                        color = if (state.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (state.active && !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                            ManagerActionButton(AppText.get(R.string.snow_open), {
                                startActivity(Intent(this@SnowLumaActivity, WebsiteBrowserActivity::class.java).putExtra("snowluma", true))
                            }, Modifier.fillMaxWidth(), enabled = state.ready)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(R.string.ui_start), { command("start") }, Modifier.weight(1f), ActionTone.START,
                                    !manager.busy && manager.environmentInstalled && state.installed && state.profileReady && !state.active && !napcat)
                                ManagerActionButton(AppText.get(R.string.ui_stop), { command("stop") }, Modifier.weight(1f), ActionTone.STOP,
                                    !manager.busy && state.active)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(R.string.snow_desktop), { desktop() }, Modifier.weight(1f), ActionTone.NEUTRAL)
                                ManagerActionButton(AppText.get(R.string.snow_terminal), { terminal() }, Modifier.weight(1f))
                            }
                            if (napcat) Text(AppText.get(R.string.snow_napcat_conflict), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            state.error?.let { error -> Text(error, Modifier.clickable { errorDetail = error }, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        GroupedManagerMenu(listOf(AppText.get(R.string.snow_manage_group) to listOf(
                            ManagerMenuItem(AppText.get(R.string.snow_guide), R.drawable.ic_document,
                                AppText.get(if (guideCompleted) R.string.snow_guide_completed else R.string.snow_guide_hint)) { page = SnowPage.GUIDE },
                            ManagerMenuItem(AppText.get(R.string.snow_directory), R.drawable.ic_settings,
                                AppText.get(if (state.installed && state.profileReady) R.string.snow_setup_ready else R.string.snow_setup_pending)) { page = SnowPage.DIRECTORY },
                            ManagerMenuItem(AppText.get(R.string.snow_credentials), R.drawable.ic_visibility,
                                AppText.get(if (credentials != null) R.string.snow_credentials_available else R.string.snow_credentials_unavailable)) { page = SnowPage.CREDENTIALS },
                            ManagerMenuItem(AppText.get(R.string.snow_output), R.drawable.ic_logs) { page = SnowPage.OUTPUT },
                            ManagerMenuItem(AppText.get(R.string.snow_about), R.drawable.ic_info) { page = SnowPage.ABOUT }
                        )), Modifier.weight(1f))
                    }
                    SnowPage.DIRECTORY -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ManagerSection(AppText.get(R.string.snow_directory)) {
                            SelectionContainer { Text(state.directory, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                            SetupCheck(state.installed, AppText.get(if (state.installed) R.string.snow_files_ready else R.string.snow_files_missing))
                            SetupCheck(state.profileReady, AppText.get(if (state.profileReady) R.string.snow_profile_ready else R.string.snow_profile_missing))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(R.string.snow_check), { refresh() }, Modifier.weight(1f), ActionTone.NEUTRAL, !manager.busy)
                                ManagerActionButton(AppText.get(R.string.snow_edit_directory), {
                                    directory = state.directory; directoryError = null; editDirectory = true
                                }, Modifier.weight(1f), ActionTone.NEUTRAL, !state.active && !manager.busy)
                            }
                        }
                    }
                    SnowPage.GUIDE -> {
                        LinearProgressIndicator(progress = { (step + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
                        Text(AppText.get(R.string.snow_step, step + 1, steps.size, AppText.get(steps[step])), style = MaterialTheme.typography.titleSmall)
                        key(step) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (step == steps.lastIndex) {
                                    ManagerSection(AppText.get(R.string.snow_astrbot_dashboard)) {
                                        GuideText(AppText.get(R.string.snow_astrbot_detail))
                                        GuideValue(R.string.snow_listen, "127.0.0.1:6199")
                                        GuideText(AppText.get(R.string.snow_astrbot_token))
                                    }
                                    ManagerSection(AppText.get(R.string.snow_snowluma_dashboard)) {
                                        GuideText(AppText.get(R.string.snow_panel_detail))
                                        GuideValue(R.string.snow_connection_url, "ws://127.0.0.1:6199/ws")
                                        GuideValue(R.string.snow_connection_role, "Universal")
                                        GuideValue(R.string.snow_message_format, "array")
                                        GuideText(AppText.get(R.string.snow_connection_token))
                                    }
                                    GuideText(AppText.get(R.string.snow_verify_reply))
                                } else ManagerSection(AppText.get(steps[step])) {
                                    when (step) {
                                        0 -> {
                                            GuideText(AppText.get(R.string.snow_environment_detail))
                                            ManagerActionButton(AppText.get(R.string.snow_qq_install_page), {
                                                startActivity(Intent(this@SnowLumaActivity, DesktopActivity::class.java))
                                            }, Modifier.fillMaxWidth())
                                        }
                                        1 -> {
                                            GuideText(AppText.get(R.string.snow_package_detail))
                                            CommandBlock(listOf(
                                                "# ${AppText.get(R.string.snow_package_directory)}",
                                                "mkdir -p ${SnowLumaRepository.quote(state.directory)} && cd ${SnowLumaRepository.quote(state.directory)} &&",
                                                "# ${AppText.get(R.string.snow_package_download)}",
                                                "curl -fL --retry 2 -o SnowLuma.tar.gz ${SnowLumaRepository.ARCHIVE_URL} &&",
                                                "# ${AppText.get(R.string.snow_package_verify)}",
                                                "printf '%s  %s\\n' '${SnowLumaRepository.ARCHIVE_SHA256}' 'SnowLuma.tar.gz' | sha256sum -c - &&",
                                                "# ${AppText.get(R.string.snow_package_extract)}",
                                                "tar -xzf SnowLuma.tar.gz"
                                            ).joinToString("\n"), terminalButton = true)
                                            TextButton(onClick = { external(SnowLumaRepository.OFFICIAL_RELEASE) }) { Text(AppText.get(R.string.snow_official)) }
                                        }
                                        2 -> {
                                            GuideText(AppText.get(R.string.snow_profile_detail))
                                            CommandBlock(SnowLumaRepository.profileCommand(state.directory), terminalButton = true)
                                            ManagerActionButton(AppText.get(R.string.snow_desktop), { desktop() }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                                        }
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (step > 0) ManagerActionButton(AppText.get(R.string.snow_previous), { savedStep = step - 1 }, Modifier.weight(1f), ActionTone.NEUTRAL)
                            ManagerActionButton(AppText.get(if (step == steps.lastIndex) R.string.snow_finish_guide else R.string.snow_next), {
                                if (step == steps.lastIndex) {
                                    repository.completeGuide(); guideCompleted = true; page = SnowPage.HOME
                                } else savedStep = step + 1
                            }, Modifier.weight(if (step == steps.lastIndex) 2f else 1f))
                        }
                    }
                    SnowPage.CREDENTIALS -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ManagerSection(AppText.get(R.string.snow_credentials)) {
                            Text(AppText.get(R.string.snow_credentials_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (credentials == null) {
                                Text(AppText.get(R.string.snow_credentials_missing), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(AppText.get(R.string.snow_username), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        SelectionContainer { Text(credentials.username, fontFamily = FontFamily.Monospace) }
                                    }
                                    TextButton(onClick = { copyCredential(credentials.username) }) { Text(AppText.get(R.string.snow_copy_username)) }
                                }
                                Text(AppText.get(R.string.snow_initial_password), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(if (revealPassword) credentials.password else "••••••••••••",
                                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp),
                                    fontFamily = FontFamily.Monospace) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ManagerActionButton(AppText.get(if (revealPassword) R.string.snow_hide_password else R.string.snow_show_password),
                                        { revealPassword = !revealPassword }, Modifier.weight(1f), ActionTone.NEUTRAL)
                                    ManagerActionButton(AppText.get(R.string.snow_copy_password), { copyCredential(credentials.password) }, Modifier.weight(1f))
                                }
                                TextButton(onClick = { SnowLumaStore.forgetInitialCredentials() }) { Text(AppText.get(R.string.snow_forget_credentials)) }
                            }
                            ManagerActionButton(AppText.get(R.string.snow_open), {
                                startActivity(Intent(this@SnowLumaActivity, WebsiteBrowserActivity::class.java).putExtra("snowluma", true))
                            }, Modifier.fillMaxWidth(), enabled = state.ready)
                        }
                    }
                    SnowPage.OUTPUT -> {
                        Text(AppText.get(R.string.snow_output_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow,
                            MaterialTheme.shapes.medium).padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text(output.ifBlank { AppText.get(R.string.snow_output_empty) },
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    SnowPage.ABOUT -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ManagerSection(AppText.get(R.string.snow_runtime)) {
                            Text(AppText.get(R.string.snow_intro), style = MaterialTheme.typography.bodyMedium)
                            Text(AppText.get(R.string.snow_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ManagerSection(AppText.get(R.string.snow_about)) {
                            Text(AppText.get(R.string.snow_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ManagerActionButton(AppText.get(R.string.snow_license), { external("https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/LICENSE") }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                            ManagerActionButton("EULA", { external("https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/EULA.md") }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                            ManagerActionButton(AppText.get(R.string.snow_tutorial), { external("https://github.com/JunWan666/astrbot-for-android/blob/main/docs/SnowLuma手动部署.md") }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL)
                        }
                    }
                }
            }
        }
        if (editDirectory) AlertDialog(onDismissRequest = { editDirectory = false }, title = { Text(AppText.get(R.string.snow_edit_directory)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = directory, onValueChange = { directory = it; directoryError = null }, singleLine = true, isError = directoryError != null)
                directoryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = {
                runCatching { repository.saveDirectory(directory.trim()) }.onSuccess { editDirectory = false }
                    .onFailure { directoryError = it.message ?: AppText.get(R.string.snow_invalid_directory) }
            }) { Text(AppText.get(R.string.ui_ok)) } }, dismissButton = { TextButton(onClick = { editDirectory = false }) { Text(AppText.get(R.string.ui_cancel)) } })
        errorDetail?.let { error -> AlertDialog(onDismissRequest = { errorDetail = null }, title = { Text(AppText.get(R.string.snow_name)) },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { errorDetail = null; page = SnowPage.OUTPUT }) { Text(AppText.get(R.string.snow_output)) } },
            dismissButton = { TextButton(onClick = { errorDetail = null }) { Text(AppText.get(R.string.ui_close)) } }) }
    }

    @Composable private fun SetupCheck(ready: Boolean, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ready) "✓" else "○", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** Only the inline emphasis and code used by the guide; commands remain literal. */
    @Composable private fun GuideText(text: String) {
        val codeColor = MaterialTheme.colorScheme.primary
        val highlighted = remember(text, codeColor) {
            buildAnnotatedString {
                val tokens = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
                var offset = 0
                tokens.findAll(text).forEach { match ->
                    append(text.substring(offset, match.range.first))
                    val bold = match.groups[1]
                    withStyle(if (bold != null) SpanStyle(fontWeight = FontWeight.SemiBold)
                        else SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                        append(bold?.value ?: match.groups[2]!!.value)
                    }
                    offset = match.range.last + 1
                }
                append(text.substring(offset))
            }
        }
        Text(highlighted, style = MaterialTheme.typography.bodyMedium)
    }

    @Composable private fun GuideValue(label: Int, value: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text(AppText.get(label), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    @Composable private fun CommandBlock(command: String, terminalButton: Boolean = false) {
        val dark = isSystemInDarkTheme()
        val commandColor = if (dark) Color(0xFFB9D1FF) else Color(0xFF244B78)
        val commentColor = if (dark) Color(0xFF8BC69D) else Color(0xFF2F7048)
        val highlighted = remember(command, commandColor, commentColor) {
            buildAnnotatedString {
                command.lineSequence().forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    val comment = line.trimStart().startsWith("#")
                    withStyle(SpanStyle(color = if (comment) commentColor else commandColor,
                        fontWeight = if (comment) FontWeight.Medium else FontWeight.Normal)) { append(line) }
                }
            }
        }
        SelectionContainer { Text(highlighted, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.shapes.small).padding(10.dp), softWrap = true,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp), fontFamily = FontFamily.Monospace) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerActionButton(AppText.get(R.string.snow_copy), { copy(command) }, Modifier.weight(1f))
            if (terminalButton) ManagerActionButton(AppText.get(R.string.snow_terminal), { terminal() }, Modifier.weight(1f))
        }
        if (terminalButton) Text(AppText.get(R.string.snow_copy_terminal_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}
