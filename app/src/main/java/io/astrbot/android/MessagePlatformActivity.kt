package io.astrbot.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** A single platform per page; credentials and pending QR sessions stay in memory. */
class MessagePlatformActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = MessagePlatform.entries.firstOrNull { it.name == intent.getStringExtra("platform") }
            ?: return finish()
        enableEdgeToEdge()
        setContent { ManagerTheme { PlatformScreen(platform) } }
    }

    private fun backend() = startActivity(Intent(this, WebUiActivity::class.java)
        .putExtra("serviceId", ServiceId.ASTRBOT.name).putExtra(AstrBotWebRoutes.EXTRA, "platforms"))

    @Composable private fun PlatformScreen(platform: MessagePlatform) {
        val state by RuntimeStore.state.collectAsStateWithLifecycle()
        val values = rememberMessagePlatforms(state)
        val value = values.first { it.platform == platform }
        val ready = state.services.getValue(ServiceId.ASTRBOT).canOpenWebUi
        val origin = state.webUis.getValue(ServiceId.ASTRBOT).endpoint?.origin
        val nonce = state.platformObservationNonce
        val client = remember(origin, nonce) { origin?.let { address -> PlatformDashboardClient(address, persisted = { changed ->
            withContext(Dispatchers.IO) { PlatformDashboardClient.savedInFile(
                java.io.File(filesDir, "persistent/astrbot/data/cmd_config.json"), changed) }
        }) {
            val latest = RuntimeStore.state.value
            latest.services.getValue(ServiceId.ASTRBOT).canOpenWebUi && latest.platformObservationNonce == nonce &&
                latest.webUis.getValue(ServiceId.ASTRBOT).endpoint?.origin == address
        } } }
        DisposableEffect(client) { onDispose { client?.close() } }
        var signedIn by remember(client) { mutableStateOf(false) }
        var reconnect by remember { mutableIntStateOf(0) }
        var rows by remember(client) { mutableStateOf(emptyList<JSONObject>()) }
        var busy by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var edit by remember(client) { mutableStateOf<JSONObject?>(null) }
        var original by remember(client) { mutableStateOf<JSONObject?>(null) }
        var scanOnOpen by remember(client) { mutableStateOf(true) }
        var deleting by remember(client) { mutableStateOf<JSONObject?>(null) }
        var help by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        suspend fun reload() {
            rows = requireNotNull(client).list(platform)
            if (rows.isEmpty() && edit == null) {
                original = null; scanOnOpen = true
                edit = PlatformDashboardClient.defaultConfig(platform)
            }
        }
        fun leaveBinding() {
            if (busy) return
            if (rows.isEmpty()) finish() else { edit = null; original = null; failure = null }
        }
        BackHandler(enabled = edit != null && rows.isNotEmpty()) { leaveBinding() }
        LaunchedEffect(client, ready, reconnect) {
            signedIn = false
            if (!ready || client == null) return@LaunchedEffect
            busy = true; failure = null
            try {
                val secret = withContext(Dispatchers.IO) { AstrBotLocalSession.secret(this@MessagePlatformActivity, nonce) }
                    ?: error(AppText.get(R.string.platform_session_restart))
                client.connectLocal(secret)
                reload()
                signedIn = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: AppText.get(R.string.platform_request_failed) }
            finally { busy = false }
        }
        fun perform(action: suspend () -> Unit) {
            if (busy) return
            scope.launch {
                busy = true; failure = null; notice = null
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = error.message ?: AppText.get(R.string.platform_request_failed); signedIn = client?.authenticated == true }
                finally { busy = false }
            }
        }
        Scaffold(topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (edit != null && rows.isNotEmpty()) leaveBinding() else finish() }) {
                    Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_go_back), Modifier.size(22.dp))
                }
                Text(platform.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (signedIn && edit == null) IconButton(onClick = { perform { reload() } }, enabled = !busy && !state.busy) {
                    Icon(painterResource(R.drawable.ic_restart), AppText.get(R.string.ui_refresh), Modifier.size(22.dp))
                }
                IconButton(onClick = { help = true }) { Icon(painterResource(R.drawable.ic_info), AppText.get(R.string.platform_instructions), Modifier.size(22.dp)) }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).navigationBarsPadding()
                .padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlatformIcon(platform)
                        Column(Modifier.weight(1f)) {
                            Text(value.summary(), style = MaterialTheme.typography.titleSmall)
                            Text(AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_subtitle else R.string.platform_wechat_subtitle),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (!ready) ManagerSection(AppText.get(R.string.platform_setup)) {
                    Text(AppText.get(R.string.platform_service_required), style = MaterialTheme.typography.bodySmall)
                    ManagerActionButton(AppText.get(if (state.componentsReady) R.string.platform_start_astrbot else R.string.ui_initialize_runtime),
                        { RuntimeService.command(this@MessagePlatformActivity, if (state.componentsReady) "start" else "initialize", ServiceId.ASTRBOT) },
                        Modifier.fillMaxWidth(), enabled = !state.busy)
                } else if (!signedIn || client?.authenticated != true) ManagerSection(AppText.get(R.string.platform_local_session)) {
                    Text(AppText.get(if (busy) R.string.platform_local_connecting else R.string.platform_local_session_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!busy) {
                        ManagerActionButton(AppText.get(R.string.ui_retry), { reconnect++ }, Modifier.fillMaxWidth())
                        ManagerActionButton(AppText.get(R.string.platform_restart_astrbot), {
                            RuntimeService.command(this@MessagePlatformActivity, "restart", ServiceId.ASTRBOT)
                        }, Modifier.fillMaxWidth(), ActionTone.NEUTRAL, !state.busy)
                        TextButton(onClick = { backend() }) { Text(AppText.get(R.string.platform_open_backend)) }
                    }
                } else if (edit != null) {
                    PlatformEditor(platform, requireNotNull(edit), original == null, scanOnOpen, requireNotNull(client),
                        busy = busy || state.busy, allowCancel = rows.isNotEmpty(), onDismiss = ::leaveBinding, onSave = { changed ->
                            perform {
                                val result = client.save(platform, original, changed)
                                // Saving has succeeded. A failed follow-up refresh must not lose authorization
                                // or send the user back to a new QR code.
                                rows = try { client.list(platform) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { rows.filterNot { it.optString("id") == changed.optString("id") } + changed }
                                edit = null; original = null
                                notice = AppText.get(if (result == PlatformDashboardClient.SaveResult.STORED_AFTER_ERROR)
                                    R.string.platform_saved_connection_pending else R.string.platform_saved)
                            }
                        })
                } else {
                    rows.forEach { config ->
                        ManagerSection(config.optString("id")) {
                            Text(AppText.get(R.string.platform_bound_status, AppText.get(
                                if (config.optBoolean("enable", true)) R.string.platform_enabled else R.string.platform_disabled)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagerActionButton(AppText.get(R.string.platform_rebind), {
                                    original = config; scanOnOpen = true; edit = JSONObject(config.toString()); notice = null; failure = null
                                },
                                    Modifier.weight(1f), enabled = !busy && !state.busy)
                                ManagerActionButton(AppText.get(R.string.platform_delete), { deleting = config },
                                    Modifier.weight(1f), ActionTone.STOP, !busy && !state.busy)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { original = config; scanOnOpen = false; edit = JSONObject(config.toString()); notice = null; failure = null },
                                    enabled = !busy && !state.busy) { Text(AppText.get(R.string.platform_more_options)) }
                                TextButton(onClick = {
                                    perform { val changed = JSONObject(config.toString()).put("enable", !config.optBoolean("enable", true))
                                        requireNotNull(client).save(platform, config, changed); reload() }
                                }, enabled = !busy && !state.busy) {
                                    Text(AppText.get(if (config.optBoolean("enable", true)) R.string.platform_disable else R.string.platform_enable))
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = {
                            original = null; scanOnOpen = true; notice = null; failure = null
                            edit = PlatformDashboardClient.defaultConfig(platform).apply {
                                if (rows.any { it.optString("id") == optString("id") })
                                    put("id", optString("id") + "_" + java.lang.Long.toString(System.currentTimeMillis(), 36))
                            }
                        }, enabled = !busy && !state.busy && value.state != AdapterState.UNSUPPORTED) {
                            Text(AppText.get(R.string.platform_add_another))
                        }
                    }
                }
                if (edit == null && rows.isNotEmpty()) Text(AppText.get(R.string.platform_status_explanation),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        deleting?.let { config -> AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(AppText.get(R.string.platform_delete)) },
            text = { Text(AppText.get(R.string.platform_delete_hint, config.optString("id"))) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                perform { requireNotNull(client).delete(platform, config); reload(); notice = AppText.get(R.string.platform_deleted) }
            }, enabled = !busy && !state.busy) { Text(AppText.get(R.string.platform_delete_confirm), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(AppText.get(R.string.ui_cancel)) } }) }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(platform.title) },
            text = { Text(AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_help else R.string.platform_wechat_help),
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                if (platform == MessagePlatform.QQ) "https://docs.astrbot.app/platform/qqofficial/websockets.html"
                else "https://docs.astrbot.app/platform/weixin_oc.html"))) }) { Text(AppText.get(R.string.platform_official_guide)) } },
            dismissButton = { TextButton(onClick = { help = false }) { Text(AppText.get(R.string.ui_close)) } })
    }
}

@Composable private fun PlatformEditor(platform: MessagePlatform, config: JSONObject, creating: Boolean, startScan: Boolean,
    client: PlatformDashboardClient, busy: Boolean, allowCancel: Boolean, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var id by remember(config) { mutableStateOf(config.optString("id")) }
    var appid by remember(config) { mutableStateOf(config.optString("appid")) }
    var secret by remember(config) { mutableStateOf(config.optString("secret")) }
    var enabled by remember(config) { mutableStateOf(config.optBoolean("enable", true)) }
    var markdown by remember(config) { mutableStateOf(config.optBoolean("use_markdown", true)) }
    var loginResult by remember(config) { mutableStateOf<JSONObject?>(null) }
    var qr by remember(config) { mutableStateOf<JSONObject?>(null) }
    var pairing by remember(config) { mutableStateOf(false) }
    var error by remember(config) { mutableStateOf<String?>(null) }
    var qrState by remember(config) { mutableStateOf("") }
    var scanAttempt by remember(config) { mutableIntStateOf(if (startScan) 1 else 0) }
    var manual by remember(config) { mutableStateOf(false) }
    var advanced by remember(config) { mutableStateOf(!startScan) }
    var autoSaveAttempted by remember(config, scanAttempt, manual) { mutableStateOf(false) }
    fun changedConfig(): JSONObject {
        val changed = JSONObject(config.toString()).put("id", id.trim()).put("enable", enabled)
        if (platform == MessagePlatform.QQ) changed.put("appid", appid.trim()).put("secret", secret.trim()).put("use_markdown", markdown)
        else loginResult?.let { result -> listOf("weixin_oc_token", "weixin_oc_account_id", "weixin_oc_base_url", "weixin_oc_user_id")
            .forEach { key -> if (result.has(key)) changed.put(key, result.get(key)) } }
        return changed
    }
    LaunchedEffect(loginResult, busy, manual) {
        if (loginResult != null && !manual && !busy && !autoSaveAttempted && id.isNotBlank()) {
            autoSaveAttempted = true
            onSave(changedConfig())
        }
    }
    val owner = LocalLifecycleOwner.current
    // A QR result is output, never an effect key: updating it must not cancel its own poll.
    // Keep the session across background/foreground transitions; dismissal cancels requests.
    LaunchedEffect(config, scanAttempt, manual) {
        qr = null; loginResult = null; error = null; qrState = ""
        if (scanAttempt == 0 || manual) return@LaunchedEffect
        var session: JSONObject? = null
        var expiry = 0L
        var interval = 2L
        var finished = false
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (finished) return@repeatOnLifecycle
            try {
                if (session == null) {
                    pairing = true
                    val started = client.registration(config)
                    check(started.optString("status") == "pending" && registrationQrContent(started).isNotBlank()) {
                        AppText.get(R.string.platform_qr_failed)
                    }
                    session = started; qr = started
                    interval = started.optLong("interval", 2).coerceIn(1, 10)
                    expiry = android.os.SystemClock.elapsedRealtime() + 180_000
                    qrState = AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_qr_waiting else R.string.platform_qr_waiting)
                }
                pairing = false
                while (isActive) {
                    check(android.os.SystemClock.elapsedRealtime() < expiry) { AppText.get(R.string.platform_qr_expired) }
                    delay(interval * 1000L)
                    val result = client.registration(config, requireNotNull(session))
                    when (result.optString("status")) {
                        "created" -> {
                            val fields = if (platform == MessagePlatform.QQ) listOf("appid", "secret") else listOf("weixin_oc_token")
                            check(fields.all { result.optString(it).isNotBlank() }) { AppText.get(R.string.platform_qr_failed) }
                            loginResult = result
                            if (platform == MessagePlatform.QQ) { appid = result.getString("appid"); secret = result.getString("secret") }
                            qr = null; qrState = AppText.get(R.string.platform_qr_confirmed); finished = true
                            return@repeatOnLifecycle
                        }
                        "pending", "slow_down" -> {
                            if (result.optString("status") == "slow_down") interval = (interval + 2).coerceAtMost(10)
                            // QQ's numeric status 1 means pending, not proof of a scan.
                            val scanned = result.optString("qr_status") in setOf("scaned", "scanned")
                            qrState = AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_qr_waiting
                                else if (scanned) R.string.platform_qr_scanned else R.string.platform_qr_waiting)
                        }
                        "expired" -> error(AppText.get(R.string.platform_qr_expired))
                        else -> error(AppText.get(R.string.platform_qr_failed))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message; qr = null; finished = true }
            finally { pairing = false }
        }
    }
    // Rebinding requires a new authorization; retained credentials must not make a failed scan look successful.
    val credentialsReady = if (startScan && !manual) loginResult != null
        else if (platform == MessagePlatform.QQ) appid.isNotBlank() && secret.isNotBlank()
        else loginResult != null || config.optString("weixin_oc_token").isNotBlank()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppText.get(if (creating) R.string.platform_scan_bind else if (startScan) R.string.platform_rebind else R.string.platform_more_options),
                style = MaterialTheme.typography.titleMedium)
            Text(AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_scan_hint else R.string.platform_wechat_scan_hint),
                style = MaterialTheme.typography.bodySmall)
            if (!manual) {
                if (loginResult != null && qr == null && !pairing) Text(AppText.get(
                    if (busy || !autoSaveAttempted) R.string.platform_wechat_credentials_ready else R.string.platform_authorization_retained),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                qr?.let { session ->
                    val bitmap = remember(session) { runCatching {
                        val content = registrationQrContent(session)
                        require(content.isNotBlank() && content.length <= 4096)
                        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 480, 480)
                        android.graphics.Bitmap.createBitmap(480, 480, android.graphics.Bitmap.Config.ARGB_8888).apply {
                            setPixels(IntArray(480 * 480) { index -> if (matrix[index % 480, index / 480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 480, 0, 0, 480, 480)
                        }.asImageBitmap()
                    }.getOrNull() }
                    if (bitmap != null) Image(bitmap, AppText.get(if (platform == MessagePlatform.QQ) R.string.platform_qq_qr else R.string.platform_wechat_qr),
                        Modifier.fillMaxWidth().height(220.dp))
                    else Text(AppText.get(R.string.platform_qr_failed), color = MaterialTheme.colorScheme.error)
                }
                if (qrState.isNotBlank() && loginResult == null) Text(qrState, style = MaterialTheme.typography.bodySmall)
                if (pairing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(AppText.get(R.string.platform_qr_loading), style = MaterialTheme.typography.bodySmall)
                }
                ManagerActionButton(AppText.get(if (scanAttempt > 0 || credentialsReady) R.string.platform_scan_again else R.string.platform_scan),
                    { scanAttempt++ }, Modifier.fillMaxWidth(), enabled = !busy && !pairing)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (platform == MessagePlatform.QQ) TextButton(onClick = { manual = !manual }, enabled = !busy) {
                    Text(AppText.get(if (manual) R.string.platform_use_qr else R.string.platform_manual_credentials))
                }
                TextButton(onClick = { advanced = !advanced }, enabled = !busy) {
                    Text(AppText.get(if (advanced) R.string.platform_hide_options else R.string.platform_more_options))
                }
            }
            if (manual) {
                OutlinedTextField(appid, { appid = it.take(128) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("AppID") }, singleLine = true)
                OutlinedTextField(secret, { secret = it.take(4096) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            if (advanced) {
                OutlinedTextField(id, { id = it.take(128) }, Modifier.fillMaxWidth(), enabled = creating && !busy,
                    label = { Text(AppText.get(R.string.platform_bot_id)) }, singleLine = true)
                if (platform == MessagePlatform.QQ) {
                    Text(AppText.get(if (config.optString("type") == "qq_official_webhook") R.string.platform_existing_webhook else R.string.platform_qq_websocket),
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(AppText.get(R.string.platform_markdown), Modifier.weight(1f)); Switch(markdown, { markdown = it }, enabled = !busy) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text(AppText.get(R.string.platform_enabled), Modifier.weight(1f)); Switch(enabled, { enabled = it }, enabled = !busy) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (manual || !startScan || (loginResult != null && autoSaveAttempted && !busy))
              ManagerActionButton(AppText.get(R.string.platform_save_connect), onClick = {
                onSave(changedConfig())
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy && !pairing && qr == null && id.isNotBlank() && credentialsReady)
            if (allowCancel)
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(AppText.get(R.string.ui_cancel))
                }
        }
    }
}

private fun registrationQrContent(session: JSONObject): String = listOf("verification_uri_complete", "qrcode_img_content", "qrcode")
    .firstNotNullOfOrNull { session.optString(it).takeIf(String::isNotBlank) }.orEmpty()
