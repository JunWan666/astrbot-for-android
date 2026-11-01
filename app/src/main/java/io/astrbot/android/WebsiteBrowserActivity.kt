package io.astrbot.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.astrbot.android.runtime.SnowLumaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** User pages have no credential access; the explicit local SnowLuma entry can prefill its initial login. */
class WebsiteBrowserActivity : LocalizedActivity() {
    private lateinit var browser: WebView
    private lateinit var scale: BrowserScale
    private lateinit var address: TextView
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private lateinit var content: FrameLayout
    private val pages = mutableListOf<WebView>()
    private val gestures = WebsiteGestureWindow()
    private var allowAppLinks = false
    private var hideQuery = false
    private var navigation = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val harness = intent.getBooleanExtra("harness",false)
        val snowluma = intent.getBooleanExtra("snowluma", false)
        allowAppLinks = !harness && !snowluma
        hideQuery = harness
        val bookmark = (if (snowluma) WebsiteBookmark("extension-snowluma", "SnowLuma", io.astrbot.android.runtime.SnowLumaRepository.WEB_UI_URL)
            else if (harness) io.astrbot.android.runtime.HarnessStore.entryUrl?.let { WebsiteBookmark("extension-harness","DeepSeek Harness",it) }
            else WebsiteBookmarks(this).read().firstOrNull { it.id == intent.getStringExtra("bookmarkId") })
            ?: run { finish(); return }
        scale = BrowserScale(this, "website-${bookmark.id}")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun button(label: String, icon: Int, action: (View) -> Unit) {
            toolbar.addView(ImageButton(this).apply {
                contentDescription = label; setImageResource(icon)
                val typed = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
                setBackgroundResource(typed.resourceId)
                setOnClickListener { action(it) }
            }, LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        button(AppText.get(R.string.ui_back),R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() }
        address = TextView(this).apply {
            text = if (harness) bookmark.url.substringBefore('?').substringBefore('#') else bookmark.url; textSize = 12f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(address,LinearLayout.LayoutParams(0,-2,1f))
        button(AppText.get(R.string.ui_refresh),R.drawable.ic_restart) { error.visibility = View.GONE; browser.reload() }
        button(AppText.get(R.string.ui_more_actions),R.drawable.ic_more) { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, AppText.get(R.string.ui_page_zoom))
                menu.add(0, 2, 1, AppText.get(R.string.websites_external_browser))
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) scale.choose(browser) else openExternalBrowser()
                    true
                }
                show()
            }
        }
        layout.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal)
        layout.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        error = TextView(this).apply { text = AppText.get(R.string.websites_load_failed); visibility = View.GONE; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        layout.addView(error)
        content = FrameLayout(this)
        browser = createBrowser()
        pages += browser
        scale.attach(browser)
        content.addView(browser, FrameLayout.LayoutParams(-1, -1))
        layout.addView(content,LinearLayout.LayoutParams(-1,0,1f)); setContentView(layout)
        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) browser.loadUrl(bookmark.url)
        if (snowluma) observeInitialLogin()
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browser.canGoBack()) browser.goBack() else if (pages.size > 1) closeWindow(browser) else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createBrowser(): WebView = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(allowAppLinks)
            settings.javaScriptCanOpenWindowsAutomatically = allowAppLinks
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            setOnTouchListener { view, event ->
                if (view === browser && event.actionMasked == MotionEvent.ACTION_UP) gestures.record()
                false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = runCatching { WebsiteBookmarks.normalize(request.url.toString()) }.isSuccess
                    if (allowed) return false
                    val target = if (allowAppLinks) WebsiteAppLinks.parse(request.url.toString()) else null
                    if (target != null && view === browser && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        (request.hasGesture() || gestures.recent())) {
                        gestures.consume()
                        try { startActivity(target.intent) }
                        catch (_: ActivityNotFoundException) { notice(AppText.get(R.string.websites_app_missing, AppText.get(target.name))) }
                        catch (_: SecurityException) { notice(AppText.get(R.string.websites_app_failed, AppText.get(target.name))) }
                    } else if (request.isForMainFrame && view === browser) {
                        notice(AppText.get(if (target == null) R.string.websites_link_unsupported else R.string.websites_tap_to_open))
                    }
                    return true
                }
                override fun onPageStarted(view: WebView,url: String?,favicon: Bitmap?) {
                    if (view !== browser) return
                    navigation++
                    error.visibility = View.GONE
                    if (url != null) address.text = displayUrl(url)
                }
                override fun onPageFinished(view: WebView,url: String?) { this@WebsiteBrowserActivity.scale.apply(view) }
                override fun onPageCommitVisible(view: WebView,url: String?) { this@WebsiteBrowserActivity.scale.apply(view) }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,failure: WebResourceError) {
                    if (request.isForMainFrame && view === browser) error.visibility = View.VISIBLE
                }
                override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,response: WebResourceResponse) {
                    if (request.isForMainFrame && view === browser) error.visibility = View.VISIBLE
                }
                override fun onReceivedSslError(view: WebView,handler: SslErrorHandler,failure: android.net.http.SslError) {
                    handler.cancel(); if (view === browser) error.visibility = View.VISIBLE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView,value: Int) {
                    if (view !== browser) return
                    this@WebsiteBrowserActivity.progress.progress = value
                    this@WebsiteBrowserActivity.progress.visibility = if (value < 100) View.VISIBLE else View.GONE
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    if (!allowAppLinks || view !== browser || pages.size >= 4 ||
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || (!isUserGesture && !gestures.recent())) return false
                    if (isUserGesture) gestures.record()
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val child = createBrowser()
                    pages += child
                    browser = child
                    this@WebsiteBrowserActivity.scale.attach(child)
                    content.removeAllViews()
                    content.addView(child, FrameLayout.LayoutParams(-1, -1))
                    address.text = AppText.get(R.string.websites_opening_window)
                    error.visibility = View.GONE
                    this@WebsiteBrowserActivity.progress.visibility = View.VISIBLE
                    transport.webView = child
                    resultMsg.sendToTarget()
                    return true
                }
                override fun onCloseWindow(window: WebView) { closeWindow(window) }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
        }

    private fun closeWindow(window: WebView) {
        if (pages.size <= 1 || window === pages.first() || !pages.remove(window)) return
        if (window === browser) {
            content.removeAllViews()
            browser = pages.last()
            content.addView(browser, FrameLayout.LayoutParams(-1, -1))
            address.text = browser.url?.let(::displayUrl).orEmpty()
            error.visibility = View.GONE
            progress.visibility = if (browser.progress < 100) View.VISIBLE else View.GONE
            progress.progress = browser.progress
            scale.apply(browser)
        }
        window.stopLoading(); window.destroy()
    }

    private fun displayUrl(url: String) = if (hideQuery) url.substringBefore('?').substringBefore('#') else url
    private fun notice(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun openExternalBrowser() {
        val url = browser.url?.takeIf { runCatching { WebsiteBookmarks.normalize(it) }.isSuccess }
            ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { notice(AppText.get(R.string.websites_browser_missing)) }
    }
    private fun observeInitialLogin() {
        val template = assets.open("webui/snowluma-login-prefill.js").bufferedReader().use { it.readText() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(SnowLumaStore.initialCredentials, SnowLumaStore.state) { credentials, state ->
                    credentials.takeIf { state.active }
                }.collectLatest { credentials ->
                    if (credentials == null) return@collectLatest
                    val script = SnowLumaLogin.script(template, credentials)
                    var completedNavigation = -1L
                    while (isActive) {
                        val currentNavigation = navigation
                        if (completedNavigation != currentNavigation && SnowLumaLogin.accepts(browser.url) &&
                            SnowLumaStore.initialCredentials.value === credentials && SnowLumaStore.state.value.active) {
                            val result = CompletableDeferred<String>()
                            browser.evaluateJavascript(script, result::complete)
                            if (withTimeoutOrNull(1500) { result.await() } in setOf("\"filled\"", "\"occupied\"")) {
                                completedNavigation = currentNavigation
                            }
                        }
                        delay(1000)
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { browser.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart(); pages.forEach { it.onResume() } }
    override fun onStop() { pages.forEach { it.onPause() }; super.onStop() }
    override fun onDestroy() {
        pages.forEach {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading(); it.destroy()
        }
        pages.clear()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
