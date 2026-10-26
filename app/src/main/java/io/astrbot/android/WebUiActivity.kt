package io.astrbot.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.ServiceId
import io.astrbot.android.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** This Activity owns only the browser. RuntimeService owns the servers. */
class WebUiActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var endpoint: LocalEndpoint
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var recoveryPanel: ComposeView
    private lateinit var serviceId: ServiceId
    private lateinit var browserScale: BrowserScale
    private val pageFailure = MutableStateFlow<WebUiFailure?>(null)
    private val pendingAction = MutableStateFlow<String?>(null)
    private var loadDeadline: Job? = null
    private var loginAssist: Job? = null
    private var loadSequence = 0L
    private var committedSequence = -1L
    private var reloadAfterRecovery = true
    private var modelSettingsEntry = false
    private var pendingModelSettings = false
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var chooseMultiple = false
    private var pendingDownload: String? = null
    private var pendingExportToken: String? = null
    private val exportCache by lazy { WebExportCache(File(cacheDir, "web-exports")) }
    private var downloadNonce: String? = null
    private val blobReaderKey = "__astrbotBlob_" + UUID.randomUUID().toString().replace("-", "")
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        completeExport(uri, image = true)
    }
    private val upload = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (chooser == null && uris.isNotEmpty()) Toast.makeText(this, "页面已重建，请重新选择上传文件", Toast.LENGTH_LONG).show()
        chooser?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.let { if (chooseMultiple) it.toTypedArray() else arrayOf(it.first()) })
        chooser = null
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        completeExport(uri, image = false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = ServiceId.entries.firstOrNull { it.name == intent.getStringExtra("serviceId") } ?: run { finish(); return }
        serviceId = id
        browserScale = BrowserScale(this, id.name)
        modelSettingsEntry = id == ServiceId.ASTRBOT && intent.getBooleanExtra(AiAccess.MODEL_SETTINGS_EXTRA, false)
        pendingModelSettings = modelSettingsEntry && (savedInstanceState?.getBoolean("pendingModelSettings", true) ?: true)
        // A missing/disabled address gets native recovery controls; never load this
        // placeholder unless a real enabled configuration has the same origin.
        endpoint = RuntimeStore.state.value.webUis.getValue(id).endpoint ?: LocalEndpoint(id.defaultPort)
        restorePendingExport(savedInstanceState)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground = android.graphics.Color.parseColor(if (dark) "#E9EBF1" else "#202536")
        val background = android.graphics.Color.parseColor(if (dark) "#1B1F28" else "#FFFFFF")
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(background) }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        fun icon(label: String, drawable: Int, action: (ImageButton) -> Unit) = ImageButton(this).apply {
            contentDescription = label
            setImageResource(drawable)
            imageTintList = android.content.res.ColorStateList.valueOf(foreground)
            val attribute = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attribute, true)
            setBackgroundResource(attribute.resourceId)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { action(this) }
            toolbar.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        icon("返回", R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply { text = "${id.label} 后台"; textSize = 16f; setTextColor(foreground); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        status = TextView(this).apply {
            text = endpoint.origin; textSize = 11f
            setTextColor(android.graphics.Color.parseColor(if (dark) "#A0A7B7" else "#7B8294"))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        heading.addView(status)
        toolbar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (modelSettingsEntry) toolbar.addView(android.widget.Button(this).apply {
            text = "模型配置"; textSize = 12f; setTextColor(foreground)
            setOnClickListener {
                pendingModelSettings = true
                val state = RuntimeStore.state.value
                if (!webUiNeedsRecovery(id, state, endpoint.origin, pageFailure.value, pendingAction.value)) {
                    webView.loadUrl(endpoint.origin + AiAccess.MODEL_SETTINGS_PATH)
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        icon("刷新", R.drawable.ic_restart) { retryPage() }
        icon("更多操作", R.drawable.ic_more) { anchor ->
            PopupMenu(this, anchor).apply {
                val connection = RuntimeStore.state.value.webUis.getValue(id)
                if (connection.enabled && connection.endpoint?.origin == endpoint.origin) {
                    menu.add("外部浏览器打开"); menu.add("复制地址")
                    if (!webUiNeedsRecovery(id, RuntimeStore.state.value, endpoint.origin, pageFailure.value, pendingAction.value)) menu.add("保存页面图片")
                }
                menu.add("查看日志"); menu.add("关闭后台")
                menu.add("页面缩放 · ${browserScale.percent}%")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "外部浏览器打开" -> openExternal(endpoint.origin)
                        "复制地址" -> {
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("后台地址", endpoint.origin))
                            Toast.makeText(this@WebUiActivity, "已复制后台地址", Toast.LENGTH_SHORT).show()
                        }
                        "保存页面图片" -> saveVisiblePage()
                        "查看日志" -> openManager(logs = true)
                        "关闭后台" -> finish()
                        else -> if (item.title.toString().startsWith("页面缩放")) browserScale.choose(webView)
                    }
                    true
                }
                show()
            }
        }
        layout.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#386BF6"))
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (endpoint.acceptsNavigation(request.url.toString())) {
                        if (request.isForMainFrame && request.url.toString().substringBefore('#') != view.url?.substringBefore('#')) beginNavigation()
                        return false
                    }
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http")) openExternal(request.url.toString())
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loadSequence++
                    loadDeadline?.cancel(); loadDeadline = null
                    loginAssist?.cancel()
                    status.text = "正在连接 ${id.label}…"
                    if (pageFailure.value == null) beginPageLoad()
                }
                override fun onPageCommitVisible(view: WebView, url: String?) {
                    browserScale.apply(view)
                    committedSequence = loadSequence
                    val sequence = loadSequence
                    lifecycleScope.launch { checkVisibleDocument(sequence) }
                    prefillInitialLogin()
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    browserScale.apply(view)
                    val sequence = loadSequence
                    lifecycleScope.launch { checkVisibleDocument(sequence) }
                    if (pageFailure.value == null) status.text = endpoint.origin
                    prefillInitialLogin()
                    completeModelNavigation()
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    prefillInitialLogin()
                    completeModelNavigation()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        loadDeadline?.cancel()
                        if (pageFailure.value !in setOf(WebUiFailure.CERTIFICATE, WebUiFailure.TIMEOUT)) pageFailure.value = WebUiFailure.NETWORK
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) { loadDeadline?.cancel(); pageFailure.value = WebUiFailure.HTTP }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    if (error.url == view.url || error.url == view.originalUrl) { loadDeadline?.cancel(); pageFailure.value = WebUiFailure.CERTIFICATE }
                    handler.cancel()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    this@WebUiActivity.progress.progress = value
                    this@WebUiActivity.progress.visibility = if (value == 100) android.view.View.GONE else android.view.View.VISIBLE
                }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    chooseMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.contains('/') }
                    upload.launch(if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray())
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            setDownloadListener { url, _, disposition, mime, _ ->
                if (endpoint.acceptsNavigation(url) && pendingDownload == null) {
                    pendingDownload = url
                    save.launch(URLUtil.guessFileName(url, disposition, mime).replace(Regex("[/\\\\]"), "_").take(150))
                } else if (url.startsWith("blob:") && endpoint.acceptsNavigation(url.removePrefix("blob:")) && pendingDownload == null) {
                    saveBlob(url)
                } else if (url.startsWith("data:") && pendingDownload == null) {
                    saveDataUrl(url)
                } else Toast.makeText(this@WebUiActivity, "此下载方式尚未支持，可尝试外部浏览器", Toast.LENGTH_LONG).show()
            }
        }
        browserScale.attach(webView)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "AstrBotDownload", setOf(endpoint.origin)) { _, message, origin, mainFrame, _ ->
                if (mainFrame && endpoint.acceptsNavigation(origin.toString())) {
                    val data = message.data
                    if (data != null && data.length <= 12 * 1024 * 1024) runCatching {
                        val result = JSONObject(data)
                        if (downloadNonce != null && result.optString("nonce") == downloadNonce) {
                            downloadNonce = null
                            pendingDownload = null
                            if (result.has("data")) saveDataUrl(result.getString("data"), result.optString("name"))
                            else Toast.makeText(this, "无法读取网页生成的文件，可尝试外部浏览器", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val script = assets.open("webui/blob-download.js").bufferedReader().use { it.readText() }
                .replace("__ASTRBOT_BLOB_READER_KEY__", JSONObject.quote(blobReaderKey))
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(endpoint.origin))
        }
        recoveryPanel = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by RuntimeStore.state.collectAsStateWithLifecycle()
                val failure by pageFailure.collectAsStateWithLifecycle()
                val pending by pendingAction.collectAsStateWithLifecycle()
                ManagerTheme {
                    if (webUiNeedsRecovery(id, state, endpoint.origin, failure, pending)) {
                        WebUiRecoveryPanel(id, state, endpoint.origin, failure, pending,
                            onAction = ::recoverPage, onHome = { openManager(logs = false) }, onLogs = { openManager(logs = true) })
                    }
                }
            }
        }
        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(recoveryPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        layout.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        val restored = if (savedInstanceState != null && savedInstanceState.getString("webOrigin") == endpoint.origin) webView.restoreState(savedInstanceState) else null
        reloadAfterRecovery = restored == null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(RuntimeStore.state, pageFailure, pendingAction) { state, failure, pending -> Triple(state, failure, pending) }
                    .collect { (state, failure, pending) ->
                        if (pending != null && state.completedOperation == pending) pendingAction.value = null
                        val connection = state.webUis.getValue(id)
                        val available = state.componentsReady && state.services.getValue(id).canOpenWebUi &&
                            connection.enabled && connection.endpoint?.origin == endpoint.origin
                        if (!available) reloadAfterRecovery = true
                        else if (reloadAfterRecovery && pending == null) {
                            reloadAfterRecovery = false
                            loadPage()
                        }
                        val blocked = webUiNeedsRecovery(id, state, endpoint.origin, failure, pending)
                        recoveryPanel.visibility = if (blocked) View.VISIBLE else View.GONE
                        webView.visibility = if (blocked) View.INVISIBLE else View.VISIBLE
                        if (blocked) {
                            status.text = state.services.getValue(id).summary()
                            progress.visibility = View.GONE
                        }
                    }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) { pageFailure.value = null; webView.goBack() } else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // Android can restore the browser directly after losing the process.
        lifecycleScope.launch {
            if (!RuntimeStore.state.value.environmentInstalled && RuntimeStore.owner == null) RuntimeStore.refreshInstallation(this@WebUiActivity)
            val wanted = getSharedPreferences("manager", MODE_PRIVATE).let { preferences ->
                ServiceId.entries.any { preferences.getBoolean("desired_${it.name}", false) }
            }
            if (wanted && RuntimeStore.owner == null) RuntimeService.command(this@WebUiActivity, "reconcile")
        }
    }

    private fun loadPage() {
        pageFailure.value = null
        beginNavigation()
        if (webView.url?.let(endpoint::acceptsNavigation) == true) webView.reload()
        else webView.loadUrl(endpoint.origin + if (modelSettingsEntry) AiAccess.MODEL_SETTINGS_PATH else "")
    }

    /** Invalidate the old rendered document before reload, even if no new response ever arrives. */
    private fun beginNavigation() {
        loadSequence++
        committedSequence = -1L
        loadDeadline?.cancel(); loadDeadline = null
        loginAssist?.cancel()
        beginPageLoad()
    }

    /** Upstream may discard its return URL after login. Follow only this fixed local destination. */
    private fun completeModelNavigation() {
        if (!pendingModelSettings || !endpoint.acceptsNavigation(webView.url.orEmpty())) return
        webView.evaluateJavascript("""
            (() => {
              if (location.hash.startsWith('#/auth/') || !localStorage.getItem('token')) return false;
              if (location.hash !== '#/providers') location.hash = '/providers';
              return true;
            })()
        """.trimIndent()) { result -> if (result == "true") pendingModelSettings = false }
    }

    private fun beginPageLoad() {
        if (loadDeadline?.isActive == true) return
        val sequence = loadSequence
        loadDeadline = lifecycleScope.launch {
            delay(45_000)
            if (!checkVisibleDocument(sequence) && sequence == loadSequence && pageFailure.value == null) {
                pageFailure.value = WebUiFailure.TIMEOUT
                webView.stopLoading()
            }
        }
    }

    /** A rendered login/app remains usable when an auxiliary request keeps WebView progress below 100. */
    private suspend fun checkVisibleDocument(sequence: Long): Boolean {
        if (!::webView.isInitialized || sequence != loadSequence || committedSequence != sequence) return false
        val result = CompletableDeferred<String>()
        webView.evaluateJavascript("document.readyState !== 'loading' && !!document.body && " +
            "(document.body.innerText.trim().length > 0 || !!document.querySelector('input,button,canvas,svg'))", result::complete)
        val ready = withTimeoutOrNull(2000) { result.await() }
        if (sequence == loadSequence && ready == "true" && endpoint.acceptsNavigation(webView.url.orEmpty()) &&
                pageFailure.value in setOf(null, WebUiFailure.TIMEOUT)) {
                loadDeadline?.cancel(); loadDeadline = null
                pageFailure.value = null
                status.text = endpoint.origin
                return true
        }
        return false
    }

    private fun prefillInitialLogin() {
        if (!::webView.isInitialized || !endpoint.acceptsNavigation(webView.url.orEmpty()) || loginAssist?.isActive == true) return
        val sequence = loadSequence
        loginAssist = lifecycleScope.launch {
            val credential = withContext(Dispatchers.IO) { WebUiCredentials.initialLogin(this@WebUiActivity, serviceId) } ?: return@launch
            val template = withContext(Dispatchers.IO) { assets.open("webui/login-prefill.js").bufferedReader().use { it.readText() } }
            val script = template.replace("__ORIGIN__", JSONObject.quote(endpoint.origin))
                .replace("__SERVICE__", JSONObject.quote(serviceId.name)).replace("__USERNAME__", JSONObject.quote(credential.username))
                .replace("__PASSWORD__", JSONObject.quote(credential.password))
            repeat(24) {
                if (sequence != loadSequence || !exportOriginCurrent() || !endpoint.acceptsNavigation(webView.url.orEmpty())) return@launch
                if (!withContext(Dispatchers.IO) { WebUiCredentials.isCurrent(this@WebUiActivity, serviceId, credential) }) return@launch
                val result = CompletableDeferred<String>()
                webView.evaluateJavascript(script, result::complete)
                if (withTimeoutOrNull(1000) { result.await() } == "true") return@launch
                delay(500)
            }
        }
    }

    private fun retryPage() {
        val state = RuntimeStore.state.value
        val connection = state.webUis.getValue(serviceId)
        if (state.services.getValue(serviceId).canOpenWebUi && connection.enabled && connection.endpoint?.origin == endpoint.origin && pendingAction.value == null) loadPage()
    }

    private fun recoverPage(action: WebUiRecoveryAction) {
        val state = RuntimeStore.state.value
        when (action) {
            WebUiRecoveryAction.RELOAD -> retryPage()
            WebUiRecoveryAction.REOPEN -> recreate()
            WebUiRecoveryAction.START, WebUiRecoveryAction.RESTART -> {
                if (state.busy || pendingAction.value != null || !state.componentsReady || state.webUis.getValue(serviceId).endpoint == null) return
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                runCatching {
                    pendingAction.value = RuntimeService.command(this, if (action == WebUiRecoveryAction.START) "start" else "restart", serviceId)
                }.onFailure { Toast.makeText(this, "无法提交请求，请返回首页重试", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun openManager(logs: Boolean) {
        val target = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (logs) target.putExtra("openLogService", serviceId.name)
        startActivity(target)
        finish()
    }

    private fun saveBlob(url: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(this, "当前系统 WebView 不支持此下载，请更新 WebView 或使用外部浏览器", Toast.LENGTH_LONG).show()
            return
        }
        val nonce = UUID.randomUUID().toString()
        downloadNonce = nonce
        pendingDownload = url
        val script = """
            (() => {
              const nonce = ${JSONObject.quote(nonce)};
              const send = value => AstrBotDownload.postMessage(JSON.stringify({nonce, ...value}));
              const url = ${JSONObject.quote(url)};
              const retained = window[${JSONObject.quote(blobReaderKey)}]?.(url);
              const content = retained ? Promise.resolve(retained.blob) : fetch(url).then(r => r.blob());
              content.then(blob => {
                if (blob.size > 8 * 1024 * 1024) throw new Error('size');
                const reader = new FileReader();
                reader.onload = () => send({data: reader.result, name: retained?.name || ''});
                reader.onerror = () => send({error: 'read'});
                reader.readAsDataURL(blob);
              }).catch(() => send({error: 'read'}));
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        lifecycleScope.launch {
            delay(30_000)
            if (downloadNonce == nonce) {
                downloadNonce = null; pendingDownload = null
                Toast.makeText(this@WebUiActivity, "网页文件下载超时，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Captures only the visible local page, allowing SVG/canvas QR codes to be saved too. */
    private fun saveVisiblePage() {
        if (webUiNeedsRecovery(serviceId, RuntimeStore.state.value, endpoint.origin, pageFailure.value, pendingAction.value)) return
        if (pendingDownload != null || !endpoint.acceptsNavigation(webView.url.orEmpty())) return
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) return
        pendingDownload = "page-image:"
        lifecycleScope.launch {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bytes = try {
                if (!copyVisiblePage(bitmap)) null else withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray().takeIf { it.size <= 8 * 1024 * 1024 }
                    }
                }
            } finally { bitmap.recycle() }
            if (bytes == null) {
                pendingDownload = null
                Toast.makeText(this@WebUiActivity, "页面图片未能捕获或过大，请调整显示后重试", Toast.LENGTH_LONG).show()
            } else {
                stageExport(bytes, "webui-page-${System.currentTimeMillis()}.png", image = true)
            }
        }
    }

    /** PixelCopy includes the hardware-composited SVG/canvas/transform content. */
    private suspend fun copyVisiblePage(bitmap: Bitmap): Boolean = suspendCoroutine { continuation ->
        val offset = IntArray(2)
        webView.getLocationInWindow(offset)
        val bounds = Rect(offset[0], offset[1], offset[0] + bitmap.width, offset[1] + bitmap.height)
        try {
            // Wait for the callback even if the Activity closes, then recycle the
            // destination in the caller; PixelCopy itself cannot be cancelled.
            PixelCopy.request(window, bounds, bitmap, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) { continuation.resume(false) }
    }

    private fun saveDataUrl(url: String, suggestedName: String? = null) {
        val header = url.substringBefore(',')
        val bytes = runCatching {
            check(url.length <= 12 * 1024 * 1024 && header.startsWith("data:") && header.endsWith(";base64"))
            Base64.decode(url.substringAfter(','), Base64.DEFAULT).also { check(it.size <= 8 * 1024 * 1024) }
        }.getOrNull()
        if (bytes == null) { Toast.makeText(this, "网页生成的文件需为 Base64 格式且不超过 8 MB，可尝试外部浏览器", Toast.LENGTH_LONG).show(); return }
        pendingDownload = "data:"
        val mime = header.removePrefix("data:").removeSuffix(";base64")
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val name = suggestedName?.replace(Regex("""[/\\\p{Cntrl}]"""), "_")?.take(150)?.trim()
            ?.takeIf { it.isNotEmpty() && it !in setOf(".", "..") } ?: "webui-export.$extension"
        stageExport(bytes, name, image = false)
    }

    private fun exportOriginCurrent(): Boolean {
        val connection = RuntimeStore.state.value.webUis.getValue(serviceId)
        return connection.enabled && connection.endpoint?.origin == endpoint.origin
    }

    private fun restorePendingExport(state: Bundle?) {
        val token = state?.getString("pendingWebExportToken")
        val request = state?.getString("pendingWebDownload")
        if (state?.getString("webOrigin") == endpoint.origin && exportOriginCurrent() &&
            (request?.let(endpoint::acceptsNavigation) == true || (token != null && request in setOf("data:", "page-image:")))) {
            pendingDownload = request
            pendingExportToken = token
        } else if (request != null) {
            Toast.makeText(this, "导出已中断，请重新导出", Toast.LENGTH_LONG).show()
        }
        val retainedToken = pendingExportToken
        lifecycleScope.launch(Dispatchers.IO) {
            if (token != retainedToken) exportCache.discard(token)
            exportCache.prune(retainedToken)
        }
    }

    /** Staging finishes before opening DocumentsUI; only its bounded private-cache token is saved. */
    private fun stageExport(bytes: ByteArray, name: String, image: Boolean) = lifecycleScope.launch {
        var token: String? = null
        var handedOff = false
        try {
            // Assign inside IO: cancellation on returning to Main must still be able to delete the file.
            withContext(Dispatchers.IO) { token = exportCache.stage(bytes) }
            pendingExportToken = token
            if (image) saveImage.launch(name) else save.launch(name)
            handedOff = true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            pendingDownload = null
            pendingExportToken = null
            Toast.makeText(this@WebUiActivity, "无法准备导出文件，请检查可用空间后重试", Toast.LENGTH_LONG).show()
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { exportCache.discard(token) }
        }
    }

    private fun completeExport(uri: Uri?, image: Boolean) {
        val token = pendingExportToken
        val url = pendingDownload
        pendingExportToken = null
        pendingDownload = null
        lifecycleScope.launch {
            try {
                if (uri == null) return@launch
                if (!exportOriginCurrent()) {
                    Toast.makeText(this@WebUiActivity, "后台地址已变更，请重新导出", Toast.LENGTH_LONG).show()
                } else if (token != null) {
                    val success = withContext(Dispatchers.IO) { runCatching {
                        exportCache.writeTo(token) { checkNotNull(contentResolver.openOutputStream(uri, "w")) }
                    }.isSuccess }
                    Toast.makeText(this@WebUiActivity, if (success) {
                        if (image) "页面图片已保存" else "文件已保存"
                    } else "导出内容已失效或保存失败，请重新导出", Toast.LENGTH_LONG).show()
                } else if (!image && url != null && endpoint.acceptsNavigation(url)) download(url, uri)
                else Toast.makeText(this@WebUiActivity, "导出内容已失效，请重新导出", Toast.LENGTH_LONG).show()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { exportCache.discard(token) }
            }
        }
    }

    private fun download(url: String, destination: Uri) = lifecycleScope.launch {
        if (!exportOriginCurrent() || !endpoint.acceptsNavigation(url)) {
            Toast.makeText(this@WebUiActivity, "后台地址已变更，请重新导出", Toast.LENGTH_LONG).show()
            return@launch
        }
        val cookies = CookieManager.getInstance().getCookie(url)
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).followRedirects(false).build()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).apply { if (cookies != null) header("Cookie", cookies) }.build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "下载接口返回 ${response.code}" }
                    val body = checkNotNull(response.body)
                    check(body.contentLength() <= 256L * 1024 * 1024) { "下载文件超出 256 MB 限制" }
                    contentResolver.openOutputStream(destination, "w")!!.use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count <= 256L * 1024 * 1024) { "下载文件超出 256 MB 限制" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        Toast.makeText(this@WebUiActivity, if (result.isSuccess) "文件已保存" else "下载未完成，请重试", Toast.LENGTH_LONG).show()
    }
    private fun openExternal(url: String) {
        Toast.makeText(this, "外部浏览器可能需要单独登录后台", Toast.LENGTH_SHORT).show()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        if (::endpoint.isInitialized) outState.putString("webOrigin", endpoint.origin)
        outState.putBoolean("pendingModelSettings", pendingModelSettings)
        outState.putString("pendingWebDownload", pendingDownload?.takeIf { it.length <= 16 * 1024 })
        outState.putString("pendingWebExportToken", pendingExportToken)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        if (isFinishing && pendingExportToken != null) {
            val cache = exportCache
            val token = pendingExportToken
            exportCleanup.launch { cache.discard(token) }
        }
        if (::webView.isInitialized) { webView.stopLoading(); (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }

    companion object {
        // Cleanup must survive this Activity without retaining its context or blocking Main.
        private val exportCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
