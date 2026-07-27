package com.jnet.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jnet.monitor.databinding.ActivityMainBinding
import com.jnet.monitor.databinding.DialogSettingsBinding
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "JNetMonitorPrefs"
        private const val KEY_DEFAULT_URL = "default_url"
        private const val FALLBACK_URL = "https://jeriyant.my.id"
        private const val JS_PRINT_INTERFACE = "AndroidPrintInterface"
        private const val GITHUB_RELEASES_API = "https://api.github.com/repos/Jeriyant/JNET-Monitor-APK/releases/latest"
        private const val QUICKPRINTER_PACKAGE = "pe.diegoveloper.printerserverapp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupToolbar()
        setupWebView()
        setupSwipeRefresh()
        setupBackPressedHandler()

        val initialUrl = getDefaultUrl()
        loadUrl(initialUrl)

        checkForUpdates(isManual = false)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(webSettings: WebSettings) {
        with(webSettings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString + " JNETMonitorApp/1.8"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebSettings(binding.webView.settings)
        binding.webView.addJavascriptInterface(
            WebAppInterface(this, binding.webView), JS_PRINT_INTERFACE
        )

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Subtitle removed as per user request
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)
                configureWebSettings(popupWebView.settings)
                popupWebView.addJavascriptInterface(
                    WebAppInterface(this@MainActivity, popupWebView), JS_PRINT_INTERFACE
                )

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return dispatchUrl(url, v)
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        injectBridgeScript(v)
                        if (isPrintPage(url)) printWebPage(v)
                    }
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) = super.onCloseWindow(window)
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return dispatchUrl(url, view)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.layoutError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false
                injectBridgeScript(view)
                if (isPrintPage(url)) printWebPage(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.layoutError.visibility = View.VISIBLE
                    binding.btnRetry.setOnClickListener {
                        binding.layoutError.visibility = View.GONE
                        binding.webView.reload()
                    }
                }
            }
        }
    }

    private fun isPrintPage(url: String?) =
        url != null && (url.contains("print.php") || url.contains("vpreview.php") || url.contains("quickuser.php"))

    // ==========================================
    // JS Bridge Script — the KEY fix for Quick Print
    // ==========================================

    private fun injectBridgeScript(targetWebView: WebView? = binding.webView) {
        // Language: JavaScript
        // This script does THREE things:
        //
        // 1. Override window.print() → calls native AndroidPrintInterface.print()
        //
        // 2. CRITICAL: Intercept window.location.href setter via Object.defineProperty
        //    sendToQuickPrinterChrome() in printbt.php does:
        //       window.location.href = "intent://...#Intent;scheme=quickprinter;..."
        //    Android WebView's shouldOverrideUrlLoading() is NEVER called for JS property assignment,
        //    only for real navigations. So we must intercept here in JS and hand off to native bridge.
        //
        // 3. Inject X-Requested-With header into all jQuery AJAX requests
        //    quickuser.php checks HTTP_X_REQUESTED_WITH to decide whether to send BT print script.
        //    Android WebView does NOT send this header automatically, causing server to always redirect.

        val js = """
(function() {
    // ─── 1. Override window.print() ───────────────────────────────────────────
    if (!window.print || !window.print._jnetBridge) {
        var _origPrint = window.print;
        window.print = function() {
            if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.nativePrint) {
                window.$JS_PRINT_INTERFACE.nativePrint();
            } else if (_origPrint) {
                _origPrint.call(window);
            }
        };
        window.print._jnetBridge = true;
    }

    // ─── 2. Intercept window.location.href setter ────────────────────────────
    // sendToQuickPrinterChrome() in printbt.php does:
    //   window.location.href = "intent://...#Intent;scheme=quickprinter;..."
    // We intercept this assignment and route to native Android bridge.
    if (!window._jnetLocationPatched) {
        try {
            var _locProto = window.location;
            var _origDescriptor = Object.getOwnPropertyDescriptor(window.location.__proto__, 'href')
                                  || Object.getOwnPropertyDescriptor(window.location, 'href');

            if (_origDescriptor && _origDescriptor.set) {
                var _origSetter = _origDescriptor.set;
                Object.defineProperty(window.location, 'href', {
                    configurable: true,
                    get: _origDescriptor.get ? _origDescriptor.get.bind(window.location) : function() { return _locProto.href; },
                    set: function(url) {
                        if (url && (
                            url.indexOf('intent://') === 0 ||
                            url.indexOf('quickprinter:') === 0 ||
                            url.indexOf('rawbt:') === 0
                        )) {
                            // Route intent URL to native Android bridge
                            if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.sendIntent) {
                                window.$JS_PRINT_INTERFACE.sendIntent(url);
                                return;
                            }
                        }
                        _origSetter.call(window.location, url);
                    }
                });
                window._jnetLocationPatched = true;
            }
        } catch(e) {
            // Fallback: can't patch, intent:// will be handled by shouldOverrideUrlLoading
        }
    }

    // ─── 3. Add X-Requested-With header to jQuery AJAX ───────────────────────
    // quickuser.php reads HTTP_X_REQUESTED_WITH to detect AJAX vs direct nav.
    // Android WebView does NOT send this header, so server always redirects instead
    // of returning the Bluetooth print script. We patch jQuery $.ajaxSetup if available.
    if (window.jQuery) {
        window.jQuery.ajaxSetup({
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        });
    } else {
        // Retry after DOM ready in case jQuery hasn't loaded yet
        document.addEventListener('DOMContentLoaded', function() {
            if (window.jQuery) {
                window.jQuery.ajaxSetup({
                    headers: { 'X-Requested-With': 'XMLHttpRequest' }
                });
            }
        });
    }
})();
        """.trimIndent()

        targetWebView?.evaluateJavascript(js, null)
    }

    // ==========================================
    // URL Dispatcher — for real navigations
    // ==========================================

    fun dispatchUrl(url: String, targetWebView: WebView?): Boolean {
        if (url.isEmpty()) return false

        // HTTP/HTTPS stays inside WebView
        if (url.startsWith("http://") || url.startsWith("https://")) return false

        // Delegate to intent launcher for custom schemes
        return launchIntent(url, targetWebView)
    }

    fun launchIntent(url: String, targetWebView: WebView?): Boolean {
        if (url.startsWith("intent://") || url.contains("scheme=quickprinter") || url.contains("package=$QUICKPRINTER_PACKAGE")) {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } catch (e: Exception) {
                // App not installed → try open Play Store
                val pkg = try {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getPackage()
                } catch (e2: Exception) { QUICKPRINTER_PACKAGE }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e3: Exception) {
                    printWebPage(targetWebView)
                }
                true
            }
        }

        if (url.startsWith("rawbt:") || url.startsWith("quickprinter:")) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                printWebPage(targetWebView)
                true
            }
        }

        // External apps (tel:, mailto:, whatsapp:, etc.)
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ==========================================
    // Native Print (WebView → PrintManager)
    // ==========================================

    fun printWebPage(targetWebView: WebView? = binding.webView) {
        try {
            val wv = targetWebView ?: binding.webView
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "${getString(R.string.app_name)}_${System.currentTimeMillis()}"
            val printAdapter = wv.createPrintDocumentAdapter(jobName)
            val attr = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("id", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                .build()
            printManager.print(jobName, printAdapter, attr)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal cetak: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // ==========================================
    // JavaScript Interface Bridge
    // ==========================================

    inner class WebAppInterface(
        private val activity: MainActivity,
        private val webView: WebView
    ) {
        /** Called by window.print() polyfill */
        @JavascriptInterface
        fun nativePrint() {
            activity.runOnUiThread {
                activity.printWebPage(webView)
            }
        }

        /**
         * Called by our window.location.href intercept when JS assigns an intent:// URL.
         * This is the KEY fix — shouldOverrideUrlLoading never fires for JS property
         * assignment, so we intercept at the JS level and hand off here on the UI thread.
         */
        @JavascriptInterface
        fun sendIntent(url: String) {
            activity.runOnUiThread {
                activity.launchIntent(url, webView)
            }
        }
    }

    // ==========================================
    // Setup helpers
    // ==========================================

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.accent)
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })
    }

    // ==========================================
    // 3-Dot Menu
    // ==========================================

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> { binding.webView.reload(); true }
            R.id.action_print -> { printWebPage(binding.webView); true }
            R.id.action_settings -> { showSettingsDialog(); true }
            R.id.action_check_update -> { checkForUpdates(isManual = true); true }
            R.id.action_exit -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val currentUrl = getDefaultUrl()

        dialogBinding.etUrl.setText(currentUrl)
        dialogBinding.tvCurrentUrl.text = "URL Saat Ini: ${binding.webView.url ?: currentUrl}"

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_settings_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.btn_save), null)
            .setNeutralButton(getString(R.string.btn_reset_default), null)
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialogBinding.etUrl.text.toString().trim()
            if (TextUtils.isEmpty(input)) {
                dialogBinding.inputLayoutUrl.error = "URL tidak boleh kosong"
                return@setOnClickListener
            }
            val formatted = formatUrl(input)
            saveDefaultUrl(formatted)
            Toast.makeText(this, getString(R.string.msg_url_saved), Toast.LENGTH_SHORT).show()
            loadUrl(formatted)
            dialog.dismiss()
        }

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            saveDefaultUrl(FALLBACK_URL)
            Toast.makeText(this, "URL direset ke $FALLBACK_URL", Toast.LENGTH_SHORT).show()
            loadUrl(FALLBACK_URL)
            dialog.dismiss()
        }
    }

    // ==========================================
    // Auto-Update (GitHub Releases)
    // ==========================================

    private fun checkForUpdates(isManual: Boolean) {
        if (isManual) Toast.makeText(this, getString(R.string.update_checking), Toast.LENGTH_SHORT).show()

        Executors.newSingleThreadExecutor().execute {
            try {
                val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val rawTag = json.optString("tag_name", "")
                    val notes = json.optString("body", "Versi baru tersedia.")
                    val htmlUrl = json.optString("html_url", "https://github.com/Jeriyant/JNET-Monitor-APK/releases")

                    var apkUrl = htmlUrl
                    json.optJSONArray("assets")?.let { assets ->
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apkUrl = a.optString("browser_download_url", htmlUrl)
                                break
                            }
                        }
                    }

                    val latest = cleanVersion(rawTag)
                    val current = getCurrentVersion()

                    runOnUiThread {
                        if (isNewerVersion(current, latest)) showUpdateDialog(latest, notes, apkUrl)
                        else if (isManual) showLatestDialog(current)
                    }
                } else if (isManual) {
                    runOnUiThread { Toast.makeText(this, getString(R.string.update_error), Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                if (isManual) runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.update_error)} (${e.localizedMessage})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getCurrentVersion() = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.8.0"
    } catch (e: Exception) { "1.8.0" }

    private fun cleanVersion(v: String) = v.trim().trimStart('v', 'V')

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (latest.isEmpty()) return false
        val c = cleanVersion(current).split(".").map { it.toIntOrNull() ?: 0 }
        val l = cleanVersion(latest).split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, l.size)) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    private fun showUpdateDialog(ver: String, notes: String, url: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.update_available_title)} (v$ver)")
            .setMessage("Catatan Rilis:\n\n$notes")
            .setPositiveButton(getString(R.string.update_btn_download)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(getString(R.string.update_btn_later)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showLatestDialog(ver: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_latest_title))
            .setMessage("${getString(R.string.update_latest_msg)}\nVersi saat ini: v$ver")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    // ==========================================
    // URL Helpers
    // ==========================================

    private fun getDefaultUrl() = prefs.getString(KEY_DEFAULT_URL, FALLBACK_URL) ?: FALLBACK_URL
    private fun saveDefaultUrl(url: String) = prefs.edit().putString(KEY_DEFAULT_URL, url).apply()
    private fun loadUrl(url: String) = binding.webView.loadUrl(formatUrl(url))
    private fun formatUrl(url: String): String {
        val c = url.trim()
        return if (c.startsWith("http://") || c.startsWith("https://")) c else "https://$c"
    }
}
