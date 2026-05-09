package com.aiguruapp.student.widget

import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebChromeClient

/**
 * Manages the SVG loading animation WebView shown while BB sessions generate.
 *
 * Strategy: at session start, 5 random animations are picked from the full pool.
 * Those 5 play in order (no repeats). When all 5 are exhausted, a fresh random 5
 * are chosen from the full pool for the next batch.
 *
 * Assets live in: assets/loading_svgs/
 * Call [start] to load the next animation, [stop] to clear the WebView.
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

    private const val SESSION_SIZE = 5

    // Current session queue: 5 filenames picked randomly, served in order
    private val sessionQueue = mutableListOf<String>()
    private var sessionIndex = 0

    /** Pick SESSION_SIZE distinct animations at random from the full pool. */
    private fun refreshSession() {
        sessionQueue.clear()
        sessionQueue.addAll(ALL_ANIMATIONS.toList().shuffled().take(SESSION_SIZE))
        sessionIndex = 0
    }

    /** Return the next animation in the session queue; refreshes when exhausted. */
    private fun nextAnimation(): String {
        if (sessionIndex >= sessionQueue.size) refreshSession()
        return sessionQueue[sessionIndex++]
    }

    /**
     * Load the next session animation into [webView] and make it visible.
     * Must be called on the main thread.
     */
    fun start(webView: WebView) {
        val chosen = nextAnimation()
        webView.stopLoading()
        webView.clearCache(true)
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
        webView.setBackgroundColor(0x00000000)
        webView.webChromeClient = WebChromeClient()
        // Append a timestamp fragment so the WebView treats each load as a distinct URL
        // and cannot serve a cached version of a previously shown animation.
        val url = "file:///android_asset/$chosen#${System.currentTimeMillis()}"
        webView.loadUrl(url)
        webView.visibility = android.view.View.VISIBLE
    }

    /**
     * Stop the animation and hide the WebView.
     * Must be called on the main thread.
     */
    fun stop(webView: WebView) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.visibility = android.view.View.GONE
    }
}
