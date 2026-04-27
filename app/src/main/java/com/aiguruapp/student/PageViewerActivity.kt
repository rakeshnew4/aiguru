package com.aiguruapp.student

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aiguruapp.student.utils.PdfPageManager
import com.aiguruapp.student.utils.PdfPreloadManager
import com.aiguruapp.student.widget.BoxSpinnerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen page viewer for PDF chapters.
 * Receives: subjectName, chapterName, pdfId, pdfAssetPath, pageCount, startPage (0-based).
 * Allows browsing pages with Prev/Next, and tapping "Ask AI" to jump into ChatActivity.
 */
class PageViewerActivity : AppCompatActivity() {

    private lateinit var pageImage: ImageView
    private lateinit var loadingSpinner: BoxSpinnerView
    private lateinit var pageTitle: TextView
    private lateinit var pageCounter: TextView
    private lateinit var prevButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private lateinit var pdfId: String
    private lateinit var pdfAssetPath: String
    private var pageCount = 0
    private var currentPage = 0

    private lateinit var pdfPageManager: PdfPageManager
    private lateinit var pdfPreloader: PdfPreloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_page_viewer)

        subjectName  = intent.getStringExtra("subjectName")  ?: "Subject"
        chapterName  = intent.getStringExtra("chapterName")  ?: "Chapter"
        pdfId        = intent.getStringExtra("pdfId")        ?: ""
        pdfAssetPath = intent.getStringExtra("pdfAssetPath") ?: ""
        pageCount    = intent.getIntExtra("pageCount", 1)
        currentPage  = intent.getIntExtra("startPage", 0)

        pdfPageManager = PdfPageManager(this)
        pdfPreloader = PdfPreloadManager()

        pageImage     = findViewById(R.id.pageImage)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        pageTitle     = findViewById(R.id.pageTitle)
        pageCounter   = findViewById(R.id.pageCounter)
        prevButton    = findViewById(R.id.prevButton)
        nextButton    = findViewById(R.id.nextButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        prevButton.setOnClickListener {
            if (currentPage > 0) { currentPage--; loadPage() }
        }
        nextButton.setOnClickListener {
            if (currentPage < pageCount - 1) { currentPage++; loadPage() }
        }
        
        // Ask AI button - attach page to chat and send result back
        findViewById<MaterialButton>(R.id.askAiPageButton).setOnClickListener { 
            openChatForCurrentPage() 
        }
        
        // Load the initial page
        loadPage()
    }

    private fun loadPage() {
        pageTitle.text   = "Page ${currentPage + 1}"
        pageCounter.text = "${currentPage + 1} / $pageCount"
        prevButton.isEnabled = currentPage > 0
        nextButton.isEnabled = currentPage < pageCount - 1

        loadingSpinner.visibility = View.VISIBLE
        loadingSpinner.start()
        pageImage.visibility = View.INVISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = pdfPageManager.getPage(pdfId, pdfAssetPath, currentPage)
                val bmp  = BitmapFactory.decodeFile(file.absolutePath)
                
                // Start preloading next 5 pages in background
                pdfPreloader.preloadAhead(pdfId, pdfAssetPath, currentPage, pdfPageManager, pageCount)
                
                withContext(Dispatchers.Main) {
                    loadingSpinner.stop()
                    loadingSpinner.visibility = View.GONE
                    if (bmp != null) {
                        pageImage.setImageBitmap(bmp)
                        pageImage.visibility = View.VISIBLE
                    } else {
                        Toast.makeText(this@PageViewerActivity, "Failed to render page", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingSpinner.stop()
                    loadingSpinner.visibility = View.GONE
                    Toast.makeText(this@PageViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openChatForCurrentPage() {
        val file = pdfPageManager.getCachedPage(pdfId, currentPage)
        if (file == null) {
            Toast.makeText(this, "Page not rendered yet — wait a moment", Toast.LENGTH_SHORT).show()
            return
        }
        // Return the page info to whoever launched us (FullChatFragment via its launcher).
        // This keeps navigation inside the tabbed ChapterActivity instead of opening
        // a standalone ChatHostActivity.
        setResult(
            android.app.Activity.RESULT_OK,
            android.content.Intent()
                .putExtra("pdfPageFilePath", file.absolutePath)
                .putExtra("pdfPageNumber", currentPage + 1)
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfPreloader.stop()
    }
}
