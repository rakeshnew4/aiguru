package com.aiguruapp.student

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shows all saved BB sessions for a specific subject + chapter.
 * Allows replaying a session (re-launches BlackboardActivity with the same question)
 * or deleting a saved entry.
 */
class BbSavedSessionsActivity : BaseActivity() {

    companion object {
        const val EXTRA_SUBJECT = "extra_subject"
        const val EXTRA_CHAPTER = "extra_chapter"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: SessionsAdapter
    private val sessions = mutableListOf<Map<String, Any>>()
    private val allSessions = mutableListOf<Map<String, Any>>()
    private var activeFilter: String? = null

    private lateinit var subject: String
    private lateinit var chapter: String
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bb_saved_sessions)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "Subject"
        chapter = intent.getStringExtra(EXTRA_CHAPTER) ?: "Chapter"
        userId  = SessionManager.getFirestoreUserId(this)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.chapterSubtitle).text = "$subject › $chapter"

        emptyState   = findViewById(R.id.emptyState)
        recyclerView = findViewById(R.id.sessionsList)

        adapter = SessionsAdapter(
            sessions  = sessions,
            onReplay  = { session -> replaySession(session) },
            onDelete  = { session -> confirmDelete(session) },
            onShare   = { session -> shareSession(session) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyStateStartBbBtn)
            .setOnClickListener {
                finish()
                startActivity(Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }

        loadSessions()
    }

    private fun loadSessions() {
        FirestoreManager.loadAllSavedBbSessions(
            userId = userId,
            onSuccess = { list ->
                allSessions.clear()
                allSessions.addAll(list)
                applyFilter(activeFilter)
                buildFilterChips()
            },
            onFailure = {
                Toast.makeText(this, "Couldn't load sessions", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun applyFilter(subject: String?) {
        activeFilter = subject
        sessions.clear()
        sessions.addAll(if (subject == null) allSessions
                        else allSessions.filter { (it["subject"] as? String) == subject })
        adapter.notifyDataSetChanged()
        val empty = sessions.isEmpty()
        emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun buildFilterChips() {
        val scroll    = findViewById<android.widget.HorizontalScrollView>(R.id.filterScrollView) ?: return
        val container = findViewById<android.widget.LinearLayout>(R.id.filterChipsContainer) ?: return
        val subjects  = allSessions.mapNotNull { it["subject"] as? String }
            .filter { it.isNotBlank() }.distinct().sorted()
        if (subjects.isEmpty()) { scroll.visibility = View.GONE; return }

        container.removeAllViews()
        scroll.visibility = View.VISIBLE
        val dp = resources.displayMetrics.density

        fun makeChip(label: String, filterValue: String?): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                val isActive = filterValue == activeFilter
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * dp
                    setColor(if (isActive) android.graphics.Color.parseColor("#3D1A6E")
                             else android.graphics.Color.parseColor("#252840"))
                    setStroke((1 * dp).toInt(),
                        if (isActive) android.graphics.Color.parseColor("#7B52CC")
                        else android.graphics.Color.parseColor("#33AABBCC"))
                }
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
                setOnClickListener { applyFilter(filterValue); buildFilterChips() }
            }
        }

        container.addView(makeChip("All", null))
        subjects.forEach { container.addView(makeChip(it, it)) }
    }

    private fun replaySession(session: Map<String, Any>) {
        val topic     = session["topic"] as? String ?: return
        val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: ""
        val convId    = session["conversation_id"] as? String
        val msgId     = session["message_id"] as? String
        @Suppress("UNCHECKED_CAST")
        val ttsKeys = (session["tts_keys"] as? List<String>) ?: emptyList()
        val intent = Intent(this, BlackboardActivity::class.java).apply {
            putExtra(BlackboardActivity.EXTRA_MESSAGE, topic)
            putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
            putExtra(BlackboardActivity.EXTRA_SUBJECT, subject)
            putExtra(BlackboardActivity.EXTRA_CHAPTER, chapter)
            putExtra(BlackboardActivity.EXTRA_IS_REPLAY, true)
            // Pass sessionId so BlackboardActivity loads steps directly from Firestore
            // without any LLM regeneration.
            if (sessionId.isNotBlank()) putExtra(BlackboardActivity.EXTRA_SESSION_ID, sessionId)
            if (ttsKeys.isNotEmpty()) putExtra(BlackboardActivity.EXTRA_TTS_KEYS, ArrayList(ttsKeys))
            if (!convId.isNullOrBlank()) putExtra(BlackboardActivity.EXTRA_CONVERSATION_ID, convId)
            if (!msgId.isNullOrBlank())  putExtra(BlackboardActivity.EXTRA_MESSAGE_ID, msgId)
        }
        startActivity(intent)
    }

    private fun shareSession(session: Map<String, Any>) {
        val topic = session["topic"] as? String ?: return
        val stepCount = (session["step_count"] as? Long)?.toInt() ?: (session["step_count"] as? Int) ?: 0
        val steps = if (stepCount > 0) "$stepCount steps" else "BB Session"
        val text = "I just studied \"$topic\" on AfterClass AI!\n\n📚 $steps covered step by step with AI lessons. 🎓"
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
            "Share lesson"
        ))
    }

    private fun confirmDelete(session: Map<String, Any>) {
        val sessionId = session["session_id"] as? String ?: session["id"] as? String ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Session?")
            .setMessage("Remove this saved session from chapter notes?")
            .setPositiveButton("Delete") { _, _ ->
                FirestoreManager.deleteBbSession(
                    userId    = userId,
                    subject   = subject,
                    chapter   = chapter,
                    sessionId = sessionId,
                    onSuccess = {
                        allSessions.removeAll { (it["session_id"] ?: it["id"]) == sessionId }
                        val idx = sessions.indexOfFirst {
                            (it["session_id"] ?: it["id"]) == sessionId
                        }
                        if (idx >= 0) {
                            sessions.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                        if (sessions.isEmpty()) {
                            emptyState.visibility   = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        }
                        buildFilterChips()
                    },
                    onFailure = {
                        Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class SessionsAdapter(
        private val sessions: List<Map<String, Any>>,
        private val onReplay: (Map<String, Any>) -> Unit,
        private val onDelete: (Map<String, Any>) -> Unit,
        private val onShare:  (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<SessionsAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val topic:     TextView = view.findViewById(R.id.sessionTopic)
            val stepCount: TextView = view.findViewById(R.id.sessionStepCount)
            val date:      TextView = view.findViewById(R.id.sessionDate)
            val replayBtn: TextView = view.findViewById(R.id.replayBtn)
            val deleteBtn: TextView = view.findViewById(R.id.deleteBtn)
            val shareBtn:  TextView = view.findViewById(R.id.shareBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bb_saved_session, parent, false)
            return VH(view)
        }

        override fun getItemCount() = sessions.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val session = sessions[position]
            val topic     = (session["topic"] as? String)?.trim() ?: "(no topic)"
            val stepCount = (session["step_count"] as? Long)?.toInt() ?: (session["step_count"] as? Int) ?: 0
            val savedAt   = (session["saved_at"] as? Long) ?: 0L

            holder.topic.text     = topic
            holder.stepCount.text = if (stepCount > 0) "📚 $stepCount steps" else "📚 BB Session"
            holder.date.text      = if (savedAt > 0) dateFormat.format(Date(savedAt)) else ""
            holder.replayBtn.setOnClickListener { onReplay(session) }
            holder.deleteBtn.setOnClickListener { onDelete(session) }
            holder.shareBtn.setOnClickListener  { onShare(session)  }
        }
    }
}
