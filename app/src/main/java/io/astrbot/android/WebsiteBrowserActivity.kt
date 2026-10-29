package io.astrbot.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback

/** User-selected HTTP(S) pages. No runtime credentials or native message bridge. */
class WebsiteBrowserActivity : LocalizedActivity() {
    private lateinit var browser: WebView
    private lateinit var scale: BrowserScale
    private lateinit var address: TextView
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val harness = intent.getBooleanExtra("harness",false)
        val bookmark = (if (harness) io.astrbot.android.runtime.HarnessStore.entryUrl?.let { WebsiteBookmark("extension-harness","DeepSeek Harness",it) }
            else WebsiteBookmarks(this).read().firstOrNull { it.id == intent.getStringExtra("bookmarkId") })
            ?: run { finish(); return }
        scale = BrowserScale(this, "website-${bookmark.id}")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun button(label: String, icon: Int, action: () -> Unit) {
            toolbar.addView(ImageButton(this).apply {
                contentDescription = label; setImageResource(icon)
                val typed = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
                setBackgroundResource(typed.resourceId)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        button(AppText.get(R.string.ui_back),R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() }
        address = TextView(this).apply {
            text = if (harness) bookmark.url.substringBefore('?').substringBefore('#') else bookmark.url; textSize = 12f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(address,LinearLayout.LayoutParams(0,-2,1f))
        button(AppText.get(R.string.ui_refresh),R.drawable.ic_restart) { error.visibility = View.GONE; browser.reload() }
        button(AppText.get(R.string.ui_page_zoom),R.drawable.ic_more) { scale.choose(browser) }
        layout.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal)
        layout.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        error = TextView(this).apply { text = AppText.get(R.string.websites_load_failed); visibility = View.GONE; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        layout.addView(error)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = runCatching { WebsiteBookmarks.normalize(request.url.toString()) }.isSuccess
                    if (!allowed && request.isForMainFrame) Toast.makeText(this@WebsiteBrowserActivity,AppText.get(R.string.websites_only_http),Toast.LENGTH_SHORT).show()
                    return !allowed
                }
                override fun onPageStarted(view: WebView,url: String?,favicon: Bitmap?) {
                    error.visibility = View.GONE
                    if (url != null) address.text = if (harness) url.substringBefore('?').substringBefore('#') else url
                }
                override fun onPageFinished(view: WebView,url: String?) { this@WebsiteBrowserActivity.scale.apply(view) }
                override fun onPageCommitVisible(view: WebView,url: String?) { this@WebsiteBrowserActivity.scale.apply(view) }
                override fun onReceivedError(view: WebView,request: WebResourceRequest,failure: WebResourceError) {
                    if (request.isForMainFrame) error.visibility = View.VISIBLE
                }
                override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,response: WebResourceResponse) {
                    if (request.isForMainFrame) error.visibility = View.VISIBLE
                }
                override fun onReceivedSslError(view: WebView,handler: SslErrorHandler,failure: android.net.http.SslError) {
                    handler.cancel(); error.visibility = View.VISIBLE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView,value: Int) {
                    this@WebsiteBrowserActivity.progress.progress = value
                    this@WebsiteBrowserActivity.progress.visibility = if (value < 100) View.VISIBLE else View.GONE
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
        }
        scale.attach(browser)
        layout.addView(browser,LinearLayout.LayoutParams(-1,0,1f)); setContentView(layout)
        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) browser.loadUrl(bookmark.url)
        onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (browser.canGoBack()) browser.goBack() else finish() }
        })
    }
    override fun onSaveInstanceState(outState: Bundle) { browser.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart(); if (::browser.isInitialized) browser.onResume() }
    override fun onStop() { if (::browser.isInitialized) browser.onPause(); super.onStop() }
    override fun onDestroy() {
        if (::browser.isInitialized) {
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.stopLoading(); browser.destroy()
        }
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
