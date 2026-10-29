package io.astrbot.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Magnifier
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.astrbot.android.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** Local, offline terminal renderer. Only RuntimeService can create or own a shell. */
class TerminalActivity : LocalizedActivity() {
    private var browser: WebView? = null
    private var ready by mutableStateOf(false)
    private var control by mutableStateOf(false)
    private var selectionMode by mutableStateOf(false)
    private var pageError by mutableStateOf<String?>(null)
    private var pendingPaste by mutableStateOf<Pair<Long, String>?>(null)
    private var selectionMagnifier: Magnifier? = null
    private var selectionTouchActive = false
    private val preferences by lazy { getSharedPreferences("terminal-ui", MODE_PRIVATE) }
    private var fontSize by mutableIntStateOf(10)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fontSize = preferences.getInt("fontSize", 10).coerceIn(10, 24)
        setContent { ManagerTheme { TerminalScreen() } }
        lifecycleScope.launch {
            RuntimeStore.operationLock.withLock { RuntimeStore.refreshInstallation(this@TerminalActivity) }
            if (savedInstanceState == null && RuntimeStore.state.value.environmentInstalled && !RuntimeStore.state.value.busy && TerminalStore.state.value.sessions.isEmpty()) startSession()
        }
    }

    private fun startSession(restart: Boolean = false, id: Long? = TerminalStore.state.value.selectedId, newSession: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        RuntimeService.command(this, if (newSession) "terminal-new" else if (restart) "terminal-restart" else "terminal-start", terminalSessionId = id)
    }

    @Composable private fun TerminalScreen() {
        val session by TerminalStore.state.collectAsStateWithLifecycle()
        val current = session.active
        val manager by RuntimeStore.state.collectAsStateWithLifecycle()
        var menu by remember { mutableStateOf(false) }
        var confirmation by remember { mutableStateOf<Pair<String, Long>?>(null) }
        var help by remember { mutableStateOf(false) }
        val enabled = manager.environmentInstalled && !manager.busy
        LaunchedEffect(ready, session.sessions, session.selectedId) {
            control = false
            if (current == null) selectionMode = false
            if (ready) syncSessions()
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_back), AppText.get(R.string.ui_back), Modifier.size(22.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(AppText.get(R.string.terminal_name), style = MaterialTheme.typography.titleMedium)
                    Text(if (session.running) AppText.get(R.string.terminal_running_count, session.runningCount) else AppText.get(R.string.terminal_session_stopped),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { setFont(fontSize - 1) }, enabled = ready && fontSize > 10,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = AppText.get(R.string.terminal_font_smaller) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { setFont(fontSize + 1) }, enabled = ready && fontSize < 24,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = AppText.get(R.string.terminal_font_larger) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { keyboard() }, enabled = ready && current?.running == true) {
                    Icon(painterResource(R.drawable.ic_keyboard), AppText.get(R.string.terminal_keyboard), Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), AppText.get(R.string.terminal_actions), Modifier.size(22.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        fun closeThen(action: () -> Unit) { menu = false; action() }
                        DropdownMenuItem(text = { Text(AppText.get(if (current?.running == true) R.string.terminal_restart else R.string.terminal_start)) }, enabled = enabled,
                            onClick = { closeThen { if (current?.running == true) confirmation = "restart" to current.id else startSession() } })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.terminal_end)) }, enabled = current != null && !manager.busy,
                            onClick = { closeThen { current?.let { confirmation = "close" to it.id } } })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.terminal_copy)) }, enabled = ready, onClick = { closeThen { js("TerminalApp.copy()") } })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.terminal_paste)) }, enabled = ready && current?.running == true && !manager.busy, onClick = { closeThen { paste() } })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.terminal_clear)) }, enabled = ready && !manager.busy, onClick = { closeThen { TerminalStore.clearScreen() } })
                        DropdownMenuItem(text = { Text(AppText.get(R.string.ui_help)) }, onClick = { closeThen { help = true } })
                    }
                }
            }
            if (selectionMode) Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                for ((label, action) in listOf(R.string.terminal_copy_selection to "copy", R.string.terminal_select_all to "all", R.string.ui_cancel to "cancel")) {
                    TextButton(onClick = { js("TerminalApp.selection(${JSONObject.quote(action)})") },
                        modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        Text(AppText.get(label), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.sessions.forEach { tab -> key(tab.id) {
                        val bringIntoView = remember { BringIntoViewRequester() }
                        LaunchedEffect(session.selectedId) { if (session.selectedId == tab.id) { withFrameNanos { }; bringIntoView.bringIntoView() } }
                        InputChip(selected = session.selectedId == tab.id, onClick = { TerminalStore.select(tab.id) },
                            modifier = Modifier.bringIntoViewRequester(bringIntoView),
                            label = { Text(AppText.get(R.string.terminal_session_label, tab.id)) },
                            leadingIcon = { Text(if (tab.running) "●" else "○", color = if (tab.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = {
                                IconButton(onClick = { confirmation = "close" to tab.id }, enabled = !manager.busy,
                                    modifier = Modifier.size(28.dp).semantics { contentDescription = AppText.get(R.string.terminal_close_label, tab.id) }) { Text("×") }
                            })
                    } }
                    if (session.sessions.isEmpty()) Text(AppText.get(R.string.terminal_no_sessions), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { startSession(newSession = true) }, enabled = enabled && session.sessions.size < TerminalStore.MAX_SESSIONS,
                    modifier = Modifier.semantics { contentDescription = AppText.get(R.string.terminal_new) }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            }
            if (!manager.environmentInstalled) {
                Text(AppText.get(R.string.terminal_initialize_first), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            val message = pageError ?: session.error ?: current?.error ?: current?.exitCode?.let {
                when (it) {
                    137 -> AppText.get(R.string.terminal_exited_killed)
                    143 -> AppText.get(R.string.terminal_exited_terminated)
                    else -> AppText.get(R.string.terminal_exited, it)
                }
            }
            if (message != null) Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (manager.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { createBrowser() }, modifier = Modifier.fillMaxWidth().weight(1f))
            val keysEnabled = ready && current?.running == true && !manager.busy && !selectionMode
            Column(Modifier.fillMaxWidth().background(ComposeColor(0xff1b2435)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Shortcut("Ctrl", control, keysEnabled, Modifier.weight(1f)) { control = !control; js("TerminalApp.ctrl($control)") }
                    for ((label, value) in listOf("Esc" to "\u001b", "↑" to "\u001b[A", "/" to "/")) {
                        Shortcut(label, false, keysEnabled, Modifier.weight(1f)) { js("TerminalApp.key(${JSONObject.quote(value)})") }
                    }
                    Shortcut(AppText.get(R.string.terminal_paste), false, keysEnabled, Modifier.weight(1f)) { paste() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value) in listOf("Tab" to "\t", "←" to "\u001b[D", "↓" to "\u001b[B", "→" to "\u001b[C")) {
                        Shortcut(label, false, keysEnabled, Modifier.weight(1f)) { js("TerminalApp.key(${JSONObject.quote(value)})") }
                    }
                    Shortcut(AppText.get(R.string.terminal_enter), false, keysEnabled, Modifier.weight(1f)) { js("TerminalApp.key(\"\\r\")") }
                }
            }
        }
        confirmation?.let { (action, id) ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(AppText.get(if (action == "close") R.string.terminal_end else R.string.terminal_restart)) },
                text = { Text(AppText.get(R.string.terminal_end_message)) },
                confirmButton = { TextButton(onClick = { confirmation = null; if (action == "close") RuntimeService.command(this, "terminal-close", terminalSessionId = id) else startSession(true, id) }) { Text(AppText.get(R.string.ui_ok)) } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        pendingPaste?.let { (id, value) ->
            AlertDialog(onDismissRequest = { pendingPaste = null }, title = { Text(AppText.get(R.string.terminal_paste_confirm)) },
                text = { Text(AppText.get(R.string.terminal_paste_message)) },
                confirmButton = { TextButton(onClick = { pendingPaste = null; sendPaste(value, id) }) { Text(AppText.get(R.string.terminal_paste)) } },
                dismissButton = { TextButton(onClick = { pendingPaste = null }) { Text(AppText.get(R.string.ui_cancel)) } })
        }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(AppText.get(R.string.terminal_name)) },
            text = { Text(AppText.get(R.string.terminal_help)) }, confirmButton = { TextButton(onClick = { help = false }) { Text(AppText.get(R.string.ui_ok)) } })
    }

    @Composable private fun Shortcut(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
        FilledTonalButton(action, enabled = enabled, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = modifier.height(40.dp), colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = ComposeColor(if (selected) 0xff3867df else 0xff2a354b), contentColor = ComposeColor.White,
                disabledContainerColor = ComposeColor(0xff242d3e), disabledContentColor = ComposeColor(0xff8792a6))) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createBrowser(): WebView = WebView(this).apply {
        browser = this
        setBackgroundColor(Color.rgb(18, 24, 38))
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(false); settings.builtInZoomControls = false; settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@TerminalActivity)).build()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == HOST && uri.path?.startsWith("/assets/terminal/") == true && request.method == "GET") {
                    loader.shouldInterceptRequest(uri)?.let { return it }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                pageError = AppText.get(R.string.terminal_renderer_error)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "UbuntuTerminal", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == ORIGIN && url == PAGE) {
                    val text = message.data
                    if (text != null && text.length <= 600_000) runCatching { handleMessage(JSONObject(text)) }
                }
            }
            loadUrl(PAGE)
        } else pageError = AppText.get(R.string.terminal_webview_required)
    }

    private fun handleMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                ready = true
                val labels = JSONObject().put("latest", AppText.get(R.string.terminal_scroll_latest))
                js("TerminalApp.labels($labels);TerminalApp.font($fontSize);TerminalApp.active(${lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)})")
                syncSessions()
            }
            "poll" -> js("TerminalApp.receive(${TerminalStore.output(message.optLong("generation", -1), message.optLong("sequence"), message.optLong("id", -1))})")
            "input" -> if (!RuntimeStore.state.value.busy && message.optLong("id", -1) == TerminalStore.state.value.selectedId)
                TerminalStore.input(message.optString("data"), message.optLong("id"))
            "resize" -> TerminalStore.resize(message.optInt("columns"), message.optInt("rows"), message.optLong("id", -1))
            "ctrlReleased" -> control = false
            "selection" -> if (message.optLong("id", -1) == TerminalStore.state.value.selectedId) {
                selectionMode = message.optBoolean("selected")
                if (!selectionMode) dismissMagnifier()
                if (selectionMode) browser?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
            }
            "magnifier" -> updateMagnifier(message)
            "selection-handles" -> updateHandleGestures(message)
            "copy" -> {
                if (message.optLong("id", -1) != TerminalStore.state.value.selectedId) return
                val text = message.optString("data").take(524288)
                if (text.isNotEmpty()) {
                    val clip = ClipData.newPlainText(AppText.get(R.string.terminal_name), text)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(this, AppText.get(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncSessions() {
        val state = TerminalStore.state.value
        val sessions = JSONArray().apply { state.sessions.forEach { put(JSONObject().put("id", it.id).put("running", it.running)) } }
        js("TerminalApp.sessions($sessions,${state.selectedId ?: -1})")
    }

    private fun js(script: String) { if (ready) browser?.evaluateJavascript(script, null) }
    private fun setFont(value: Int) { fontSize = value.coerceIn(10, 24); preferences.edit().putInt("fontSize", fontSize).apply(); js("TerminalApp.font($fontSize)") }
    private fun dismissMagnifier() {
        magnifierRevision++
        if (Build.VERSION.SDK_INT >= 28) selectionMagnifier?.dismiss()
        selectionMagnifier = null
    }

    private var magnifierRevision = 0L

    private fun updateHandleGestures(message: JSONObject) {
        if (Build.VERSION.SDK_INT < 29 || message.optLong("id", -1) != TerminalStore.state.value.selectedId) return
        val view = browser ?: return
        val width = message.optDouble("width"); val height = message.optDouble("height")
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return
        val rectangles = message.optJSONArray("rects") ?: return
        view.systemGestureExclusionRects = (0 until minOf(2, rectangles.length())).mapNotNull { index ->
            val item = rectangles.optJSONArray(index) ?: return@mapNotNull null
            val values = (0..3).map { item.optDouble(it) }
            if (values.any { !it.isFinite() }) return@mapNotNull null
            android.graphics.Rect((values[0] * view.width / width).toInt().coerceIn(0, view.width),
                (values[1] * view.height / height).toInt().coerceIn(0, view.height),
                (values[2] * view.width / width).toInt().coerceIn(0, view.width),
                (values[3] * view.height / height).toInt().coerceIn(0, view.height)).takeUnless { it.isEmpty }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) selectionTouchActive = true
        val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (released) selectionTouchActive = false
        return try { super.dispatchTouchEvent(event) }
        finally { if (released) dismissMagnifier() }
    }

    private fun updateMagnifier(message: JSONObject) {
        if (!message.optBoolean("show")) { dismissMagnifier(); return }
        if (Build.VERSION.SDK_INT < 28 || !selectionTouchActive || !selectionMode || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            message.optLong("id", -1) != TerminalStore.state.value.selectedId) return
        val view = browser ?: return
        val x = message.optDouble("x"); val y = message.optDouble("y")
        val width = message.optDouble("width"); val height = message.optDouble("height")
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0 || height <= 0 || x !in 0.0..width || y !in 0.0..height || view.width <= 0 || view.height <= 0) return
        val magnifier = selectionMagnifier ?: if (Build.VERSION.SDK_INT >= 29) {
            val density = resources.displayMetrics.density
            Magnifier.Builder(view).setSize((168 * density).toInt(), (56 * density).toInt())
                .setInitialZoom(2f).setCornerRadius(10 * density).build()
        } else Magnifier(view)
        selectionMagnifier = magnifier
        val revision = ++magnifierRevision
        // Wait for the WebView selection frame; JS geometry can otherwise be
        // newer than the surface copied by Android's native magnifier.
        view.postVisualStateCallback(revision, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId != magnifierRevision || !selectionTouchActive || selectionMagnifier !== magnifier) return
                view.postOnAnimation {
                    if (requestId != magnifierRevision || !selectionTouchActive || selectionMagnifier !== magnifier) return@postOnAnimation
                    magnifier.show((x * view.width / width).toFloat(), (y * view.height / height).toFloat())
                    view.postOnAnimation {
                        if (requestId == magnifierRevision && selectionTouchActive && selectionMagnifier === magnifier) magnifier.update()
                    }
                }
            }
        })
    }
    private fun keyboard() {
        if (ready) browser?.let { view ->
            view.evaluateJavascript("TerminalApp.focus()") {
                view.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private fun paste() {
        val id = TerminalStore.state.value.selectedId ?: return
        val text = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.length > 65536) { Toast.makeText(this, AppText.get(R.string.terminal_paste_too_large), Toast.LENGTH_LONG).show(); return }
        if (text.any { it == '\n' || it == '\r' }) pendingPaste = id to text else sendPaste(text, id)
    }
    private fun sendPaste(text: String, id: Long) {
        if (!RuntimeStore.state.value.busy && TerminalStore.state.value.selectedId == id && TerminalStore.state.value.active?.running == true)
            js("TerminalApp.paste(${JSONObject.quote(text)})")
    }

    override fun onStart() { super.onStart(); browser?.onResume(); js("TerminalApp.active(true)") }
    override fun onStop() {
        selectionTouchActive = false; dismissMagnifier()
        if (Build.VERSION.SDK_INT >= 29) browser?.systemGestureExclusionRects = emptyList()
        js("TerminalApp.active(false)"); browser?.onPause(); pendingPaste = null; super.onStop()
    }

    override fun onDestroy() {
        dismissMagnifier()
        ready = false
        browser?.apply { stopLoading(); removeAllViews(); destroy() }
        browser = null
        super.onDestroy()
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val ORIGIN = "https://$HOST"
        private const val PAGE = "$ORIGIN/assets/terminal/index.html"
    }
}
