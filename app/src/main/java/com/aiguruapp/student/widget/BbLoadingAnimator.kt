package com.aiguruapp.student.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebViewClient

/**
 * Manages the SVG loading animation WebView shown while BB sessions generate.
 *
 * Strategy:
 *  - At first [start] call, all HTML files are pre-loaded into memory and a small
 *    `<script>` is injected that slows every CSS animation by [SLOW_FACTOR]. This
 *    avoids per-switch disk I/O so the WebView crossover feels instant.
 *  - Each loading episode picks 5 random animations (no repeats within the session).
 *  - A fast warmup default plays first so the user never sees an unstyled WebView,
 *    then session animations rotate every [ROTATE_INTERVAL_MS].
 *  - WebView bg is solid #0D1117 so there's no white flash before HTML renders.
 */
object BbLoadingAnimator {

    private val ALL_ANIMATIONS = arrayOf(
        "loading_svgs/01_water_glass.html",
        "loading_svgs/02_house_build.html",
        "loading_svgs/03_rocket_launch.html",
        "loading_svgs/04_plant_grow.html",
        "loading_svgs/05_gears.html",
        "loading_svgs/06_constellation.html",
        "loading_svgs/07_lightbulb.html",
        "loading_svgs/08_pencil_write.html",
        "loading_svgs/09_brain_neurons.html",
        "loading_svgs/10_solar_system.html",
        "loading_svgs/11_hourglass.html",
        "loading_svgs/12_dna_helix.html",
        "loading_svgs/13_atom_nucleus.html",
        "loading_svgs/14_book_open.html",
        "loading_svgs/15_volcano.html",
        "loading_svgs/16_snowflake.html",
        "loading_svgs/17_train.html",
        "loading_svgs/18_submarine.html",
        "loading_svgs/19_hot_air_balloon.html",
        "loading_svgs/20_domino.html",
        "loading_svgs/21_magnifier.html",
        "loading_svgs/22_bouncing_balls.html",
        "loading_svgs/23_typewriter.html",
        "loading_svgs/24_wave_interference.html",
        "loading_svgs/25_crystal_grow.html",
        "loading_svgs/26_clock.html",
        "loading_svgs/27_aurora.html",
        "loading_svgs/27_lightning.html",
        "loading_svgs/28_butterfly.html",
        "loading_svgs/28_telescope.html",
        "loading_svgs/29_popcorn.html",
        "loading_svgs/29_tornado.html",
        "loading_svgs/30_chemistry.html",
        "loading_svgs/31_maze_solve.html",
        "loading_svgs/32_prism_rainbow.html",
        "loading_svgs/33_sandcastle.html",
        "loading_svgs/34_music_notes.html",
        "loading_svgs/35_fish_jump.html",
        "loading_svgs/36_tetris.html"
    )

    /** Always-quick first frame so we never show an unstyled white WebView. */
    private const val WARMUP_ASSET = "loading_svgs/22_bouncing_balls.html"
    private const val WARMUP_MS = 4000L
    private const val ROTATE_INTERVAL_MS = 4000L
    private const val SESSION_SIZE = 5
    private const val DARK_BG = 0xFF0D1117.toInt()
    /** Multiplier applied to every CSS animation-duration. >1 = slower. */
    private const val SLOW_FACTOR = 1.6

    /** asset-path → HTML content (with slowdown script injected). One-time lazy load. */
    private val htmlCache = mutableMapOf<String, String>()

    private val sessionQueue = mutableListOf<String>()
    private var sessionIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var rotateRunnable: Runnable? = null
    private var activeWebView: WebView? = null

    /** Read every HTML asset into [htmlCache] once. Safe to call repeatedly. */
    private fun ensureCached(ctx: Context) {
        if (htmlCache.size >= ALL_ANIMATIONS.size) return
        for (path in ALL_ANIMATIONS) {
            if (htmlCache.containsKey(path)) continue
            try {
                ctx.assets.open(path).bufferedReader().use { reader ->
                    htmlCache[path] = injectSlowdown(reader.readText())
                }
            } catch (_: Exception) {
                // Missing/unreadable asset — fall back to file:// URL at load time.
            }
        }
    }

    /**
     * Append a `<script>` that walks all elements after `load` and multiplies their
     * `animation-duration` by [SLOW_FACTOR]. Handles comma-separated multi-animation
     * values by scaling each component independently.
     */
    private fun injectSlowdown(html: String): String {
        val js = """
            <script>
            window.addEventListener('load', function() {
              var f = $SLOW_FACTOR;
              document.querySelectorAll('*').forEach(function(el) {
                var d = getComputedStyle(el).animationDuration;
                if (!d || d === '' || d === '0s') return;
                var scaled = d.split(',').map(function(s) {
                  var t = s.trim();
                  var n = parseFloat(t);
                  return (isNaN(n) || n <= 0) ? t : (n * f) + 's';
                }).join(', ');
                el.style.animationDuration = scaled;
              });
            });
            </script>
        """.trimIndent()
        return if (html.contains("</body>"))
            html.replace("</body>", "$js</body>")
        else
            html + js
    }

    /** Pick SESSION_SIZE distinct animations at random from the full pool. */
    private fun refreshSession() {
        sessionQueue.clear()
        sessionQueue.addAll(ALL_ANIMATIONS.toList().shuffled().take(SESSION_SIZE))
        sessionIndex = 0
    }

    private fun nextAnimation(): String {
        if (sessionIndex >= sessionQueue.size) refreshSession()
        return sessionQueue[sessionIndex++]
    }

    /**
     * Begin the loading animation sequence on [webView]. Must be called on the main thread.
     * Cancels any in-flight rotation tied to a prior call before starting fresh.
     */
    fun start(webView: WebView) {
        cancelRotation()
        activeWebView = webView
        ensureCached(webView.context.applicationContext)

        with(webView.settings) {
            javaScriptEnabled                = true
            domStorageEnabled                = false
            allowFileAccessFromFileURLs      = false
            allowUniversalAccessFromFileURLs = false
            cacheMode                        = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls              = false
            displayZoomControls              = false
            setSupportZoom(false)
        }
        webView.setBackgroundColor(DARK_BG)
        webView.webChromeClient = WebChromeClient()

        // Stay INVISIBLE until the first page finishes painting — eliminates the
        // white flash. Subsequent rotation swaps are in-memory and instant so we
        // never go invisible again.
        webView.visibility = View.INVISIBLE
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (activeWebView === view) view.visibility = View.VISIBLE
                // Reset to a no-op client so rotation swaps don't hide/show the view
                view.webViewClient = WebViewClient()
            }
        }

        refreshSession()
        loadAsset(webView, WARMUP_ASSET)

        rotateRunnable = Runnable {
            if (activeWebView === webView) {
                loadAsset(webView, nextAnimation())
                scheduleNext(webView)
            }
        }
        handler.postDelayed(rotateRunnable!!, WARMUP_MS)
    }

    private fun loadAsset(webView: WebView, asset: String) {
        webView.stopLoading()
        val cached = htmlCache[asset]
        if (cached != null) {
            webView.loadDataWithBaseURL(
                "file:///android_asset/", cached, "text/html", "utf-8", null
            )
        } else {
            // Fallback for assets missing from the cache — straight from disk.
            webView.loadUrl("file:///android_asset/$asset#${System.currentTimeMillis()}")
        }
    }

    private fun scheduleNext(webView: WebView) {
        rotateRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (activeWebView === webView) {
                loadAsset(webView, nextAnimation())
                scheduleNext(webView)
            }
        }
        rotateRunnable = r
        handler.postDelayed(r, ROTATE_INTERVAL_MS)
    }

    private fun cancelRotation() {
        rotateRunnable?.let { handler.removeCallbacks(it) }
        rotateRunnable = null
    }

    /**
     * Stop rotation, blank the WebView, hide it. Must be called on the main thread.
     */
    fun stop(webView: WebView) {
        cancelRotation()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.visibility = View.GONE
        if (activeWebView === webView) activeWebView = null
    }
}
