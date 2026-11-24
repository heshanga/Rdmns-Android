package xyz.hesn.rdmns

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE = "https://rdmns.hesn.xyz"
        private const val OFFLINE = "file:///android_asset/offline.html"

        /** Hosts that open inside the app. Sub-domains match too. Everything else opens externally. */
        private val INTERNAL_HOSTS = listOf("rdmns.hesn.xyz", "hesn.xyz", "rdmns.lk")
    }

    private data class Tab(val menuId: Int, val home: String, val pathPrefix: String?)

    private val tabs = listOf(
        Tab(R.id.nav_home, "$BASE/", null),
        Tab(R.id.nav_map, "$BASE/v1map", "/v1map"),
        Tab(R.id.nav_radar, "$BASE/v1radar", "/v1radar"),
        Tab(R.id.nav_timetable, "$BASE/v1timetable", "/v1timetable"),
    )

    private val webViews = arrayOfNulls<WebView>(4)
    private val failedUrls = arrayOfNulls<String>(4)
    private var current = 0
    private var pendingUrl: String? = null
    private var firstPageDone = false
    private var lastBackPress = 0L

    private lateinit var root: View
    private lateinit var container: FrameLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var bottomNav: BottomNavigationView

    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            geoCallback?.invoke(geoOrigin, granted, granted)
            geoCallback = null
            geoOrigin = null
            if (!granted) toast(R.string.location_denied)
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileCallback = null
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        container = findViewById(R.id.container)
        progress = findViewById(R.id.progress)
        bottomNav = findViewById(R.id.bottom_nav)

        // Keep the splash until the first page has painted (max 3 s).
        splash.setKeepOnScreenCondition { !firstPageDone }
        root.postDelayed({ firstPageDone = true }, 3000)

        CookieManager.getInstance().setAcceptCookie(true)
        applyInsets()

        // Restore after rotation / theme change, otherwise open a deep link or Home.
        if (savedInstanceState != null) {
            restoreTabs(savedInstanceState)
        } else {
            val link = intent?.data?.takeIf { isInternal(it) }?.toString()
            val start = link?.let { tabFor(it) } ?: 0
            bottomNav.selectedItemId = tabs[start].menuId
            showTab(start, link)
        }

        bottomNav.setOnItemSelectedListener { item ->
            val i = tabs.indexOfFirst { it.menuId == item.itemId }
            bottomNav.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showTab(i, pendingUrl.also { pendingUrl = null })
            true
        }
        bottomNav.setOnItemReselectedListener { onTabReselected() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        if (isInternal(uri)) openInTab(uri.toString())
    }

    override fun onStart() {
        super.onStart()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Connection is back: retry every tab that is showing the offline page.
                runOnUiThread {
                    webViews.forEachIndexed { i, w -> if (w?.url == OFFLINE) retry(i) }
                }
            }
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) { }
        }
        networkCallback = null
    }

    override fun onResume() {
        super.onResume()
        webViews[current]?.let { it.onResume(); it.resumeTimers() }
    }

    override fun onPause() {
        webViews.forEach { it?.onPause() }
        webViews.firstOrNull { it != null }?.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
        webViews.forEachIndexed { i, w ->
            if (w != null) outState.putBundle("wv$i", Bundle().also { w.saveState(it) })
        }
    }

    override fun onDestroy() {
        webViews.forEach { w ->
            w?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Hide the tab bar while typing so the page gets the full height.
            bottomNav.isVisible = !imeVisible
            findViewById<View>(R.id.divider).isVisible = !imeVisible
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else 0)
            insets
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun tabFor(url: String): Int {
        val path = Uri.parse(url).path ?: return 0
        val i = tabs.indexOfFirst { t -> t.pathPrefix != null && path.startsWith(t.pathPrefix) }
        return if (i >= 0) i else 0
    }

    private fun openInTab(url: String) {
        val i = tabFor(url)
        if (i == current) {
            showTab(i, url)
        } else {
            pendingUrl = url
            bottomNav.selectedItemId = tabs[i].menuId
        }
    }

    private fun showTab(i: Int, url: String? = null) {
        current = i
        var wv = webViews[i]
        if (wv == null) {
            wv = createWebView(i)
            webViews[i] = wv
            container.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
            wv.loadUrl(url ?: tabs[i].home)
        } else if (url != null) {
            wv.loadUrl(url)
        }
        webViews.forEachIndexed { j, w ->
            if (w == null) return@forEachIndexed
            if (j == i) { w.visibility = View.VISIBLE; w.onResume() } else { w.visibility = View.GONE; w.onPause() }
        }
        updateProgress(wv.progress)
    }

    private fun restoreTabs(state: Bundle) {
        val tab = state.getInt("tab", 0)
        for (i in tabs.indices) {
            val b = state.getBundle("wv$i") ?: continue
            val wv = createWebView(i)
            webViews[i] = wv
            container.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
            if (wv.restoreState(b) == null) wv.loadUrl(tabs[i].home)
        }
        firstPageDone = true
        bottomNav.selectedItemId = tabs[tab].menuId
        showTab(tab)
    }

    /** Tapping the active tab: go back to that tab's start page, or reload if already there. */
    private fun onTabReselected() {
        val wv = webViews[current] ?: return showTab(current)
        val home = tabs[current].home
        when {
            wv.url == OFFLINE -> retry(current)
            wv.url?.trimEnd('/') != home.trimEnd('/') -> wv.loadUrl(home)
            else -> wv.reload()
        }
    }

    private fun handleBack() {
        val wv = webViews[current]
        if (wv != null && goBackSkippingOffline(wv)) return
        if (current != 0) {
            bottomNav.selectedItemId = tabs[0].menuId
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress < 2000) finish() else {
            lastBackPress = now
            toast(R.string.press_back_again)
        }
    }

    private fun goBackSkippingOffline(wv: WebView): Boolean {
        val list = wv.copyBackForwardList()
        var index = list.currentIndex - 1
        var steps = -1
        while (index >= 0 && list.getItemAtIndex(index).url == OFFLINE) { index--; steps-- }
        if (index < 0) return false
        wv.goBackOrForward(steps)
        return true
    }

    private fun retry(i: Int) {
        val wv = webViews[i] ?: return
        wv.loadUrl(failedUrls[i] ?: tabs[i].home)
    }

    private fun updateProgress(p: Int) {
        if (p in 1..99) {
            if (!progress.isVisible) progress.show()
            progress.setProgressCompat(p, true)
        } else {
            progress.hide()
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(i: Int): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100 // keep the design intact when the phone uses a large font size
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // target=_blank opens in the same view
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString RDMNSApp/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = Client(i)
        wv.webChromeClient = Chrome(i)
        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        return wv
    }

    private inner class Client(private val idx: Int) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(view, idx, request.url)

        override fun onPageFinished(view: WebView, url: String?) {
            firstPageDone = true
            if (url != null && url != OFFLINE && url != "about:blank") failedUrls[idx] = null
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                failedUrls[idx] = request.url.toString()
                view.loadUrl(OFFLINE)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Don't crash the whole app; rebuild this tab.
            container.removeView(view)
            view.destroy()
            webViews[idx] = null
            if (idx == current) showTab(idx)
            return true
        }
    }

    private inner class Chrome(private val idx: Int) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (idx == current) updateProgress(newProgress)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!isInternal(Uri.parse(origin))) {
                callback.invoke(origin, false, false)
                return
            }
            if (hasLocationPermission()) {
                callback.invoke(origin, true, true)
            } else {
                geoOrigin = origin
                geoCallback = callback
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                filePicker.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    private fun handleUrl(view: WebView, idx: Int, uri: Uri): Boolean {
        when (uri.scheme?.lowercase()) {
            "rdmns" -> {
                if (uri.host == "retry") retry(idx)
                return true
            }
            "http", "https" -> {
                if (isInternal(uri)) return false
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        intent.getStringExtra("browser_fallback_url")?.let {
                            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        }
                    }
                } catch (_: Exception) { }
                return true
            }
            "about", "data", "blob", "javascript", "file" -> return false
            else -> {
                // tel:, mailto:, geo:, whatsapp:, viber: ...
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
        }
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_app)
        }
    }

    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !URLUtil.isNetworkUrl(url)) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return
        }
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", ua)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast(R.string.downloading)
        } catch (_: Exception) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
