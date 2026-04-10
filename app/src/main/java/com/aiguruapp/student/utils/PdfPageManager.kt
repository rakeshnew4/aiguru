package com.aiguruapp.student.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Manages PDF rendering from assets to locally cached JPEG page images.
 *
 * Usage flow:
 *  1. Call getPageCount() to know how many pages exist.
 *  2. Call getPage(pdfId, assetPath, pageIndex) to get a rendered File for any page.
 *  3. Pages are cached to cacheDir/pdf_pages/{pdfId}/page_N.jpg — no re-render on repeat calls.
 *  4. The source PDF is cached to cacheDir/pdf_cache/{pdfId}.pdf — no re-copy on repeat calls.
 *
 * All methods are blocking — call from a background thread (e.g. Dispatchers.IO).
 */
class PdfPageManager(private val context: Context) {

    // Directory where rendered page images are cached
    private fun pageCacheDir(pdfId: String): File {
        val dir = File(context.cacheDir, "pdf_pages/$pdfId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Cached copy of the source PDF file
    private fun cachedPdfFile(pdfId: String): File {
        val dir = File(context.cacheDir, "pdf_cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$pdfId.pdf")
    }

    /**
     * Ensures the asset PDF is copied to the local cache. Returns the cached File.
     * Does nothing if the file is already cached.
     */
    fun ensurePdfCached(pdfId: String, assetPath: String): File {
        val cached = cachedPdfFile(pdfId)
        if (!cached.exists()) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(cached).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cached
    }

    /**
     * Returns the total number of pages in the PDF.
     * Copies the asset to cache first if needed.
     */
    fun getPageCount(pdfId: String, assetPath: String): Int {
        val file = ensurePdfCached(pdfId, assetPath)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pfd).use { renderer -> renderer.pageCount }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }
    }

    /**
     * Renders a single page and returns its cached JPEG File.
     * If already rendered, returns the existing file immediately.
     *
     * @param widthPx Target width in pixels. Height is proportional to page aspect ratio.
     */
    fun getPage(pdfId: String, assetPath: String, pageIndex: Int, widthPx: Int = 1080): File {
        val pageFile = File(pageCacheDir(pdfId), "page_$pageIndex.jpg")
        if (pageFile.exists()) return pageFile

        val pdf = ensurePdfCached(pdfId, assetPath)
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val heightPx = (widthPx * page.height.toFloat() / page.width.toFloat()).toInt()
                    // Attempt full resolution; fall back to half-res on low memory
                    val bmp: Bitmap = try {
                        Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    } catch (oom: OutOfMemoryError) {
                        Bitmap.createBitmap(widthPx / 2, heightPx / 2, Bitmap.Config.ARGB_8888)
                    }
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    try {
                        FileOutputStream(pageFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            pageFile.delete()  // Remove any partial write
            throw e
        }
        return pageFile
    }

    /**
     * Returns the cached page File if it already exists, or null if not yet rendered.
     */
    fun getCachedPage(pdfId: String, pageIndex: Int): File? {
        val f = File(pageCacheDir(pdfId), "page_$pageIndex.jpg")
        return if (f.exists()) f else null
    }

    /**
     * Clears all cache for a specific PDF (source file + all rendered pages).
     */
    fun clearCache(pdfId: String) {
        cachedPdfFile(pdfId).delete()
        pageCacheDir(pdfId).deleteRecursively()
    }
}
