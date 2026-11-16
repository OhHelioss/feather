package com.feather.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {

    private class Tab(val web: WebView) {
        var title = ""
        var url = ""
        @Volatile var pageHost: String? = null
        val blocked = AtomicInteger(0)
        var desktop = false
        var upgradedFrom: String? = null
        var progress = 100
    }

    private class IconBtn(val frame: FrameLayout, val img: ImageView, val badge: TextView)

    private lateinit var prefs: Prefs
    private lateinit var blocker: AdBlocker

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private val httpOk = HashSet<String>()
    private val totalBlocked = AtomicLong(0)
    private val uiPending = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private val blockedRunnable = Runnable { uiPending.set(false); updateUi() }

    private lateinit var outer: FrameLayout
    private lateinit var browser: LinearLayout
    private lateinit var webHost: FrameLayout
    private lateinit var fullscreenFrame: FrameLayout
    private lateinit var urlField: EditText
    private lateinit var urlRow: LinearLayout
    private lateinit var navRow: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var backBtn: IconBtn
    private lateinit var fwdBtn: IconBtn
    private lateinit var reloadBtn: IconBtn
    private lateinit var shieldBtn: IconBtn
    private lateinit var tabsBtn: IconBtn
    private lateinit var menuBtn: IconBtn

    private lateinit var mobileUa: String
    private lateinit var desktopUa: String

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private var night = false
    private var textColor = 0
    private var subtleColor = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        blocker = AdBlocker(this)
        totalBlocked.set(prefs.totalBlocked)

        // A previous session that was killed before it could wipe its data gets wiped now.
        if (prefs.clearOnExit && prefs.pendingClear) wipeData()
        if (prefs.clearOnExit) prefs.pendingClear = true

        val def = WebSettings.getDefaultUserAgent(this)
        val major = Regex("Chrome/(\\d+)").find(def)?.groupValues?.get(1) ?: "120"
        val ver = "$major.0.0.0"
        mobileUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Mobile Safari/537.36"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"

        buildUi()
        applyTheme()

        val data = intent?.data
        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            newTab(data.toString())
        } else {
            newTab(null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        if (data != null && (data.scheme == "http" || data.scheme == "https")) newTab(data.toString())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        current?.web?.onPause()
        prefs.totalBlocked = totalBlocked.get()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.clearOnExit) {
            wipeData()
            prefs.pendingClear = false
        }
        for (t in tabs) {
            t.web.stopLoading()
            t.web.destroy()
        }
        tabs.clear()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        val tab = current
        if (urlField.hasFocus()) {
            urlField.clearFocus()
            return
        }
        if (tab != null && tab.web.canGoBack()) {
            tab.web.goBack()
        } else if (tab != null && tabs.size > 1) {
            closeTab(tab)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeBtn(iconRes: Int, desc: String, badgeCentered: Boolean, onClick: () -> Unit): IconBtn {
        val frame = FrameLayout(this)
        frame.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        frame.contentDescription = desc
        frame.isClickable = true
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        frame.setBackgroundResource(tv.resourceId)
        frame.setOnClickListener { onClick() }

        val img = ImageView(this)
        img.setImageResource(iconRes)
        img.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        frame.addView(img)

        val badge = TextView(this)
        badge.textSize = if (badgeCentered) 10f else 9f
        badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
        badge.gravity = Gravity.CENTER
        if (badgeCentered) {
            badge.layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        } else {
            badge.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        frame.addView(badge)
        return IconBtn(frame, img, badge)
    }

    private fun buildUi() {
        outer = FrameLayout(this)
        browser = LinearLayout(this)
        browser.orientation = LinearLayout.VERTICAL
        webHost = FrameLayout(this)
        browser.addView(webHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.visibility = View.GONE
        progressBar.progressTintList = ColorStateList.valueOf(ACCENT)
        browser.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        urlField = EditText(this)
        urlField.setSingleLine()
        urlField.hint = "Search or enter address"
        urlField.textSize = 15f
        urlField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlField.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        urlField.setSelectAllOnFocus(true)
        urlField.setPadding(dp(16), 0, dp(16), 0)
        urlField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitUrl()
                true
            } else false
        }
        urlField.setOnFocusChangeListener { v, hasFocus ->
            val tab = current
            if (hasFocus) {
                urlField.setText(tab?.url?.takeUnless { isBlankUrl(it) } ?: "")
                v.post { urlField.selectAll() }
            } else {
                hideKeyboard()
                updateUi()
            }
        }

        urlRow = LinearLayout(this)
        urlRow.setPadding(dp(10), dp(6), dp(10), dp(2))
        urlRow.addView(urlField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        browser.addView(urlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        backBtn = makeBtn(R.drawable.ic_back, "Back", false) { current?.web?.let { if (it.canGoBack()) it.goBack() } }
        fwdBtn = makeBtn(R.drawable.ic_forward, "Forward", false) { current?.web?.let { if (it.canGoForward()) it.goForward() } }
        reloadBtn = makeBtn(R.drawable.ic_reload, "Reload", false) {
            val t = current
            if (t != null) { if (t.progress < 100) t.web.stopLoading() else t.web.reload() }
        }
        shieldBtn = makeBtn(R.drawable.ic_shield, "Blocking", false) { showShieldDialog() }
        tabsBtn = makeBtn(R.drawable.ic_tabs, "Tabs", true) { showTabs() }
        menuBtn = makeBtn(R.drawable.ic_menu, "Menu", false) { showMenu() }

        navRow = LinearLayout(this)
        for (b in listOf(backBtn, fwdBtn, reloadBtn, shieldBtn, tabsBtn, menuBtn)) navRow.addView(b.frame)
        browser.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        fullscreenFrame = FrameLayout(this)
        fullscreenFrame.setBackgroundColor(0xFF000000.toInt())
        fullscreenFrame.visibility = View.GONE

        outer.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        outer.addView(fullscreenFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(outer)

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        browser.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun applyTheme() {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bar = if (night) 0xFF17171B.toInt() else 0xFFF4F4F7.toInt()
        val field = if (night) 0xFF2A2A31.toInt() else 0xFFE4E4EA.toInt()
        textColor = if (night) 0xFFE8E8EC.toInt() else 0xFF1B1B1F.toInt()
        subtleColor = if (night) 0xFF8C8C96.toInt() else 0xFF6B6B75.toInt()

        browser.setBackgroundColor(bar)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        bg.setColor(field)
        urlField.background = bg
        urlField.setTextColor(textColor)
        urlField.setHintTextColor(subtleColor)
        val tint = ColorStateList.valueOf(textColor)
        for (b in listOf(backBtn, fwdBtn, reloadBtn, shieldBtn, tabsBtn, menuBtn)) b.img.imageTintList = tint
        tabsBtn.badge.setTextColor(textColor)   // count sits inside the tabs outline
        shieldBtn.badge.setTextColor(bar)       // count sits on the filled shield
    }

    private fun isBlankUrl(u: String) = u.isEmpty() || u == "about:blank" || u.startsWith("data:")

    private fun displayUrl(u: String): String = when {
        isBlankUrl(u) -> ""
        u.startsWith("https://") -> u.removePrefix("https://").removeSuffix("/")
        else -> u
    }

    private fun updateUi() {
        val tab = current ?: return
        if (!urlField.hasFocus()) urlField.setText(displayUrl(tab.url))
        // Show the lock state with colour: red-ish text for plain HTTP
        urlField.setTextColor(if (tab.url.startsWith("http://")) 0xFFE5534B.toInt() else textColor)

        progressBar.visibility = if (tab.progress in 0..99) View.VISIBLE else View.GONE
        progressBar.progress = tab.progress

        setEnabledLook(backBtn, tab.web.canGoBack())
        setEnabledLook(fwdBtn, tab.web.canGoForward())
        reloadBtn.img.setImageResource(if (tab.progress < 100) R.drawable.ic_close else R.drawable.ic_reload)

        tabsBtn.badge.text = tabs.size.toString()
        val host = tab.pageHost
        val active = prefs.blockAds && !(host != null && prefs.isAllowed(host))
        shieldBtn.img.alpha = if (active) 1f else 0.35f
        val n = tab.blocked.get()
        shieldBtn.badge.text = if (active && n > 0) (if (n > 99) "99+" else n.toString()) else ""
    }

    private fun setEnabledLook(b: IconBtn, enabled: Boolean) {
        b.frame.isEnabled = enabled
        b.img.alpha = if (enabled) 1f else 0.3f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    private fun focusUrlBar() {
        urlField.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- tabs

    private fun newTab(url: String?, select: Boolean = true) {
        val web = WebView(this)
        val tab = Tab(web)
        configure(tab)
        tabs.add(tab)
        if (select) selectTab(tab)
        if (url != null) loadIn(tab, url) else showStart(tab)
    }

    private fun selectTab(tab: Tab) {
        current?.web?.onPause()
        webHost.removeAllViews()
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        webHost.addView(tab.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.web.onResume()
        current = tab
        if (urlField.hasFocus()) urlField.clearFocus()
        updateUi()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tab.web.stopLoading()
        tab.web.destroy()
        if (tabs.isEmpty()) {
            current = null
            newTab(null)
        } else if (current === tab) {
            current = null
            selectTab(tabs[minOf(idx, tabs.size - 1)])
        } else {
            updateUi()
        }
    }

    private fun tabLabel(t: Tab): String = when {
        t.title.isNotBlank() -> t.title
        !isBlankUrl(t.url) -> displayUrl(t.url)
        else -> "New tab"
    }

    // ---------------------------------------------------------------- navigation helpers

    private fun resolveInput(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))) return "https://$t"
        return Prefs.ENGINES[prefs.engine].format(URLEncoder.encode(t, "UTF-8"))
    }

    private fun submitUrl() {
        val tab = current ?: return
        val url = resolveInput(urlField.text.toString()) ?: return
        urlField.clearFocus()
        loadIn(tab, url)
        tab.web.requestFocus()
    }

    private fun isLocalHost(h: String?): Boolean {
        if (h == null) return false
        return h == "localhost" || h.endsWith(".local") || h == "127.0.0.1" || h == "::1" || h == "[::1]" ||
            h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)
    }

    private fun cleanUrl(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.isHierarchical || uri.encodedQuery == null) return url
        val names = uri.queryParameterNames
        if (names.none { isTrackingParam(it) }) return url
        val b = uri.buildUpon().clearQuery()
        for (n in names) {
            if (isTrackingParam(n)) continue
            for (v in uri.getQueryParameters(n)) b.appendQueryParameter(n, v)
        }
        return b.build().toString()
    }

    private fun isTrackingParam(n: String): Boolean {
        val k = n.lowercase()
        return k.startsWith("utm_") || k in TRACKING_PARAMS
    }

    /** Applies tracking-param stripping and the HTTPS upgrade, then loads. */
    private fun loadIn(tab: Tab, url0: String) {
        var url = url0
        if (prefs.stripTracking) url = cleanUrl(url)
        tab.upgradedFrom = null
        if (prefs.httpsOnly && url.startsWith("http://")) {
            val host = Uri.parse(url).host
            if (host != null && !isLocalHost(host) && !httpOk.contains(host)) {
                tab.upgradedFrom = url
                url = "https://" + url.removePrefix("http://")
            }
        }
        tab.web.loadUrl(url, mapOf("Sec-GPC" to "1", "DNT" to "1"))
    }

    private fun showStart(tab: Tab) {
        tab.title = ""
        tab.url = ""
        tab.pageHost = null
        tab.blocked.set(0)
        val n = totalBlocked.get()
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            :root{color-scheme:light dark}
            body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;
                 font-family:sans-serif;background:#f4f4f7;color:#1b1b1f}
            h1{font-size:34px;margin:0 0 6px;letter-spacing:.5px}
            p{margin:4px 0;color:#6b6b75;font-size:15px}
            b{color:#4f8cff}
            @media(prefers-color-scheme:dark){body{background:#17171b;color:#e8e8ec}p{color:#8c8c96}}
            </style></head><body>
            <h1>Feather</h1>
            <p>Light. Private. Quiet.</p>
            <p><b>$n</b> ads &amp; trackers blocked so far</p>
            </body></html>
        """.trimIndent()
        tab.web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun errorPage(title: String, detail: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>:root{color-scheme:light dark}
        body{font-family:sans-serif;margin:0;padding:48px 28px;background:#f4f4f7;color:#1b1b1f}
        h2{margin:0 0 12px}p{color:#6b6b75;line-height:1.5;word-break:break-word}
        @media(prefers-color-scheme:dark){body{background:#17171b;color:#e8e8ec}p{color:#8c8c96}}</style>
        </head><body><h2>$title</h2><p>$detail</p></body></html>
    """.trimIndent()

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------------------------------------------------------------- WebView setup

    private fun configure(tab: Tab) {
        val web = tab.web
        val s = web.settings
        s.javaScriptEnabled = !prefs.blockJs
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.safeBrowsingEnabled = true
        s.userAgentString = mobileUa
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        applyDark(web)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, false)

        web.setDownloadListener { url, ua, cd, mime, _ -> confirmDownload(url, ua, cd, mime) }
        web.setOnLongClickListener { onWebLongPress(web) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return when (url.scheme?.lowercase()) {
                    "http", "https" -> {
                        if (!request.isForMainFrame) return false
                        val str = url.toString()
                        if (prefs.stripTracking) {
                            val cleaned = cleanUrl(str)
                            if (cleaned != str) {
                                loadIn(tab, cleaned)
                                return true
                            }
                        }
                        val host = url.host
                        if (prefs.httpsOnly && url.scheme == "http" && host != null &&
                            !isLocalHost(host) && !httpOk.contains(host)
                        ) {
                            tab.upgradedFrom = str
                            view.loadUrl("https://" + str.removePrefix("http://"))
                            return true
                        }
                        false
                    }
                    "mailto", "tel", "sms" -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) {}
                        true
                    }
                    else -> true // block intent:, file:, javascript:, custom app schemes
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                if (request.isForMainFrame) {
                    tab.pageHost = host
                    return null
                }
                if (!prefs.blockAds) return null
                val page = tab.pageHost
                if (page != null && prefs.isAllowed(page)) return null
                if (blocker.isBlocked(host)) {
                    tab.blocked.incrementAndGet()
                    totalBlocked.incrementAndGet()
                    if (!uiPending.getAndSet(true)) ui.postDelayed(blockedRunnable, 400)
                    return WebResourceResponse(
                        "text/plain", "utf-8", 403, "Blocked", emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.blocked.set(0)
                tab.url = if (isBlankUrl(url)) "" else url
                tab.pageHost = if (isBlankUrl(url)) null else Uri.parse(url).host
                injectCosmetic(tab, view)
                if (tab === current) updateUi()
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectCosmetic(tab, view)
                if (tab === current) updateUi()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (error.errorCode == WebViewClient.ERROR_UNKNOWN) return // e.g. navigation aborted
                val failing = request.url.toString()
                val up = tab.upgradedFrom
                if (up != null && request.url.scheme == "https" && Uri.parse(up).host == request.url.host) {
                    tab.upgradedFrom = null
                    val host = request.url.host ?: ""
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No secure connection")
                        .setMessage("$host doesn't support HTTPS. Load it over plain HTTP? Anyone on the network could read or change what you see.")
                        .setPositiveButton("Load over HTTP") { _, _ ->
                            httpOk.add(host)
                            view.loadUrl(up)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                view.loadDataWithBaseURL(
                    null,
                    errorPage("Can't open this page", escapeHtml(error.description?.toString() ?: "Unknown error") +
                        "<br>" + escapeHtml(failing)),
                    "text/html", "utf-8", failing
                )
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                view.loadDataWithBaseURL(
                    null,
                    errorPage(
                        "Connection isn't private",
                        "The certificate for this site can't be trusted, so Feather blocked it.<br>" + escapeHtml(error.url ?: "")
                    ),
                    "text/html", "utf-8", error.url
                )
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (tab === current) updateUi()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title ?: ""
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            // Camera, mic and location are always denied: nothing in Feather ever prompts for them.
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
    }

    private fun applyDark(web: WebView) {
        val on = prefs.darkPages
        if (Build.VERSION.SDK_INT >= 33) {
            web.settings.isAlgorithmicDarkeningAllowed = on
        } else {
            @Suppress("DEPRECATION")
            web.settings.forceDark = if (on) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    private fun injectCosmetic(tab: Tab, view: WebView) {
        if (!prefs.blockAds || prefs.blockJs) return
        val host = tab.pageHost
        if (host != null && prefs.isAllowed(host)) return
        if (isBlankUrl(tab.url)) return
        view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
    }

    // ---------------------------------------------------------------- fullscreen video

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreenFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenFrame.visibility = View.VISIBLE
        browser.visibility = View.GONE
        setSystemBars(false)
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreenFrame.removeView(v)
        fullscreenFrame.visibility = View.GONE
        browser.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        setSystemBars(true)
    }

    private fun setSystemBars(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (visible) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (visible) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    // ---------------------------------------------------------------- downloads / link actions

    private fun confirmDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("This kind of download isn't supported")
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage(name + "\n\nFrom " + (Uri.parse(url).host ?: url))
            .setPositiveButton("Download") { _, _ ->
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                    if (ua != null) req.addRequestHeader("User-Agent", ua)
                    CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                    if (mime != null) req.setMimeType(mime)
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    toast("Downloading $name")
                } catch (e: Exception) {
                    toast("Couldn't start download")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onWebLongPress(web: WebView): Boolean {
        val r = web.hitTestResult
        val link: String? = when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> r.extra
            else -> null
        }
        if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) return false
        AlertDialog.Builder(this)
            .setTitle(displayUrl(link))
            .setItems(arrayOf("Open in new tab", "Copy link", "Share link")) { _, which ->
                when (which) {
                    0 -> newTab(link)
                    1 -> copy(link)
                    2 -> share(link)
                }
            }
            .show()
        return true
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Copied")
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share link"))
    }

    // ---------------------------------------------------------------- dialogs

    private class Row(val title: String, val subtitle: String?)

    private fun showList(
        title: String,
        rows: MutableList<Row>,
        onPick: (Int) -> Unit,
        onRemove: (Int) -> Unit,
        emptyText: String? = null
    ) {
        val lv = ListView(this)
        lateinit var dialog: AlertDialog
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                val line = LinearLayout(this@MainActivity)
                line.gravity = Gravity.CENTER_VERTICAL
                line.setPadding(dp(20), dp(10), dp(8), dp(10))
                val col = LinearLayout(this@MainActivity)
                col.orientation = LinearLayout.VERTICAL
                val t = TextView(this@MainActivity)
                t.text = row.title
                t.textSize = 16f
                t.maxLines = 1
                t.ellipsize = android.text.TextUtils.TruncateAt.END
                col.addView(t)
                if (row.subtitle != null) {
                    val sub = TextView(this@MainActivity)
                    sub.text = row.subtitle
                    sub.textSize = 12f
                    sub.alpha = 0.6f
                    sub.maxLines = 1
                    sub.ellipsize = android.text.TextUtils.TruncateAt.END
                    col.addView(sub)
                }
                line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val x = TextView(this@MainActivity)
                x.text = "✕"
                x.textSize = 16f
                x.gravity = Gravity.CENTER
                x.setPadding(dp(14), dp(8), dp(14), dp(8))
                x.setOnClickListener {
                    onRemove(position)
                    notifyDataSetChanged()
                    if (rows.isEmpty()) dialog.dismiss()
                }
                line.addView(x)
                return line
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            onPick(pos)
        }
        val b = AlertDialog.Builder(this).setTitle(title)
        if (rows.isEmpty() && emptyText != null) b.setMessage(emptyText) else b.setView(lv)
        dialog = b.setNegativeButton("Close", null).create()
        dialog.show()
    }

    private fun showTabs() {
        val rows = tabs.map { Row(tabLabel(it), if (isBlankUrl(it.url)) null else displayUrl(it.url)) }.toMutableList()
        showList(
            "Tabs (${tabs.size})", rows,
            onPick = { selectTab(tabs[it]) },
            onRemove = { idx ->
                rows.removeAt(idx)
                closeTab(tabs[idx])
            }
        )
    }

    private fun showBookmarks() {
        val list = prefs.bookmarks()
        val rows = list.map { Row(it.first, displayUrl(it.second)) }.toMutableList()
        showList(
            "Bookmarks", rows,
            onPick = { current?.let { t -> loadIn(t, list[it].second) } },
            onRemove = { idx ->
                list.removeAt(idx)
                rows.removeAt(idx)
                prefs.saveBookmarks(list)
            },
            emptyText = "No bookmarks yet. Use the menu to bookmark a page."
        )
    }

    private fun showMenu() {
        val tab = current ?: return
        val host = tab.pageHost
        val siteOn = host != null && !prefs.isAllowed(host)
        val hasPage = !isBlankUrl(tab.url)
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        items.add("New tab"); actions.add { newTab(null); focusUrlBar() }
        if (hasPage) {
            items.add("Bookmark this page"); actions.add {
                val l = prefs.bookmarks()
                if (l.none { it.second == tab.url }) {
                    l.add(0, tabLabel(tab) to tab.url)
                    prefs.saveBookmarks(l)
                }
                toast("Bookmarked")
            }
        }
        items.add("Bookmarks"); actions.add { showBookmarks() }
        if (hasPage) {
            items.add("Share link"); actions.add { share(tab.url) }
            items.add("Copy link"); actions.add { copy(tab.url) }
        }
        items.add(if (tab.desktop) "Mobile site" else "Desktop site"); actions.add {
            tab.desktop = !tab.desktop
            tab.web.settings.userAgentString = if (tab.desktop) desktopUa else mobileUa
            tab.web.settings.useWideViewPort = tab.desktop
            tab.web.settings.loadWithOverviewMode = tab.desktop
            if (hasPage) tab.web.reload()
        }
        if (hasPage && host != null) {
            items.add(if (siteOn) "Turn off ad blocking for this site" else "Turn on ad blocking for this site")
            actions.add { toggleSite(tab, host) }
        }
        items.add("Settings"); actions.add { showSettings() }

        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun toggleSite(tab: Tab, host: String) {
        prefs.setAllowed(host, !prefs.isAllowed(host))
        tab.web.reload()
        updateUi()
    }

    private fun showShieldDialog() {
        val tab = current ?: return
        val host = tab.pageHost
        val b = AlertDialog.Builder(this).setTitle("Ads & trackers")
        if (!prefs.blockAds) {
            b.setMessage("Blocking is turned off in Settings.")
                .setPositiveButton("Settings") { _, _ -> showSettings() }
        } else if (host == null) {
            b.setMessage("${totalBlocked.get()} blocked in total.")
        } else {
            val on = !prefs.isAllowed(host)
            b.setMessage(
                (if (on) "Blocked ${tab.blocked.get()} requests on this page." else "Blocking is off for $host.") +
                    "\n${totalBlocked.get()} blocked in total."
            ).setPositiveButton(if (on) "Turn off for this site" else "Turn on for this site") { _, _ -> toggleSite(tab, host) }
        }
        b.setNegativeButton("Close", null).show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Block ads & trackers",
            "HTTPS-only mode",
            "Strip tracking parameters from links",
            "Block JavaScript (strict, breaks many sites)",
            "Dark pages (when system is dark)",
            "Clear all data when Feather closes"
        )
        val checked = booleanArrayOf(
            prefs.blockAds, prefs.httpsOnly, prefs.stripTracking, prefs.blockJs, prefs.darkPages, prefs.clearOnExit
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                when (which) {
                    0 -> prefs.blockAds = on
                    1 -> prefs.httpsOnly = on
                    2 -> prefs.stripTracking = on
                    3 -> prefs.blockJs = on
                    4 -> prefs.darkPages = on
                    5 -> { prefs.clearOnExit = on; prefs.pendingClear = on }
                }
                for (t in tabs) {
                    t.web.settings.javaScriptEnabled = !prefs.blockJs
                    applyDark(t.web)
                }
                updateUi()
            }
            .setPositiveButton("Done", null)
            .setNeutralButton("Search: " + Prefs.ENGINE_NAMES[prefs.engine]) { _, _ -> pickEngine() }
            .setNegativeButton("Clear data") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Clear browsing data?")
                    .setMessage("Cookies, cache, site storage and saved logins will be removed.")
                    .setPositiveButton("Clear") { _, _ -> wipeData(); toast("Cleared") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun pickEngine() {
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(Prefs.ENGINE_NAMES, prefs.engine) { d, which ->
                prefs.engine = which
                d.dismiss()
            }
            .show()
    }

    private fun wipeData() {
        for (t in tabs) {
            t.web.clearCache(true)
            t.web.clearHistory()
        }
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()
        @Suppress("DEPRECATION")
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
    }

    companion object {
        private const val REQ_FILE = 1001
        private const val ACCENT = 0xFF4F8CFF.toInt()
        private val TRACKING_PARAMS = setOf(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
            "igshid", "yclid", "twclid", "ttclid", "li_fat_id", "_hsenc", "_hsmi", "ref_src"
        )
    }
}
