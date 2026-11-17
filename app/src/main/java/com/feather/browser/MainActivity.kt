package com.feather.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import org.json.JSONObject
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
        var pending: String? = null
        var priv = false
        var scriptHandler: ScriptHandler? = null
        var thumb: Bitmap? = null
        var group = ""
        var desktopApplied = false
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
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var findRow: LinearLayout
    private lateinit var findField: EditText
    private lateinit var findCount: TextView
    private var pendingPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var fieldColor = 0
    private lateinit var lockView: FrameLayout
    private var needLock = false
    private var skipNextLock = false
    private var unlocking = false
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
        blocker.extraEnabled = prefs.extraFilters
        blocker.youtubeGentle = prefs.ytGentle
        blocker.onLoaded = { ui.post { for (t in tabs) refreshScripts(t) } }
        blocker.start()
        needLock = prefs.appLock
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

        deletePrivateProfile()

        if (prefs.restoreTabs && !prefs.clearOnExit) {
            val (saved, idx) = prefs.loadSession()
            saved.forEachIndexed { i, (title, url, group) -> newTab(url, select = i == idx.coerceIn(0, saved.size - 1), title = title, group = group) }
        } else {
            prefs.clearSession()
        }
        val data = intent?.data
        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            newTab(data.toString())
        } else if (tabs.isEmpty()) {
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
        if (prefs.restoreTabs && !prefs.clearOnExit) {
            val open = tabs.filter { !it.priv && !isBlankUrl(it.url.ifEmpty { it.pending ?: "" }) }
            val idx = open.indexOf(current).coerceAtLeast(0)
            prefs.saveSession(open.map { Triple(tabLabel(it), it.url.ifEmpty { it.pending ?: "" }, it.group) }, idx)
        } else {
            prefs.clearSession()
        }
    }

    override fun onResume() {
        super.onResume()
        current?.web?.onResume()
    }

    override fun onStart() {
        super.onStart()
        if (skipNextLock) {
            skipNextLock = false
        } else if (prefs.appLock && needLock) {
            showLock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (prefs.appLock && !unlocking) needLock = true
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
        deletePrivateProfile()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (lockView.visibility == View.VISIBLE) {
            moveTaskToBack(true)
            return
        }
        if (customView != null) {
            hideCustomView()
            return
        }
        val tab = current
        if (findRow.visibility == View.VISIBLE) {
            hideFind()
            return
        }
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
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { exportBookmarks(it) }
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            data?.data?.let { importBookmarks(it) }
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MEDIA -> {
                pendingPermission?.let { finishMediaGrant(it) }
                pendingPermission = null
            }
            REQ_GEO -> {
                val g = pendingGeo
                pendingGeo = null
                g?.second?.invoke(g.first, grantResults.any { it == PackageManager.PERMISSION_GRANTED }, false)
            }
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
        swipe = SwipeRefreshLayout(this)
        swipe.addView(webHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        swipe.setColorSchemeColors(ACCENT)
        swipe.setOnChildScrollUpCallback { _, _ -> (current?.web?.scrollY ?: 0) > 0 }
        swipe.setOnRefreshListener {
            swipe.isRefreshing = false
            current?.web?.reload()
        }
        browser.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        findRow = LinearLayout(this)
        findRow.gravity = Gravity.CENTER_VERTICAL
        findRow.visibility = View.GONE
        findRow.setPadding(dp(10), dp(4), dp(6), dp(4))
        findField = EditText(this)
        findField.setSingleLine()
        findField.hint = "Find in page"
        findField.textSize = 15f
        findField.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) { current?.web?.clearMatches(); findCount.text = "" } else current?.web?.findAllAsync(q)
            }
        })
        findField.setOnEditorActionListener { _, _, _ -> current?.web?.findNext(true); true }
        findCount = TextView(this)
        findCount.textSize = 13f
        findCount.setPadding(dp(8), 0, dp(8), 0)
        fun findBtn(label: String, onClick: () -> Unit): TextView {
            val t = TextView(this)
            t.text = label
            t.textSize = 20f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setOnClickListener { onClick() }
            return t
        }
        findRow.addView(findField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findRow.addView(findCount)
        findRow.addView(findBtn("▲") { current?.web?.findNext(false) })
        findRow.addView(findBtn("▼") { current?.web?.findNext(true) })
        findRow.addView(findBtn("✕") { hideFind() })
        browser.addView(findRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        lockView = FrameLayout(this)
        lockView.visibility = View.GONE
        lockView.isClickable = true
        val lockBox = LinearLayout(this)
        lockBox.orientation = LinearLayout.VERTICAL
        lockBox.gravity = Gravity.CENTER
        val lockTitle = TextView(this)
        lockTitle.text = "Feather is locked"
        lockTitle.textSize = 22f
        lockTitle.gravity = Gravity.CENTER
        lockTitle.tag = "lock_title"
        val unlockBtn = android.widget.Button(this)
        unlockBtn.text = "Unlock"
        unlockBtn.setOnClickListener { authenticate() }
        lockBox.addView(lockTitle)
        lockBox.addView(unlockBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        lockView.addView(lockBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        outer.addView(lockView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        fieldColor = field
        textColor = if (night) 0xFFE8E8EC.toInt() else 0xFF1B1B1F.toInt()
        subtleColor = if (night) 0xFF8C8C96.toInt() else 0xFF6B6B75.toInt()

        browser.setBackgroundColor(bar)
        lockView.setBackgroundColor(bar)
        (lockView.findViewWithTag<TextView>("lock_title"))?.setTextColor(textColor)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        bg.setColor(field)
        urlField.background = bg
        urlField.setTextColor(textColor)
        urlField.setHintTextColor(subtleColor)
        findField.setTextColor(textColor)
        findField.setHintTextColor(subtleColor)
        findCount.setTextColor(subtleColor)
        for (i in 0 until findRow.childCount) (findRow.getChildAt(i) as? TextView)?.let { if (it !== findField) it.setTextColor(textColor) }
        findCount.setTextColor(subtleColor)
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

        val bg = urlField.background as? GradientDrawable
        bg?.setColor(if (tab.priv) (if (night) 0xFF3A2A55.toInt() else 0xFFE2D6F5.toInt()) else fieldColor)
        urlField.hint = if (tab.priv) "Private tab - search or enter address" else "Search or enter address"
        if (tab.priv || prefs.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

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

    private fun privateSupported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun newTab(url: String?, select: Boolean = true, title: String = "", private: Boolean = false, group: String = "") {
        if (private && !privateSupported()) {
            toast("Private tabs need a newer Android System WebView")
            return
        }
        val web = WebView(this)
        val tab = Tab(web)
        tab.priv = private
        tab.group = group
        configure(tab)
        tabs.add(tab)
        if (select) {
            selectTab(tab)
            if (url != null) loadIn(tab, url) else showStart(tab)
        } else if (url != null) {
            // Restored background tab: loads when you first switch to it
            tab.pending = url
            tab.url = url
            tab.title = title
        }
    }

    private fun deletePrivateProfile() {
        if (!privateSupported()) return
        if (tabs.any { it.priv }) return
        try {
            ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
        } catch (_: Exception) {
            // Still in use or never created; retried on next close/start
        }
    }

    private fun selectTab(tab: Tab) {
        current?.let { if (it !== tab) captureThumb(it) }
        current?.web?.onPause()
        webHost.removeAllViews()
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        webHost.addView(tab.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.web.onResume()
        current = tab
        if (urlField.hasFocus()) urlField.clearFocus()
        tab.pending?.let {
            tab.pending = null
            loadIn(tab, it)
        }
        hideFind()
        updateUi()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tab.web.stopLoading()
        tab.web.destroy()
        if (tab.priv) deletePrivateProfile()
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

    private fun tabLabel(t: Tab): String = (if (t.priv) "Private - " else "") + when {
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

    private fun isHttpOk(host: String) = httpOk.contains(host) || prefs.isHttpAllowed(host)

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
            if (host != null && !isLocalHost(host) && !isHttpOk(host)) {
                tab.upgradedFrom = url
                url = "https://" + url.removePrefix("http://")
            }
        }
        applySiteSettings(tab, Uri.parse(url).host)
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
        if (tab.priv) WebViewCompat.setProfile(web, PRIVATE_PROFILE)
        val s = web.settings
        if (tab.priv) s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.javaScriptEnabled = !prefs.blockJs
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.setGeolocationEnabled(true) // sites still have to ask, and you have to tap Allow
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

        val cm = if (tab.priv) {
            ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.cookieManager ?: CookieManager.getInstance()
        } else CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, false)
        web.setFindListener { ordinal, matches, _ ->
            if (tab === current) findCount.text = if (matches == 0) "0" else "${ordinal + 1}/$matches"
        }
        refreshScripts(tab)

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
                        applySiteSettings(tab, host)
                        if (prefs.httpsOnly && url.scheme == "http" && host != null &&
                            !isLocalHost(host) && !isHttpOk(host)
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
                if (blocker.shouldBlock(request.url, page)) {
                    tab.blocked.incrementAndGet()
                    totalBlocked.incrementAndGet()
                    if (!uiPending.getAndSet(true)) ui.postDelayed(blockedRunnable, 400)
                    // YouTube ad pings get a normal-looking empty answer (an error makes its player stall and retry)
                    val yt = blocker.isYoutubeAdEndpoint(request.url)
                    return WebResourceResponse(
                        "text/plain", "utf-8", if (yt) 200 else 403, if (yt) "OK" else "Blocked", emptyMap(),
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
                        .setPositiveButton("Always for this site") { _, _ ->
                            prefs.setHttpAllowed(host, true)
                            view.loadUrl(up)
                        }
                        .setNeutralButton("Just once") { _, _ ->
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
                skipNextLock = true
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

            override fun onPermissionRequest(request: PermissionRequest) {
                askMedia(request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermission === request) pendingPermission = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                askGeo(origin, callback)
            }
        }
    }

    private fun applyDark(web: WebView, on: Boolean = prefs.darkPages) {
        if (Build.VERSION.SDK_INT >= 33) {
            web.settings.isAlgorithmicDarkeningAllowed = on
        } else {
            @Suppress("DEPRECATION")
            web.settings.forceDark = if (on) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    /** Applies global + per-site JavaScript, desktop-site and dark-page settings before a page loads. */
    private fun applySiteSettings(tab: Tab, host: String?) {
        val st = tab.web.settings
        val js = when (if (host != null) prefs.siteJs(host) else 0) {
            1 -> true
            2 -> false
            else -> !prefs.blockJs
        }
        if (st.javaScriptEnabled != js) st.javaScriptEnabled = js
        val desktop = tab.desktop || (host != null && prefs.siteDesktop(host))
        if (desktop != tab.desktopApplied) {
            tab.desktopApplied = desktop
            st.userAgentString = if (desktop) desktopUa else mobileUa
            st.useWideViewPort = desktop
            st.loadWithOverviewMode = desktop
        }
        applyDark(tab.web, prefs.darkPages || (host != null && prefs.siteDark(host)))
    }

    private fun injectCosmetic(tab: Tab, view: WebView) {
        if (!prefs.blockAds || !view.settings.javaScriptEnabled) return
        val host = tab.pageHost
        if (host != null && prefs.isAllowed(host)) return
        if (isBlankUrl(tab.url) || host == null) return
        val ytHost = host == "youtube.com" || host.endsWith(".youtube.com")
        val css = if (ytHost && prefs.ytGentle) "" else blocker.domainCss(host)
        if (css.isNotEmpty()) {
            view.evaluateJavascript(
                "(function(){var s=document.getElementById('__fb_dom');if(!s){s=document.createElement('style');" +
                    "s.id='__fb_dom';(document.head||document.documentElement).appendChild(s);}s.textContent=" +
                    JSONObject.quote(css) + ";})();", null
            )
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
        }
    }

    /** (Re)installs the document-start script; call after anything that changes what it should do. */
    private fun refreshScripts(tab: Tab) {
        tab.scriptHandler?.remove()
        tab.scriptHandler = null
        if (!prefs.blockAds) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            val generic = if (prefs.extraFilters) blocker.genericCss else ""
            tab.scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                tab.web, AdBlocker.documentStartScript(prefs.allowedHosts(), generic, prefs.cookieBanners, prefs.ytGentle), setOf("*")
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- permission prompts

    private fun askMedia(request: PermissionRequest) {
        val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
        val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
        if (!cam && !mic) {
            request.deny()
            return
        }
        pendingPermission?.deny()
        pendingPermission = null
        val what = if (cam && mic) "camera and microphone" else if (cam) "camera" else "microphone"
        val site = request.origin.host ?: request.origin.toString()
        AlertDialog.Builder(this)
            .setTitle("Allow $what?")
            .setMessage("$site wants to use your $what. This only lasts while the page is open.")
            .setPositiveButton("Allow") { _, _ ->
                val need = ArrayList<String>()
                if (cam && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.CAMERA)
                if (mic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.RECORD_AUDIO)
                if (need.isEmpty()) {
                    finishMediaGrant(request)
                } else {
                    pendingPermission = request
                    requestPermissions(need.toTypedArray(), REQ_MEDIA)
                }
            }
            .setNegativeButton("Don't allow") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun finishMediaGrant(request: PermissionRequest) {
        val camOk = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val ok = request.resources.filter {
            (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && camOk) || (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && micOk)
        }
        if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
    }

    private fun askGeo(origin: String, callback: GeolocationPermissions.Callback) {
        val site = Uri.parse(origin).host ?: origin
        AlertDialog.Builder(this)
            .setTitle("Share your location?")
            .setMessage("$site wants to know where you are.")
            .setPositiveButton("Allow") { _, _ ->
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = origin to callback
                    requestPermissions(
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_GEO
                    )
                }
            }
            .setNegativeButton("Don't allow") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    // ---------------------------------------------------------------- find in page

    private fun showFind() {
        findRow.visibility = View.VISIBLE
        findField.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFind() {
        if (findRow.visibility != View.VISIBLE) return
        findRow.visibility = View.GONE
        findField.setText("")
        findCount.text = ""
        current?.web?.clearMatches()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(findField.windowToken, 0)
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
        emptyText: String? = null,
        extra: List<Pair<String, () -> Unit>> = emptyList()
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
        b.setNegativeButton("Close", null)
        extra.getOrNull(0)?.let { (label, act) -> b.setPositiveButton(label) { _, _ -> act() } }
        extra.getOrNull(1)?.let { (label, act) -> b.setNeutralButton(label) { _, _ -> act() } }
        dialog = b.create()
        dialog.show()
    }

    private fun captureThumb(tab: Tab) {
        if (tab.priv) return
        val w = tab.web.width
        val h = tab.web.height
        if (w <= 0 || h <= 0) return
        try {
            val scale = 0.25f
            val bmp = Bitmap.createBitmap(
                (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565
            )
            val c = Canvas(bmp)
            c.scale(scale, scale)
            tab.web.draw(c)
            tab.thumb = bmp
        } catch (_: Throwable) {
        }
    }

    private fun groupMenu(t: Tab, after: () -> Unit) {
        val existing = tabs.map { it.group }.filter { it.isNotEmpty() }.distinct()
        val labels = ArrayList<String>()
        for (g in existing) labels.add("Move to \"$g\"")
        labels.add("New group...")
        if (t.group.isNotEmpty()) labels.add("Remove from group")
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, which ->
            when {
                which < existing.size -> { t.group = existing[which]; after() }
                which == existing.size -> {
                    val et = EditText(this)
                    et.setSingleLine()
                    et.hint = "Group name"
                    AlertDialog.Builder(this).setTitle("New group").setView(et)
                        .setPositiveButton("Create") { _, _ -> t.group = et.text.toString().trim().take(24); after() }
                        .setNegativeButton("Cancel", null).show()
                }
                else -> { t.group = ""; after() }
            }
        }.show()
    }

    private fun showTabs() {
        current?.let { captureThumb(it) }
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(8), dp(8), dp(8))
        scroll.addView(col)
        lateinit var dialog: AlertDialog

        fun render() {
            col.removeAllViews()
            val hint = TextView(this)
            hint.text = "${tabs.size} tabs. Long-press a tab to put it in a group."
            hint.textSize = 12f
            hint.alpha = 0.6f
            hint.setPadding(dp(4), 0, dp(4), dp(4))
            col.addView(hint)
            val groups = tabs.map { it.group }.distinct().sortedWith(compareBy({ it.isNotEmpty() }, { it.lowercase() }))
            for (g in groups) {
                if (g.isNotEmpty()) {
                    val head = TextView(this)
                    head.text = g
                    head.textSize = 14f
                    head.setTypeface(head.typeface, android.graphics.Typeface.BOLD)
                    head.setTextColor(ACCENT)
                    head.setPadding(dp(4), dp(12), dp(4), dp(2))
                    col.addView(head)
                }
                val members = tabs.filter { it.group == g }
                var i = 0
                while (i < members.size) {
                    val row = LinearLayout(this)
                    for (j in 0..1) {
                        val t = members.getOrNull(i + j)
                        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                        if (t == null) { row.addView(View(this), lp); continue }
                        val card = LinearLayout(this)
                        card.orientation = LinearLayout.VERTICAL
                        val bg = GradientDrawable()
                        bg.cornerRadius = dp(10).toFloat()
                        bg.setColor(fieldColor)
                        if (t === current) bg.setStroke(dp(2), ACCENT)
                        card.background = bg
                        card.clipToOutline = true
                        val frame = FrameLayout(this)
                        val img = ImageView(this)
                        img.scaleType = ImageView.ScaleType.CENTER_CROP
                        val th = t.thumb
                        if (th != null) img.setImageBitmap(th)
                        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
                        if (th == null) {
                            val ph = TextView(this)
                            ph.text = if (t.priv) "Private" else ""
                            ph.gravity = Gravity.CENTER
                            ph.setTextColor(subtleColor)
                            frame.addView(ph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
                        }
                        val x = TextView(this)
                        x.text = "✕"
                        x.textSize = 14f
                        x.setTextColor(0xFFFFFFFF.toInt())
                        x.gravity = Gravity.CENTER
                        val xb = GradientDrawable()
                        xb.shape = GradientDrawable.OVAL
                        xb.setColor(0x99000000.toInt())
                        x.background = xb
                        x.setOnClickListener { closeTab(t); render() }
                        frame.addView(x, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                        card.addView(frame)
                        val title = TextView(this)
                        title.text = tabLabel(t)
                        title.textSize = 12f
                        title.maxLines = 2
                        title.ellipsize = android.text.TextUtils.TruncateAt.END
                        title.setPadding(dp(8), dp(6), dp(8), dp(8))
                        card.addView(title)
                        card.setOnClickListener { dialog.dismiss(); selectTab(t) }
                        card.setOnLongClickListener { groupMenu(t) { render() }; true }
                        row.addView(card, lp)
                    }
                    col.addView(row)
                    i += 2
                }
            }
        }
        render()
        dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("New tab") { _, _ -> newTab(null); focusUrlBar() }
            .setNeutralButton("Private") { _, _ -> newTab(null, private = true); if (current?.priv == true) focusUrlBar() }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
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
            emptyText = "No bookmarks yet. Use the menu to bookmark a page.",
            extra = listOf("Import" to { importPick() }, "Export" to { exportPick() })
        )
    }

    private fun showMenu() {
        val tab = current ?: return
        val host = tab.pageHost
        val hasPage = !isBlankUrl(tab.url)
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(18), dp(14), dp(28))
        val sheet = GradientDrawable()
        sheet.setColor(if (night) 0xFF1F1F26.toInt() else 0xFFFFFFFF.toInt())
        val r = dp(24).toFloat()
        sheet.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        col.background = sheet

        fun run(a: () -> Unit) { dlg.dismiss(); a() }

        fun tile(label: String, a: () -> Unit): View {
            val t = TextView(this)
            t.text = label
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.setTextColor(textColor)
            val b = GradientDrawable()
            b.cornerRadius = dp(14).toFloat()
            b.setColor(fieldColor)
            t.background = b
            t.setOnClickListener { run(a) }
            return t
        }

        fun tileRow(vararg tiles: Pair<String, () -> Unit>) {
            val row = LinearLayout(this)
            for ((label, a) in tiles) {
                val lp = LinearLayout.LayoutParams(0, dp(60), 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(tile(label, a), lp)
            }
            col.addView(row)
        }

        fun section(title: String) {
            val t = TextView(this)
            t.text = title.uppercase()
            t.textSize = 11f
            t.letterSpacing = 0.08f
            t.setTextColor(subtleColor)
            t.setPadding(dp(8), dp(16), dp(8), dp(2))
            col.addView(t)
        }

        fun row(label: String, value: String? = null, a: () -> Unit) {
            val line = LinearLayout(this)
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(dp(8), dp(13), dp(8), dp(13))
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            line.setBackgroundResource(tv.resourceId)
            val l = TextView(this)
            l.text = label
            l.textSize = 16f
            l.setTextColor(textColor)
            line.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) {
                val v = TextView(this)
                v.text = value
                v.textSize = 14f
                v.setTextColor(subtleColor)
                line.addView(v)
            }
            line.setOnClickListener { run(a) }
            col.addView(line)
        }

        tileRow(
            "New tab" to { newTab(null); focusUrlBar() },
            "Private tab" to { newTab(null, private = true); if (current?.priv == true) focusUrlBar() },
            "Bookmarks" to { showBookmarks() },
            "Downloads" to {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: ActivityNotFoundException) { toast("No downloads app found") }
            }
        )

        if (hasPage) {
            section("This page")
            row("Find in page") { showFind() }
            row("Reader view") { showReader() }
            row("Bookmark this page") {
                val l = prefs.bookmarks()
                if (l.none { it.second == tab.url }) {
                    l.add(0, tabLabel(tab) to tab.url)
                    prefs.saveBookmarks(l)
                }
                toast("Bookmarked")
            }
            row("Share link") { share(tab.url) }
            row("Copy link") { copy(tab.url) }
            row("Add to home screen") { pinShortcut(tab) }
        }

        section("View")
        row("Desktop site", if (tab.desktop) "On" else "Off") {
            tab.desktop = !tab.desktop
            applySiteSettings(tab, host)
            if (hasPage) tab.web.reload()
        }
        if (hasPage && host != null) row("Site settings", host.removePrefix("www.")) { showSiteSettings(tab) }

        section("Browser")
        row("Settings") { showSettings() }
        row("HTTP exceptions") { showHttpExceptions() }

        val scroll = ScrollView(this)
        scroll.addView(col)
        dlg.setContentView(scroll)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun toggleSite(tab: Tab, host: String) {
        prefs.setAllowed(host, !prefs.isAllowed(host))
        for (t in tabs) refreshScripts(t)
        tab.web.reload()
        updateUi()
    }

    // ---------------------------------------------------------------- bookmarks import / export

    private fun exportPick() {
        skipNextLock = true
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/html").putExtra(Intent.EXTRA_TITLE, "feather-bookmarks.html")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importPick() {
        skipNextLock = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun htmlAttr(s: String) = escapeHtml(s).replace("\"", "&quot;")

    private fun exportBookmarks(uri: Uri) {
        try {
            val list = prefs.bookmarks()
            val sb = StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
            sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
            for ((t, u) in list) sb.append("    <DT><A HREF=\"").append(htmlAttr(u)).append("\">").append(escapeHtml(t)).append("</A>\n")
            sb.append("</DL><p>\n")
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            toast("Exported ${list.size} bookmarks")
        } catch (_: Exception) {
            toast("Couldn't export bookmarks")
        }
    }

    private fun importBookmarks(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().take(5_000_000).toByteArray().toString(Charsets.UTF_8) } ?: return
            val list = prefs.bookmarks()
            val known = list.map { it.second }.toHashSet()
            val re = Regex("<A\\s[^>]*?HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            var added = 0
            for (m in re.findAll(text)) {
                val url = unescapeHtml(m.groupValues[1].trim())
                if (!(url.startsWith("http://") || url.startsWith("https://")) || !known.add(url)) continue
                val title = unescapeHtml(m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()).ifEmpty { displayUrl(url) }
                list.add(title to url)
                if (++added >= 5000) break
            }
            prefs.saveBookmarks(list)
            toast("Imported $added bookmarks")
        } catch (_: Exception) {
            toast("Couldn't import that file")
        }
    }

    private fun unescapeHtml(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    // ---------------------------------------------------------------- per-site settings, reader, shortcuts

    private fun showSiteSettings(tab: Tab) {
        val host = tab.pageHost ?: return
        val js = prefs.siteJs(host)
        val items = arrayOf(
            "JavaScript: " + when (js) {
                1 -> "always on"
                2 -> "always off"
                else -> if (prefs.blockJs) "off (default)" else "on (default)"
            },
            "Desktop site: " + if (prefs.siteDesktop(host)) "always" else "default",
            "Dark pages: " + if (prefs.siteDark(host)) "always" else "default",
            "Ad blocking: " + if (prefs.isAllowed(host)) "off for this site" else "on"
        )
        AlertDialog.Builder(this)
            .setTitle(host)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.setSite(host, "js", (js + 1) % 3)
                    1 -> prefs.setSite(host, "desktop", !prefs.siteDesktop(host))
                    2 -> prefs.setSite(host, "dark", !prefs.siteDark(host))
                    3 -> toggleSite(tab, host)
                }
                if (which != 3) {
                    applySiteSettings(tab, host)
                    tab.web.reload()
                }
                showSiteSettings(tab)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showReader() {
        val tab = current ?: return
        if (!tab.web.settings.javaScriptEnabled) {
            toast("Reader view needs JavaScript on this page")
            return
        }
        tab.web.evaluateJavascript(READER_JS) { r ->
            if (r == null || r.contains("fail")) toast("Couldn't find an article on this page")
        }
    }

    private fun pinShortcut(tab: Tab) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Your launcher can't pin shortcuts")
            return
        }
        val url = tab.url
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ACCENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 170f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val letter = (tab.pageHost?.removePrefix("www.")?.firstOrNull() ?: 'F').uppercaseChar().toString()
        c.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        val launch = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
        val info = ShortcutInfo.Builder(this, "site_" + url.hashCode())
            .setShortLabel(tabLabel(tab).take(24))
            .setIcon(Icon.createWithAdaptiveBitmap(bmp))
            .setIntent(launch)
            .build()
        sm.requestPinShortcut(info, null)
    }

    // ---------------------------------------------------------------- app lock

    private fun showLock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            prefs.appLock = false
            needLock = false
            return
        }
        lockView.visibility = View.VISIBLE
        hideKeyboard()
        authenticate()
    }

    private fun authenticate() {
        unlocking = true
        try {
            val b = BiometricPrompt.Builder(this).setTitle("Unlock Feather")
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                b.setDeviceCredentialAllowed(true)
            }
            b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocking = false
                    needLock = false
                    lockView.visibility = View.GONE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    unlocking = false
                }
            })
        } catch (e: Exception) {
            unlocking = false
            toast("Can't show the unlock screen")
        }
    }

    private fun showHttpExceptions() {
        val list = prefs.httpAllowed()
        val rows = list.map { Row(it, "Always loads over plain HTTP") }.toMutableList()
        showList(
            "HTTP exceptions", rows,
            onPick = { },
            onRemove = { idx ->
                prefs.setHttpAllowed(list[idx], false)
                list.removeAt(idx)
                rows.removeAt(idx)
            },
            emptyText = "No sites. When a site has no HTTPS you can choose \"Always for this site\" and it will show up here."
        )
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
            "Clear all data when Feather closes",
            "Restore tabs on start",
            "Extra filter lists (EasyList, EasyPrivacy, uBO)",
            "Hide and decline cookie banners",
            "Lock Feather (fingerprint or screen lock)",
            "YouTube gentle mode (fewer blocked requests, faster start)"
        )
        val checked = booleanArrayOf(
            prefs.blockAds, prefs.httpsOnly, prefs.stripTracking, prefs.blockJs, prefs.darkPages, prefs.clearOnExit,
            prefs.restoreTabs, prefs.extraFilters, prefs.cookieBanners, prefs.appLock, prefs.ytGentle
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(labels, checked) { d, which, on ->
                when (which) {
                    0 -> prefs.blockAds = on
                    1 -> prefs.httpsOnly = on
                    2 -> prefs.stripTracking = on
                    3 -> prefs.blockJs = on
                    4 -> prefs.darkPages = on
                    5 -> { prefs.clearOnExit = on; prefs.pendingClear = on }
                    6 -> prefs.restoreTabs = on
                    7 -> { prefs.extraFilters = on; blocker.extraEnabled = on }
                    8 -> prefs.cookieBanners = on
                    10 -> { prefs.ytGentle = on; blocker.youtubeGentle = on }
                    9 -> {
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (on && !km.isDeviceSecure) {
                            toast("Set a screen lock in Android settings first")
                            (d as AlertDialog).listView.setItemChecked(which, false)
                        } else {
                            prefs.appLock = on
                            needLock = false
                        }
                    }
                }
                for (t in tabs) {
                    applySiteSettings(t, t.pageHost)
                    refreshScripts(t)
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
        private val READER_JS = """
(function(){
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;');}
var best=null,bs=0,els=document.querySelectorAll('article,main,[role=main],section,div,td');
for(var i=0;i<els.length;i++){var el=els[i],s=0;
for(var c=el.firstElementChild;c;c=c.nextElementSibling){if(c.tagName==='P'){var l=(c.innerText||'').length;if(l>60)s+=l;}}
if(el.tagName==='ARTICLE')s*=1.5;if(s>bs){bs=s;best=el;}}
if(!best||bs<200)return 'fail';
var c=best.cloneNode(true);
var rm=c.querySelectorAll('script,style,nav,aside,form,iframe,button,noscript,svg,video,audio,input,select,[aria-hidden=true],[class*=share],[class*=social],[class*=comment],[class*=related],[class*=newsletter],[id*=comment]');
for(var j=0;j<rm.length;j++){if(rm[j].parentNode)rm[j].parentNode.removeChild(rm[j]);}
var im=c.querySelectorAll('img');
for(var k=0;k<im.length;k++){im[k].removeAttribute('width');im[k].removeAttribute('height');im[k].removeAttribute('style');im[k].removeAttribute('srcset');}
var h1=document.querySelector('h1');var title=(h1&&h1.innerText)||document.title;
var css=':root{color-scheme:light dark}body{max-width:680px;margin:0 auto;padding:20px 18px 60px;font:18px/1.65 Georgia,serif;background:Canvas;color:CanvasText}h1{font:700 28px/1.25 sans-serif}img,video{max-width:100%;height:auto}a{color:#4f8cff}pre{overflow:auto}';
document.head.innerHTML='<meta name="viewport" content="width=device-width,initial-scale=1"><style>'+css+'</style>';
document.body.removeAttribute('class');document.body.removeAttribute('style');
document.body.innerHTML='<h1>'+esc(title)+'</h1>'+c.innerHTML;
window.scrollTo(0,0);return 'ok';
})()
""".trimIndent()
        private const val REQ_MEDIA = 1002
        private const val REQ_EXPORT = 1004
        private const val REQ_IMPORT = 1005
        private const val REQ_GEO = 1003
        private const val PRIVATE_PROFILE = "private"
        private const val ACCENT = 0xFF4F8CFF.toInt()
        private val TRACKING_PARAMS = setOf(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
            "igshid", "yclid", "twclid", "ttclid", "li_fat_id", "_hsenc", "_hsmi", "ref_src"
        )
    }
}
