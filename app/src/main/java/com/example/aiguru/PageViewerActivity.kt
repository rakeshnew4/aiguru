package com.example.aiguru

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.utils.PdfPageManager
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
    private lateinit var loadingSpinner: ProgressBar
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page_viewer)

        subjectName  = intent.getStringExtra("subjectName")  ?: "Subject"
        chapterName  = intent.getStringExtra("chapterName")  ?: "Chapter"
        pdfId        = intent.getStringExtra("pdfId")        ?: ""
        pdfAssetPath = intent.getStringExtra("pdfAssetPath") ?: ""
        pageCount    = intent.getIntExtra("pageCount", 1)
        currentPage  = intent.getIntExtra("startPage", 0)

        pdfPageManager = PdfPageManager(this)

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
        findViewById<MaterialButton>(R.id.askAiPageButton).setOnClickListener {
            openChatForCurrentPage()
        }

        loadPage()
    }

    private fun loadPage() {
        pageTitle.text   = "Page ${currentPage + 1}"
        pageCounter.text = "${currentPage + 1} / $pageCount"
        prevButton.isEnabled = currentPage > 0
        nextButton.isEnabled = currentPage < pageCount - 1

        loadingSpinner.visibility = View.VISIBLE
        pageImage.visibility = View.INVISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = pdfPageManager.getPage(pdfId, pdfAssetPath, currentPage)
                val bmp  = BitmapFactory.decodeFile(file.absolutePath)
                withContext(Dispatchers.Main) {
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
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra("subjectName", subjectName)
                .putExtra("chapterName", chapterName)
                .putExtra("pdfPageFilePath", file.absolutePath)
                .putExtra("pdfPageNumber", currentPage + 1)
        )
    }
}
