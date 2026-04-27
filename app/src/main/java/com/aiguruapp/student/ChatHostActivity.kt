package com.aiguruapp.student

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Thin host Activity for [FullChatFragment].
 *
 * This replaces the old heavyweight ChatActivity — all chat logic now lives
 * inside FullChatFragment so it can be shared between ChapterActivity (as a tab)
 * and this standalone screen.
 *
 * Intent extras (same contract as the old ChatActivity):
 *   subjectName      (String)  — subject label,            default "General"
 *   chapterName      (String)  — chapter / session title,  default "Study Session"
 *   autoPrompt       (String?) — optional: fire this message immediately on open
 *   saveNotesType    (String?) — optional: auto-save notes of given type
 *   pdfPageFilePath  (String?) — optional: attach and pre-load this PDF page
 *   pdfPageNumber    (Int)     — optional: 1-based page number,  default 1
 *   imagePath        (String?) — optional: attach this image path on open
 */
class ChatHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat_host)

        val subjectName     = intent.getStringExtra("subjectName")     ?: "General"
        val chapterName     = intent.getStringExtra("chapterName")     ?: "Study Session"
        val autoPrompt      = intent.getStringExtra("autoPrompt")
        val saveNotesType   = intent.getStringExtra("saveNotesType")
        val pdfPageFilePath = intent.getStringExtra("pdfPageFilePath")
        val pdfPageNumber   = intent.getIntExtra("pdfPageNumber", 1)
        val imagePath       = intent.getStringExtra("imagePath")

        // On config changes the fragment is already in the back-stack — reuse it.
        val fragment = (savedInstanceState?.let {
            supportFragmentManager.findFragmentByTag("chat") as? FullChatFragment
        } ?: FullChatFragment.newInstance(subjectName, chapterName).also { frag ->
            supportFragmentManager.beginTransaction()
                .replace(R.id.chatFragmentContainer, frag, "chat")
                .commitNow()
        })

        // Queue launch-time actions. FullChatFragment's internal pending-field
        // mechanism handles timing safely even before onViewCreated runs.
        if (autoPrompt != null) fragment.sendAutoPrompt(autoPrompt, saveNotesType)
        if (pdfPageFilePath != null) fragment.attachPdfPage(pdfPageFilePath, pdfPageNumber)
        if (imagePath != null) fragment.attachImage(imagePath)
    }
}
