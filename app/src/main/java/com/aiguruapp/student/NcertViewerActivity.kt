package com.aiguruapp.student

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Safe in-app viewer that opens NCERT PDF links via Google Docs Viewer,
 * so the PDF renders without needing a PDF plugin on the device.
 *
 * Security measures:
 *  - JavaScript disabled (PDFs don't need it when rendered by Docs Viewer)
 *  - Navigation restricted to ncert.nic.in and docs.google.com only
 *  - No file:// access, no mixed content
 *  - External links open in the system browser, not here
 */
class NcertViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "chapter_title"
        const val EXTRA_URL   = "ncert_pdf_url"

        /** Wraps a direct PDF URL in Google Docs Viewer for universal rendering. */
        fun docsViewerUrl(pdfUrl: String): String =
            "https://docs.google.com/gview?embedded=true&url=${Uri.encode(pdfUrl)}"

        /** Allowed host whitelist — navigation to anything else opens in the browser. */
        private val ALLOWED_HOSTS = setOf("ncert.nic.in", "docs.google.com")
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ncert_viewer)

        val title  = intent.getStringExtra(EXTRA_TITLE) ?: "NCERT Textbook"
        val rawUrl = intent.getStringExtra(EXTRA_URL)

        if (rawUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No NCERT link available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Validate URL points to NCERT before rendering
        val host = Uri.parse(rawUrl).host ?: ""
        if (!host.endsWith("ncert.nic.in")) {
            Toast.makeText(this, "Invalid NCERT link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.ncertTitle).text = title
        findViewById<ImageButton>(R.id.ncertBackButton).setOnClickListener { finish() }

        // Open-in-browser button for users who prefer a full PDF reader
        findViewById<ImageButton>(R.id.ncertOpenBrowserButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)))
        }

        progressBar = findViewById(R.id.ncertProgressBar)
        webView = findViewById(R.id.ncertWebView)
        setupWebView()

        // Load via Google Docs Viewer — renders the PDF without any native PDF plugin
        webView.loadUrl(docsViewerUrl(rawUrl))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // Serve from disk cache on re-opens; fall back to network if not cached
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            databaseEnabled = true
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                return if (ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    false  // allow navigation within whitelisted hosts
                } else {
                    // Send any other URL to the system browser safely
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
