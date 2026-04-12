package com.aiguruapp.student.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background PDF page preloader.
 * 
 * Renders next 5 pages ahead of current page in the background,
 * so when user swipes, pages load instantly from disk cache.
 * 
 * Usage:
 *   val preloader = PdfPreloadManager()
 *   preloader.preloadAhead(pdfId, assetPath, currentPage, pageManager)
 *   preloader.stop() // Clean up when done
 */
class PdfPreloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preloadState = ConcurrentHashMap<String, Int>() // pdfId -> lastRequestedPage
    private val TAG = "PdfPreloader"

    /**
     * Start preloading pages ahead of [currentPage].
     * Will preload: currentPage+1 through currentPage+5 (or until page count).
     * 
     * Safe to call repeatedly — only queues missing pages.
     */
    fun preloadAhead(
        pdfId: String,
        assetPath: String,
        currentPage: Int,
        pageManager: PdfPageManager,
        maxPageCount: Int = 999
    ) {
        val key = pdfId
        val lastRequested = preloadState[key] ?: -1
        
        // Skip if we already requested preload for this page
        if (lastRequested >= currentPage) return
        
        preloadState[key] = currentPage
        
        scope.launch {
            try {
                val startPage = currentPage + 1
                val endPage = minOf(currentPage + 5, maxPageCount - 1)
                
                for (page in startPage..endPage) {
                    if (page < 0) continue
                    
                    // Check if already cached
                    val cached = pageManager.getCachedPage(pdfId, page)
                    if (cached != null && cached.exists()) {
                        Log.d(TAG, "Page $page already cached, skipping")
                        continue
                    }
                    
                    // Render and cache
                    try {
                        val file = pageManager.getPage(pdfId, assetPath, page)
                        Log.d(TAG, "Preloaded page $page (${file.length() / 1024}KB)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to preload page $page: ${e.message}")
                        // Continue to next page, don't fail entire preload
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preload batch failed: ${e.message}")
            }
        }
    }

    /**
     * Cancel pending preloads and release resources.
     */
    fun stop() {
        scope.launch {
            preloadState.clear()
        }
    }
}
