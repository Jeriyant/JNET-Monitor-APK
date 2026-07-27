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

        // Load Default URL
        val initialUrl = getDefaultUrl()
        loadUrl(initialUrl)

        // Check for updates on app startup (silent mode)
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
            userAgentString = userAgentString + " JNETMonitorApp/1.7"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebSettings(binding.webView.settings)

        // Bridge JavaScript window.print() to Android Native PrintManager
        binding.webView.addJavascriptInterface(WebPrintInterface(this, binding.webView), JS_PRINT_INTERFACE)

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
                // Subtitle under program name removed as per user request
            }

            // Handle window.open(...) popups used by Quick Print & Mikhmon / JNET-MONITOR voucher print
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)
                configureWebSettings(popupWebView.settings)

                popupWebView.addJavascriptInterface(WebPrintInterface(this@MainActivity, popupWebView), JS_PRINT_INTERFACE)

                popupWebView.webViewClient = object : WebViewClient() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                        return handleUrlLoading(url ?: "", v)
                    }

                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return handleUrlLoading(url, v)
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        injectPrintJavaScript(v)
                        
                        // Load popup print URLs into main visible WebView so user can print & see page
                        if (url != null && (url.contains("print.php") || url.contains("vpreview.php") || url.contains("quickuser.php") || url.contains("printbt.php"))) {
                            binding.webView.loadUrl(url)
                        }
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlLoading(url ?: "", view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlLoading(url, view)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.layoutError.visibility = View.GONE
                // Subtitle under program name removed as per user request
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false

                // Inject window.print() polyfill so any site's print action triggers native Android printing
                injectPrintJavaScript(view)
                
                if (url != null && (url.contains("print.php") || url.contains("vpreview.php") || url.contains("quickuser.php"))) {
                    printWebPage(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
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

    private fun handleUrlLoading(url: String, targetWebView: WebView?): Boolean {
        if (url.isEmpty()) return false

        // Standard HTTP & HTTPS -> load inside WebView
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false
        }

        // Handle Android Intent Scheme (e.g. intent://...#Intent;scheme=quickprinter;package=pe.diegoveloper.printerserverapp;end;)
        if (url.startsWith("intent://") || url.contains("scheme=quickprinter") || url.contains("package=$QUICKPRINTER_PACKAGE")) {
            try {
                var intent: Intent? = null
                try {
                    intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                } catch (e: Exception) {
                    intent = parseQuickPrinterIntentManually(url)
                }

                if (intent != null) {
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    try {
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        val pkg = intent.getPackage() ?: QUICKPRINTER_PACKAGE
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.putExtra("data", url)
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(QUICKPRINTER_PACKAGE)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        return true
                    }
                } catch (e2: Exception) {}
            }

            // Fallback to Native System Print
            printWebPage(targetWebView)
            return true
        }

        // Handle Bluetooth printer schemes (rawbt:, quickprinter:)
        if (url.startsWith("rawbt:") || url.startsWith("quickprinter:")) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            } catch (e: Exception) {
                printWebPage(targetWebView)
                true
            }
        }

        // External apps (tel:, mailto:, whatsapp:)
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun parseQuickPrinterIntentManually(url: String): Intent? {
        try {
            val prefix = "intent://"
            val suffix = "#Intent;"
            val startIndex = url.indexOf(prefix)
            val endIndex = url.lastIndexOf(suffix)

            if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                val payload = url.substring(startIndex + prefix.length, endIndex)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("quickprinter://$payload"))
                intent.setPackage(QUICKPRINTER_PACKAGE)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return intent
            }
        } catch (e: Exception) {}
        return null
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.accent)
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    // ==========================================
    // Chrome-like Printing Subsystem
    // ==========================================

    fun printWebPage(targetWebView: WebView? = binding.webView) {
        try {
            val wv = targetWebView ?: binding.webView
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "${getString(R.string.app_name)} Document_${System.currentTimeMillis()}"
            val printAdapter = wv.createPrintDocumentAdapter(jobName)
            
            val builder = PrintAttributes.Builder()
            builder.setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            builder.setResolution(PrintAttributes.Resolution("id", "pdf", 300, 300))
            builder.setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))

            printManager.print(jobName, printAdapter, builder.build())
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memulai cetak: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun injectPrintJavaScript(targetWebView: WebView? = binding.webView) {
        val js = """
            (function() {
                if (!window.print || !window.print.isNativeBridge) {
                    var origPrint = window.print;
                    window.print = function() {
                        if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.print) {
                            window.$JS_PRINT_INTERFACE.print();
                        } else if (origPrint) {
                            origPrint();
                        }
                    };
                    window.print.isNativeBridge = true;
                }
                if (!window.openPrintBridge) {
                    var origOpen = window.open;
                    window.open = function(url, target, features) {
                        if (url && (url.indexOf('print.php') !== -1 || url.indexOf('vpreview.php') !== -1 || url.indexOf('printbt.php') !== -1)) {
                            window.location.href = url;
                            return null;
                        }
                        return origOpen ? origOpen.apply(this, arguments) : null;
                    };
                    window.openPrintBridge = true;
                }
            })();
        """.trimIndent()

        targetWebView?.evaluateJavascript(js, null)
    }

    private inner class WebPrintInterface(private val activity: MainActivity, private val webView: WebView) {
        @JavascriptInterface
        fun print() {
            activity.runOnUiThread {
                activity.printWebPage(webView)
            }
        }
    }

    // ==========================================
    // 3-Dot Menu & Settings Handling
    // ==========================================

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                binding.webView.reload()
                true
            }
            R.id.action_print -> {
                printWebPage(binding.webView)
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_check_update -> {
                checkForUpdates(isManual = true)
                true
            }
            R.id.action_exit -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val currentDefaultUrl = getDefaultUrl()

        dialogBinding.etUrl.setText(currentDefaultUrl)
        dialogBinding.tvCurrentUrl.text = "URL Saat Ini: ${binding.webView.url ?: currentDefaultUrl}"

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_settings_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.btn_save), null)
            .setNeutralButton(getString(R.string.btn_reset_default), null)
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val inputUrl = dialogBinding.etUrl.text.toString().trim()
            if (TextUtils.isEmpty(inputUrl)) {
                dialogBinding.inputLayoutUrl.error = "URL tidak boleh kosong"
                return@setOnClickListener
            }

            val formattedUrl = formatUrl(inputUrl)
            saveDefaultUrl(formattedUrl)
            
            Toast.makeText(this, getString(R.string.msg_url_saved), Toast.LENGTH_SHORT).show()
            loadUrl(formattedUrl)
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
    // GitHub Releases Auto-Update Subsystem
    // ==========================================

    private fun checkForUpdates(isManual: Boolean) {
        if (isManual) {
            Toast.makeText(this, getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL(GITHUB_RELEASES_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val rawTag = json.optString("tag_name", "")
                    val releaseNotes = json.optString("body", "Versi baru JNET-Monitor telah rilis di GitHub.")
                    val releaseHtmlUrl = json.optString("html_url", "https://github.com/Jeriyant/JNET-Monitor-APK/releases")

                    var apkDownloadUrl = releaseHtmlUrl
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkDownloadUrl = asset.optString("browser_download_url", releaseHtmlUrl)
                                break
                            }
                        }
                    }

                    val latestVersion = cleanVersion(rawTag)
                    val currentVersion = getCurrentVersion()

                    runOnUiThread {
                        if (isNewerVersion(currentVersion, latestVersion)) {
                            showUpdateAvailableDialog(latestVersion, releaseNotes, apkDownloadUrl)
                        } else if (isManual) {
                            showLatestVersionDialog(currentVersion)
                        }
                    }
                } else if (isManual) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.update_error), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (isManual) {
                    runOnUiThread {
                        Toast.makeText(this, "${getString(R.string.update_error)} (${e.localizedMessage})", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName ?: "1.7.0"
        } catch (e: Exception) {
            "1.7.0"
        }
    }

    private fun cleanVersion(version: String): String {
        var v = version.trim()
        if (v.startsWith("v", ignoreCase = true)) {
            v = v.substring(1)
        }
        return v
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (latest.isEmpty()) return false
        val currParts = cleanVersion(current).split(".")
        val latestParts = cleanVersion(latest).split(".")
        
        val maxLen = maxOf(currParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val currNum = currParts.getOrNull(i)?.toIntOrNull() ?: 0
            val latestNum = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (latestNum > currNum) return true
            if (latestNum < currNum) return false
        }
        return false
    }

    private fun showUpdateAvailableDialog(latestVersion: String, releaseNotes: String, downloadUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.update_available_title)} (v$latestVersion)")
            .setMessage("Catatan Rilis:\n\n$releaseNotes")
            .setPositiveButton(getString(R.string.update_btn_download)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.update_btn_later)) { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    private fun showLatestVersionDialog(currentVersion: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_latest_title))
            .setMessage("${getString(R.string.update_latest_msg)}\nVersi saat ini: v$currentVersion")
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    // ==========================================
    // Helpers & URL Management
    // ==========================================

    private fun getDefaultUrl(): String {
        return prefs.getString(KEY_DEFAULT_URL, FALLBACK_URL) ?: FALLBACK_URL
    }

    private fun saveDefaultUrl(url: String) {
        prefs.edit().putString(KEY_DEFAULT_URL, url).apply()
    }

    private fun loadUrl(url: String) {
        val validUrl = formatUrl(url)
        binding.webView.loadUrl(validUrl)
    }

    private fun formatUrl(url: String): String {
        var cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }
        return cleanUrl
    }

    private fun String?.isNull_or_empty_or_url(url: String?): Boolean {
        return this.isNullOrEmpty() || this == url || this.startsWith("http://") || this.startsWith("https://")
    }
}
