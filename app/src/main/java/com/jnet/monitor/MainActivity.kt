package com.jnet.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "JNetMonitorPrefs"
        private const val KEY_DEFAULT_URL = "default_url"
        private const val FALLBACK_URL = "https://google.com"
        private const val JS_PRINT_INTERFACE = "AndroidPrintInterface"
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
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
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
            userAgentString = userAgentString + " JNETMonitorApp/1.0"
        }

        // Bridge JavaScript window.print() to Android Native PrintManager
        binding.webView.addJavascriptInterface(WebPrintInterface(this), JS_PRINT_INTERFACE)

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
                if (!title.isNull_or_empty_or_url(view?.url)) {
                    binding.toolbar.subtitle = title
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Handle standard HTTP & HTTPS links inside WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // Handle external apps (whatsapp, mailto, tel, intents)
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Tidak dapat membuka tautan: $url", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.layoutError.visibility = View.GONE
                binding.toolbar.subtitle = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false

                // Inject window.print() polyfill so any site's print action triggers native Android printing
                injectPrintJavaScript()
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

    fun printWebPage() {
        try {
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "${getString(R.string.app_name)} Document_${System.currentTimeMillis()}"
            val printAdapter = binding.webView.createPrintDocumentAdapter(jobName)
            
            val builder = PrintAttributes.Builder()
            builder.setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            builder.setResolution(PrintAttributes.Resolution("id", "pdf", 300, 300))
            builder.setMinMargins(PrintAttributes.Margins.ZERO)

            printManager.print(jobName, printAdapter, builder.build())
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memulai cetak: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun injectPrintJavaScript() {
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
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    private inner class WebPrintInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun print() {
            activity.runOnUiThread {
                activity.printWebPage()
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
                printWebPage()
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
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
            .setPositiveButton(getString(R.string.btn_save), null) // Set null to handle validation inside listener
            .setNeutralButton(getString(R.string.btn_reset_default), null)
            .setNegativeButton(getString(R.string.btn_cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        // Handle Positive Button click with validation
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

        // Handle Neutral Button click (Reset to Default)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            saveDefaultUrl(FALLBACK_URL)
            Toast.makeText(this, "URL direset ke $FALLBACK_URL", Toast.LENGTH_SHORT).show()
            loadUrl(FALLBACK_URL)
            dialog.dismiss()
        }
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
