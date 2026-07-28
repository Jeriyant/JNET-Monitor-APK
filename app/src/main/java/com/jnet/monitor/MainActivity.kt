package com.jnet.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jnet.monitor.databinding.ActivityMainBinding
import com.jnet.monitor.databinding.DialogAboutBinding
import com.jnet.monitor.databinding.DialogPrinterDriverBinding
import com.jnet.monitor.databinding.DialogSettingsBinding
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "JNetMonitorPrefs"
        private const val KEY_DEFAULT_URL = "default_url"
        private const val KEY_HISTORY_JSON = "browser_history_json"
        private const val FALLBACK_URL = "https://jeriyant.my.id"
        private const val JS_PRINT_INTERFACE = "AndroidPrintInterface"
        private const val GITHUB_RELEASES_API = "https://api.github.com/repos/Jeriyant/JNET-Monitor-APK/releases/latest"
        private const val QUICKPRINTER_PACKAGE = "pe.diegoveloper.printerserverapp"
        private const val RAWBT_PACKAGE = "ru.a402d.rawbtprinter"
        private const val MAX_HISTORY_ITEMS = 30

        // Printer Driver Download URLs
        private const val URL_DRIVER_QUICKPRINTER = "http://jeriyant.my.id/.DriverPrinterBT/QuickPrinter_v1.4.8_full.apk"
        private const val URL_DRIVER_RAWBT = "http://jeriyant.my.id/.DriverPrinterBT/RAWBT_v_6.0.7_Full.apk"
        private const val URL_DRIVER_PRINTERSHARE = "http://jeriyant.my.id/.DriverPrinterBT/PrinterShare v12.24.5-PREMIUM.apk"
        private const val URL_DRIVER_NOKOPRINT = "http://jeriyant.my.id/.DriverPrinterBT/NokoPrint v5.27.0-PREMIUM.apk"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enforce system-wide Night / Day Mode compliance
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupToolbar()
        setupWebView()
        setupSwipeRefresh()
        setupBackPressedHandler()
        checkAndRequestPermissions()

        val initialUrl = getDefaultUrl()
        loadUrl(initialUrl)

        checkForUpdates(isManual = false)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.toolbar_title)
        
        // Dynamically set status bar color to match top bar
        window.statusBarColor = ContextCompat.getColor(this, R.color.toolbar_bg)
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
            useWideViewPort = false // Match Chrome Mobile Viewport
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString + " JNETMonitorApp/3.3.1"
        }

        // Native Android Force Dark Mode for WebView content if system is in Dark Mode
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                @Suppress("DEPRECATION")
                webSettings.forceDark = if (isNight) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebSettings(binding.webView.settings)

        // Set WebView background color according to Light/Dark Mode
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val wvBg = if (isNight) ContextCompat.getColor(this, R.color.surface_dark) else ContextCompat.getColor(this, R.color.surface_light)
        binding.webView.setBackgroundColor(wvBg)

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

            override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                super.onReceivedIcon(view, icon)
                val targetUrl = view?.url ?: return
                if (icon != null) {
                    saveToHistory(view.title ?: targetUrl, targetUrl, icon)
                }
            }

            /**
             * Robust, Crash-Proof New Tab / Popup Window Handler
             */
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                try {
                    val result = view?.hitTestResult
                    val url = result?.extra

                    // 1. Direct HitTestResult URL capture
                    if (!url.isNullOrEmpty()) {
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            binding.webView.loadUrl(url)
                        } else {
                            dispatchUrl(url, binding.webView)
                        }
                        return true
                    }

                    // 2. Transport Helper WebView for window.open() & target="_blank"
                    val popupWebView = WebView(this@MainActivity)
                    configureWebSettings(popupWebView.settings)
                    popupWebView.setBackgroundColor(wvBg)

                    popupWebView.addJavascriptInterface(
                        WebAppInterface(this@MainActivity, binding.webView), JS_PRINT_INTERFACE
                    )

                    popupWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        handleDownload(url, userAgent, contentDisposition, mimetype)
                    }

                    popupWebView.webViewClient = object : WebViewClient() {
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun shouldOverrideUrlLoading(v: WebView?, targetUrl: String?): Boolean {
                            if (!targetUrl.isNullOrEmpty()) {
                                if (dispatchUrl(targetUrl, binding.webView)) {
                                    try { v?.destroy() } catch (e: Exception) {}
                                    return true
                                }
                                binding.webView.loadUrl(targetUrl)
                            }
                            try { v?.destroy() } catch (e: Exception) {}
                            return true
                        }

                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUrl = request?.url?.toString()
                            if (!targetUrl.isNullOrEmpty()) {
                                if (dispatchUrl(targetUrl, binding.webView)) {
                                    try { v?.destroy() } catch (e: Exception) {}
                                    return true
                                }
                                binding.webView.loadUrl(targetUrl)
                            }
                            try { v?.destroy() } catch (e: Exception) {}
                            return true
                        }

                        override fun onPageFinished(v: WebView?, targetUrl: String?) {
                            super.onPageFinished(v, targetUrl)
                            if (!targetUrl.isNullOrEmpty()) {
                                injectBridgeScript(binding.webView)
                                if (isPrintPage(targetUrl)) printWebPage(binding.webView)
                            }
                            try { v?.destroy() } catch (e: Exception) {}
                        }
                    }

                    popupWebView.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            try { window?.destroy() } catch (e: Exception) {}
                        }
                    }

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = popupWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                } catch (e: Exception) {
                    // Prevent any possible crash on unexpected window.open JS calls
                }
                return false
            }
        }

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return dispatchUrl(url ?: "", view)
            }

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
                if (url != null) saveToHistory(view?.title ?: url, url, view?.favicon)
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
    // JS Bridge Script
    // ==========================================

    private fun injectBridgeScript(targetWebView: WebView? = binding.webView) {
        val js = """
(function() {
    // 0. Polyfill window.innerWidth so quickuser.php checks (w < 800) evaluate to TRUE on mobile
    try {
        var screenW = window.screen ? window.screen.width : 360;
        if (screenW < 800) {
            Object.defineProperty(window, 'innerWidth', {
                get: function() { return screenW; },
                configurable: true
            });
        }
    } catch(e) {}

    // 1. Override Location.prototype.href setter before V8 Chromium URL validation runs!
    try {
        if (window.Location && window.Location.prototype) {
            var descriptor = Object.getOwnPropertyDescriptor(window.Location.prototype, 'href');
            if (descriptor && descriptor.set && !window._jnetLocationProtoPatched) {
                var origSetter = descriptor.set;
                Object.defineProperty(window.Location.prototype, 'href', {
                    configurable: true,
                    enumerable: true,
                    get: descriptor.get,
                    set: function(val) {
                        if (typeof val === 'string' && (
                            val.indexOf('intent://') === 0 ||
                            val.indexOf('quickprinter:') === 0 ||
                            val.indexOf('rawbt:') === 0 ||
                            val.indexOf('scheme=quickprinter') !== -1 ||
                            val.indexOf('package=$QUICKPRINTER_PACKAGE') !== -1
                        )) {
                            if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.sendIntent) {
                                window.$JS_PRINT_INTERFACE.sendIntent(val);
                                return;
                            }
                        }
                        try {
                            origSetter.call(this, val);
                        } catch(err) {
                            if (typeof val === 'string' && (val.indexOf('intent://') !== -1 || val.indexOf('quickprinter') !== -1)) {
                                if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.sendIntent) {
                                    window.$JS_PRINT_INTERFACE.sendIntent(val);
                                }
                            }
                        }
                    }
                });
                window._jnetLocationProtoPatched = true;
            }
        }
    } catch(e) {}

    // 2. Global Error Event Listener to catch V8 SyntaxError on location.href assignments
    if (!window._jnetErrorListenerAdded) {
        window.addEventListener('error', function(event) {
            var msg = (event && event.message) ? event.message : '';
            if (msg.indexOf('intent://') !== -1 || msg.indexOf('quickprinter') !== -1) {
                var start = msg.indexOf('intent://');
                if (start === -1) start = msg.indexOf('quickprinter');
                if (start !== -1) {
                    var end = msg.indexOf("'", start);
                    if (end === -1) end = msg.length;
                    var intentUrl = msg.substring(start, end);
                    if (window.$JS_PRINT_INTERFACE && window.$JS_PRINT_INTERFACE.sendIntent) {
                        window.$JS_PRINT_INTERFACE.sendIntent(intentUrl);
                    }
                }
            }
        }, true);
        window._jnetErrorListenerAdded = true;
    }

    // 3. Override window.print()
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

    // 4. Hook sendToQuickPrinterChrome directly
    function patchQuickPrinter() {
        if (window.$JS_PRINT_INTERFACE) {
            window.sendToQuickPrinterChrome = function() {
                var cmds = "";
                try {
                    if (typeof commandsToPrint !== 'undefined') {
                        cmds = commandsToPrint;
                    }
                } catch(e) {}
                if (cmds) {
                    window.$JS_PRINT_INTERFACE.sendIntent(cmds);
                } else {
                    var textEncoded = (typeof encodeURI === 'function') ? encodeURI(cmds) : "";
                    window.$JS_PRINT_INTERFACE.sendIntent("intent://" + textEncoded + "#Intent;scheme=quickprinter;package=$QUICKPRINTER_PACKAGE;end;");
                }
            };
        }
    }

    patchQuickPrinter();
    setInterval(patchQuickPrinter, 250);

    // 5. Patch jQuery AJAX headers to send X-Requested-With: XMLHttpRequest
    if (window.jQuery) {
        window.jQuery.ajaxSetup({
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        });
    } else {
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
    // URL & Intent Dispatcher
    // ==========================================

    fun dispatchUrl(url: String, targetWebView: WebView?): Boolean {
        if (url.isEmpty()) return false

        // Handle direct file download links (.apk, .zip, .rar, .pdf, .7z)
        val lower = url.lowercase(Locale.getDefault())
        if (lower.endsWith(".apk") || lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".pdf") || lower.endsWith(".7z") || lower.contains(".apk?")) {
            handleDownload(url)
            return true
        }

        if (url.startsWith("http://") || url.startsWith("https://")) return false
        return launchIntent(url, targetWebView)
    }

    private fun handleDownload(
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimetype: String? = null
    ) {
        if (url.isEmpty()) return
        try {
            val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimetype.isNullOrEmpty()) setMimeType(mimetype)
                if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                setDescription("Mengunduh berkas...")
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                setTitle(filename)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Mengunduh berkas...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Fallback: Open in external system browser
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (err: Exception) {
                Toast.makeText(applicationContext, "Gagal mengunduh berkas: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchIntent(url: String, targetWebView: WebView?): Boolean {
        var payload = url
        if (payload.startsWith("intent://")) {
            val hashIdx = payload.indexOf("#Intent;")
            payload = if (hashIdx != -1) {
                payload.substring("intent://".length, hashIdx)
            } else {
                payload.substring("intent://".length)
            }
        } else if (payload.startsWith("quickprinter://")) {
            payload = payload.substring("quickprinter://".length)
        } else if (payload.startsWith("rawbt:")) {
            payload = payload.substring("rawbt:".length)
        }

        try {
            payload = URLDecoder.decode(payload, "UTF-8")
        } catch (e: Exception) {}

        val encodedPayload = Uri.encode(payload)

        // Method 1: Direct quickprinter:// intent to QuickPrinter app
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("quickprinter://$encodedPayload")).apply {
                setPackage(QUICKPRINTER_PACKAGE)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {}

        // Method 2: Direct rawbt: intent to RawBT app
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("rawbt:$encodedPayload")).apply {
                setPackage(RAWBT_PACKAGE)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {}

        // Method 3: Intent without explicit package restriction
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("quickprinter://$encodedPayload")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {}

        // Method 4: Direct package launch intent
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(QUICKPRINTER_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return true
            }
        } catch (e: Exception) {}

        // Method 5: Launch RawBT app directly
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(RAWBT_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return true
            }
        } catch (e: Exception) {}

        // Method 6: Fallback to System Print
        printWebPage(targetWebView)
        return true
    }

    // ==========================================
    // Native Print
    // ==========================================

    fun printWebPage(targetWebView: WebView? = binding.webView) {
        try {
            val wv = targetWebView ?: binding.webView
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "${getString(R.string.toolbar_title)}_${System.currentTimeMillis()}"
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
    // History Feature
    // ==========================================

    // ==========================================
    // History Feature
    // ==========================================

    data class HistoryItem(val title: String, val url: String, val timestamp: String, val faviconBase64: String = "")

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        return try {
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, baos)
            val b = baos.toByteArray()
            android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    private fun base64ToBitmap(base64Str: String): android.graphics.Bitmap? {
        return try {
            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) { null }
    }

    private fun saveToHistory(title: String, url: String, faviconBitmap: android.graphics.Bitmap? = null) {
        if (url.isEmpty() || url.startsWith("about:") || url.startsWith("data:")) return
        try {
            val historyJson = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
            val array = JSONArray(historyJson)
            val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

            val iconStr = faviconBitmap?.let { bitmapToBase64(it) } ?: ""

            if (array.length() > 0) {
                val lastObj = array.getJSONObject(0)
                if (lastObj.optString("url") == url) {
                    if (iconStr.isNotEmpty() && lastObj.optString("favicon").isEmpty()) {
                        lastObj.put("favicon", iconStr)
                        if (title.isNotEmpty()) lastObj.put("title", title)
                        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
                    }
                    return
                }
            }

            val newObj = JSONObject().apply {
                put("title", title.ifEmpty { url })
                put("url", url)
                put("time", time)
                if (iconStr.isNotEmpty()) put("favicon", iconStr)
            }

            val newArray = JSONArray()
            newArray.put(newObj)
            for (i in 0 until array.length()) {
                newArray.put(array.get(i))
            }

            prefs.edit().putString(KEY_HISTORY_JSON, newArray.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun getHistoryList(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            val historyJson = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
            val array = JSONArray(historyJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(HistoryItem(
                    title = obj.optString("title", "Halaman Web"),
                    url = obj.optString("url", ""),
                    timestamp = obj.optString("time", ""),
                    faviconBase64 = obj.optString("favicon", "")
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun showHistoryDialog() {
        val historyList = getHistoryList()
        if (historyList.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.history_title))
                .setMessage(getString(R.string.history_empty))
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
            return
        }

        val adapter = object : ArrayAdapter<HistoryItem>(this, R.layout.item_history, historyList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_history, parent, false)
                val item = getItem(position) ?: return view

                val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivHistoryIcon)
                val tvTitle = view.findViewById<TextView>(R.id.tvHistoryTitle)
                val tvUrl = view.findViewById<TextView>(R.id.tvHistoryUrl)
                val tvTime = view.findViewById<TextView>(R.id.tvHistoryTime)

                tvTitle.text = item.title
                tvUrl.text = item.url
                tvTime.text = item.timestamp

                if (item.faviconBase64.isNotEmpty()) {
                    val bmp = base64ToBitmap(item.faviconBase64)
                    if (bmp != null) {
                        ivIcon.setImageBitmap(bmp)
                        ivIcon.colorFilter = null
                        androidx.core.widget.ImageViewCompat.setImageTintList(ivIcon, null)
                    } else {
                        ivIcon.setImageResource(R.drawable.ic_history)
                        androidx.core.widget.ImageViewCompat.setImageTintList(
                            ivIcon,
                            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                        )
                    }
                } else {
                    ivIcon.setImageResource(R.drawable.ic_history)
                    androidx.core.widget.ImageViewCompat.setImageTintList(
                        ivIcon,
                        android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                    )
                }

                return view
            }
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.history_title))
            .setAdapter(adapter) { _, which ->
                val item = historyList[which]
                loadUrl(item.url)
                dialog?.dismiss()
            }
            .setNeutralButton(getString(R.string.btn_clear_history)) { d, _ ->
                prefs.edit().remove(KEY_HISTORY_JSON).apply()
                Toast.makeText(this, getString(R.string.msg_history_cleared), Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()
    }

    // ==========================================
    // Printer Driver Dialog
    // ==========================================

    private fun openExternalDownload(url: String) {
        handleDownload(url)
    }

    private fun showPrinterDriverDialog() {
        val dialogBinding = DialogPrinterDriverBinding.inflate(LayoutInflater.from(this))

        dialogBinding.btnDownloadQuickPrinter.setOnClickListener {
            openExternalDownload(URL_DRIVER_QUICKPRINTER)
        }

        dialogBinding.btnDownloadRawbt.setOnClickListener {
            openExternalDownload(URL_DRIVER_RAWBT)
        }

        dialogBinding.btnDownloadPrinterShare.setOnClickListener {
            openExternalDownload(URL_DRIVER_PRINTERSHARE)
        }

        dialogBinding.btnDownloadNokoPrint.setOnClickListener {
            openExternalDownload(URL_DRIVER_NOKOPRINT)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.printer_driver_title))
            .setView(dialogBinding.root)
            .setPositiveButton("Tutup") { d, _ -> d.dismiss() }
            .show()
    }

    // ==========================================
    // About Dialog (with Check Updates button & Creator info)
    // ==========================================

    private fun showAboutDialog() {
        val dialogBinding = DialogAboutBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvAboutVersion.text = "Versi ${getCurrentVersion()}"

        var aboutDialog: androidx.appcompat.app.AlertDialog? = null

        dialogBinding.btnCheckUpdateAbout.setOnClickListener {
            aboutDialog?.dismiss()
            checkForUpdates(isManual = true)
        }

        aboutDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Tutup") { d, _ -> d.dismiss() }
            .create()

        aboutDialog.show()
    }

    // ==========================================
    // JavaScript Interface Bridge
    // ==========================================

    inner class WebAppInterface(
        private val activity: MainActivity,
        private val webView: WebView
    ) {
        @JavascriptInterface
        fun nativePrint() {
            activity.runOnUiThread {
                activity.printWebPage(webView)
            }
        }

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
    // 3-Dot Menu (with Icons enabled & styled)
    // ==========================================

    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> { binding.webView.reload(); true }
            R.id.action_print -> { printWebPage(binding.webView); true }
            R.id.action_history -> { showHistoryDialog(); true }
            R.id.action_settings -> { showSettingsDialog(); true }
            R.id.action_printer_driver -> { showPrinterDriverDialog(); true }
            R.id.action_about -> { showAboutDialog(); true }
            R.id.action_exit -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val currentUrl = getDefaultUrl()

        dialogBinding.etUrl.setText(currentUrl)
        val activeUrl = binding.webView.url ?: currentUrl
        dialogBinding.tvCurrentUrl.text = "URL Saat Ini: $activeUrl"

        dialogBinding.btnSetCurrentUrl.setOnClickListener {
            val liveUrl = binding.webView.url
            if (!liveUrl.isNullOrEmpty()) {
                dialogBinding.etUrl.setText(liveUrl)
                Toast.makeText(this, "Diisi dengan URL saat ini", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Belum ada halaman yang dimuat", Toast.LENGTH_SHORT).show()
            }
        }

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
    // Auto-Update (GitHub Releases — Force Update)
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "2.9.0"
    } catch (e: Exception) { "2.9.0" }

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
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.update_available_title)} (v$ver)")
            .setMessage("Catatan Rilis:\n\n$notes\n\n⚠️ Pembaruan ini Wajib untuk melanjutkan penggunaan aplikasi.")
            .setCancelable(false)
            .setPositiveButton(getString(R.string.update_btn_download)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                finish()
            }
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.let { btn ->
            btn.background = ContextCompat.getDrawable(this, R.drawable.bg_framed_button)
            btn.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }
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
