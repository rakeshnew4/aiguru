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
            onDelete  = { session -> confirmDelete(session) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadSessions()
    }

    private fun loadSessions() {
        // Use flat mirror collection — shows all sessions regardless of subject/chapter.
        // Sessions saved from general BB mode default chapter="General", not "General Chat",
        // so querying by chapter would miss them.
        FirestoreManager.loadAllSavedBbSessions(
            userId = userId,
            onSuccess = { list ->
                sessions.clear()
                sessions.addAll(list)
                adapter.notifyDataSetChanged()
                if (sessions.isEmpty()) {
                    emptyState.visibility   = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility   = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            },
            onFailure = {
                Toast.makeText(this, "Couldn't load sessions", Toast.LENGTH_SHORT).show()
            }
        )
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
                        val idx = sessions.indexOfFirst {
                            (it["session_id"] ?: it["id"]) == sessionId
                        }
                        if (idx >= 0) {
                            sessions.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                        if (sessions.isEmpty()) {
                            emptyState.visibility  = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        }
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
        private val onDelete: (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<SessionsAdapter.VH>() {

        private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val topic:     TextView = view.findViewById(R.id.sessionTopic)
            val stepCount: TextView = view.findViewById(R.id.sessionStepCount)
            val date:      TextView = view.findViewById(R.id.sessionDate)
            val replayBtn: TextView = view.findViewById(R.id.replayBtn)
            val deleteBtn: TextView = view.findViewById(R.id.deleteBtn)
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
        }
    }
}
