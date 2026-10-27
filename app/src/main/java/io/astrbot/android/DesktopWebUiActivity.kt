package io.astrbot.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.runtime.DesktopRepository
import io.astrbot.android.runtime.DesktopStore
import io.astrbot.android.runtime.RuntimeService
import kotlinx.coroutines.*

/** Local noVNC client. Credentials stay out of URLs, browser storage and logs. */
class DesktopWebUiActivity : LocalizedActivity() {
    private lateinit var webView: WebView
    private lateinit var scale: BrowserScale
    private lateinit var progress: ProgressBar
    private lateinit var recovery: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: Button
    private val endpoint = LocalEndpoint(6081)
    private var deadline: Job? = null
    private var loading = false
    private var loaded = false
    private var failure = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scale = BrowserScale(this, "desktop")
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground = android.graphics.Color.parseColor(if (dark) "#E9EBF1" else "#202536")
        val background = android.graphics.Color.parseColor(if (dark) "#1B1F28" else "#FFFFFF")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(background)
        }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun icon(label: String, drawable: Int, action: (ImageButton) -> Unit) = ImageButton(this).apply {
            contentDescription = label; setImageResource(drawable)
            imageTintList = android.content.res.ColorStateList.valueOf(foreground)
            val attribute = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attribute, true)
            setBackgroundResource(attribute.resourceId); setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { action(this) }
            toolbar.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        icon(AppText.get(R.string.ui_back), R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply {
            text = AppText.get(R.string.desktop_name); textSize = 16f; setTextColor(foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        heading.addView(TextView(this).apply { text = endpoint.origin; textSize = 11f; setTextColor(foreground) })
        toolbar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        icon(AppText.get(R.string.ui_refresh), R.drawable.ic_restart) { reload() }
        icon(AppText.get(R.string.ui_more_actions), R.drawable.ic_more) { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, AppText.get(R.string.ui_page_zoom_b29fc, scale.percent))
                menu.add(0, 2, 1, AppText.get(R.string.ui_open_in_browser))
                setOnMenuItemClickListener {
                    if (it.itemId == 1) scale.choose(webView) else openExternal(DesktopRepository.WEB_UI_URL)
                    true
                }
                show()
            }
        }
        layout.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        val content = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (endpoint.acceptsNavigation(request.url.toString())) return false
                    if (request.isForMainFrame && request.hasGesture()) openExternal(request.url.toString())
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && !endpoint.acceptsNavigation(url)) return
                    failure = false; loading = true
                    deadline?.cancel()
                    deadline = lifecycleScope.launch {
                        delay(45000)
                        if (loading && !loaded) showFailure()
                    }
                }
                override fun onPageCommitVisible(view: WebView, url: String?) { committed(view) }
                override fun onPageFinished(view: WebView, url: String?) { committed(view) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showFailure()
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) showFailure()
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); showFailure()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    this@DesktopWebUiActivity.progress.progress = value
                    this@DesktopWebUiActivity.progress.visibility = if (value < 100 && !failure) View.VISIBLE else View.GONE
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
        }
        scale.attach(webView)
        content.addView(webView, FrameLayout.LayoutParams(-1, -1))
        recovery = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(background)
        }
        message = TextView(this).apply { setTextColor(foreground); gravity = android.view.Gravity.CENTER; textSize = 15f }
        recovery.addView(message, LinearLayout.LayoutParams(-1, -2))
        retry = Button(this).apply {
            setOnClickListener {
                if (!DesktopStore.state.value.running) RuntimeService.command(this@DesktopWebUiActivity, "desktop-start")
                else reload()
            }
        }
        recovery.addView(retry, LinearLayout.LayoutParams(-2, -2))
        content.addView(recovery, FrameLayout.LayoutParams(-1, -1))
        layout.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        savedInstanceState?.getBundle("browser")?.let { loaded = webView.restoreState(it) != null }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recovery.visibility != View.VISIBLE && webView.canGoBack()) webView.goBack() else finish()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DesktopStore.state.collect { state ->
                    if (!state.running) { loaded = false; loading = false; webView.stopLoading() }
                    if (state.webUiReady && !loaded && !loading && !failure) reload()
                    updateRecovery()
                }
            }
        }
        updateRecovery()
    }

    private fun committed(view: WebView) {
        if (failure || !endpoint.acceptsNavigation(view.url.orEmpty())) return
        loaded = true; loading = false; deadline?.cancel()
        scale.apply(view); updateRecovery()
        val password = runCatching { DesktopRepository(this).password() }.getOrNull() ?: return
        view.evaluateJavascript("""
            (() => {
                if (window.__managerDesktopPassword) clearInterval(window.__managerDesktopPassword);
                const fill = () => {
                    const input = document.getElementById('noVNC_password_input');
                    const button = document.getElementById('noVNC_credentials_button');
                    const dialog = document.getElementById('noVNC_credentials_dlg');
                    if (!input || !button || !dialog?.classList.contains('noVNC_open')) return;
                    clearInterval(window.__managerDesktopPassword);
                    input.value = ${org.json.JSONObject.quote(password)};
                    input.dispatchEvent(new Event('input', {bubbles: true}));
                    button.click();
                };
                window.__managerDesktopPassword = setInterval(fill, 250);
                fill();
                setTimeout(() => clearInterval(window.__managerDesktopPassword), 60000);
            })();
        """.trimIndent(), null)
    }

    private fun reload() {
        deadline?.cancel(); failure = false; loaded = false; loading = false
        if (DesktopStore.state.value.webUiReady) {
            loading = true
            webView.loadUrl(webView.url?.takeIf(endpoint::acceptsNavigation) ?: DesktopRepository.WEB_UI_URL)
        }
        updateRecovery()
    }

    private fun showFailure() {
        failure = true; loading = false; deadline?.cancel(); progress.visibility = View.GONE
        updateRecovery()
    }

    private fun updateRecovery() {
        val state = DesktopStore.state.value
        recovery.visibility = if (state.running && state.webUiReady && !failure && (loaded || loading)) View.GONE else View.VISIBLE
        message.text = AppText.get(when {
            !state.configuration.installed -> R.string.desktop_install_first
            !state.running -> R.string.ui_stopped
            failure || state.error != null -> R.string.desktop_unavailable
            else -> R.string.cpolar_connecting
        })
        retry.text = AppText.get(if (!state.running) R.string.desktop_start else R.string.ui_refresh)
        retry.isEnabled = state.configuration.installed && !state.busy && !state.paused
    }

    private fun openExternal(url: String) {
        if (Uri.parse(url).scheme !in setOf("http", "https")) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("browser", Bundle().also { webView.saveState(it) })
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { CookieManager.getInstance().flush(); webView.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() {
        deadline?.cancel(); webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy()
        super.onDestroy()
    }
}
