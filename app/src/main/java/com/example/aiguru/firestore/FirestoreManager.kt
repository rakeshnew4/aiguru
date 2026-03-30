package com.example.aiguru.firestore

import android.util.Log
import com.example.aiguru.models.PageContent
import com.example.aiguru.models.UserMetadata
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * Centralized Firestore operations for user metadata, subjects, chapters, and conversations.
 *
 * Firestore structure:
 *   users/{userId}                                             ← UserMetadata
 *   users/{userId}/subjects/{subjectId}                       ← {name, createdAt}
 *   users/{userId}/subjects/{subjectId}/chapters/{chapterId}  ← {name, isPdf, ...}
 *
 *   users/{userId}/conversations/{conversationId}            ← {subject, chapter, createdAt, lastMessage, summary}
 *   users/{userId}/conversations/{conversationId}/messages/{messageId}  ← {role, text, timestamp, tokens?}
 */
object FirestoreManager {
    
    private val db = FirebaseFirestore.getInstance()

    // ── ID sanitization ────────────────────────────────────────────────────────
    // Firestore document IDs must not contain '/'
    fun safeId(name: String): String =
        name.trim().replace(Regex("[/\\\\#%?*\\[\\]]"), "_").take(100)

    private fun usersRef(userId: String) = db.collection("users").document(userId)
    
    /**
     * Save or update user metadata document.
     */
    fun saveUserMetadata(
        metadata: UserMetadata,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        val userId = metadata.userId
        if (userId.isBlank() || userId == "guest_user") {
            onFailure(null); return
        }
        val now = System.currentTimeMillis()
        val docData = metadata.copy(
            updatedAt = now,
            createdAt = if (metadata.createdAt == 0L) now else metadata.createdAt
        )
        
        db.collection("users").document(userId)
            .set(docData, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }
    
    /**
     * Get user metadata.
     */
    fun getUserMetadata(
        userId: String,
        onSuccess: (UserMetadata?) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    onSuccess(doc.toObject(UserMetadata::class.java))
                } else {
                    onSuccess(null)
                }
            }
            .addOnFailureListener { onFailure(it) }
    }
    
    /**
     * Update specific field.
     */
    fun updateUserField(
        userId: String,
        field: String,
        value: Any,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("users").document(userId)
            .update(field, value)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Update subscription plan fields for a user after successful payment verification.
     */
    fun updateUserPlan(
        userId: String,
        planId: String,
        planName: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") {
            onFailure(null)
            return
        }
        val updates = mapOf(
            "planId" to planId,
            "planName" to planName,
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Subjects ────────────────────────────────────────────────────────────────

    fun saveSubject(userId: String, subjectName: String) {
        if (userId.isBlank() || userId == "guest_user") return
        val doc = mapOf("name" to subjectName, "createdAt" to System.currentTimeMillis())
        usersRef(userId).collection("subjects").document(safeId(subjectName))
            .set(doc, SetOptions.merge())
            .addOnFailureListener { Log.e("Firestore", "saveSubject failed uid=$userId: ${it.message}") }
    }

    fun deleteSubject(userId: String, subjectName: String) {
        if (userId.isBlank() || userId == "guest_user") return
        usersRef(userId).collection("subjects").document(safeId(subjectName)).delete()
    }

    fun loadSubjects(
        userId: String,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId).collection("subjects")
            .orderBy("createdAt")
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { it.getString("name") })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Chapters ────────────────────────────────────────────────────────────────

    fun saveChapter(
        userId: String,
        subjectName: String,
        chapterName: String,
        isPdf: Boolean = false,
        pdfAssetPath: String = ""
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val doc = mapOf(
            "name"         to chapterName,
            "isPdf"        to isPdf,
            "pdfAssetPath" to pdfAssetPath,
            "createdAt"    to System.currentTimeMillis()
        )
        usersRef(userId).collection("subjects").document(safeId(subjectName))
            .collection("chapters").document(safeId(chapterName))
            .set(doc, SetOptions.merge())
            .addOnFailureListener { Log.e("Firestore", "saveChapter failed uid=$userId: ${it.message}") }
    }

    fun deleteChapter(userId: String, subjectName: String, chapterName: String) {
        if (userId.isBlank() || userId == "guest_user") return
        usersRef(userId).collection("subjects").document(safeId(subjectName))
            .collection("chapters").document(safeId(chapterName))
            .delete()
    }

    fun loadChapters(
        userId: String,
        subjectName: String,
        onSuccess: (List<String>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId).collection("subjects").document(safeId(subjectName))
            .collection("chapters")
            .orderBy("createdAt")
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { it.getString("name") })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Conversations / Messages ────────────────────────────────────────────────
    // Stored under users/{userId}/conversations/ — covered by existing Firestore rules.
    // conversationId = "{safeSubject}__{safeChapter}"

    fun convId(subject: String, chapter: String): String =
        "${safeId(subject)}__${safeId(chapter)}".take(200)

    private fun convsRef(userId: String) = usersRef(userId).collection("conversations")

    /**
     * Upsert a message into users/{userId}/conversations/{convId}/messages/{messageId}.
     * Also updates conversation-level lastMessage.
     */
    fun saveMessage(
        userId: String,
        subject: String,
        chapter: String,
        messageId: String,
        text: String,
        role: String,          // "user" or "model"
        timestamp: Long,
        tokens: Int? = null
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val cid = convId(subject, chapter)
        val convRef = convsRef(userId).document(cid)
        Log.d("TokenDebug", "[Firestore] saveMessage uid=$userId cid=$cid role=$role tokens=$tokens")

        // Upsert conversation header — token increment is merged atomically in the same write
        // so it works even on a brand-new document (update() would fail if doc doesn't exist yet)
        val convMeta = mutableMapOf<String, Any>(
            "subject"     to subject,
            "chapter"     to chapter,
            "lastMessage" to text.take(120),
            "updatedAt"   to timestamp,
            "createdAt"   to timestamp
        )
        if (tokens != null && tokens > 0) {
            convMeta["totalTokens"] = FieldValue.increment(tokens.toLong())
        }
        convRef.set(convMeta, SetOptions.merge())
            .addOnFailureListener { Log.e("Firestore", "saveMessage header failed uid=$userId cid=$cid: ${it.message}") }

        // Save message
        val msgDoc = mutableMapOf<String, Any>(
            "role"      to role,
            "text"      to text,
            "timestamp" to timestamp
        )
        if (tokens != null) msgDoc["tokens"] = tokens
        convRef.collection("messages").document(messageId).set(msgDoc)
            .addOnFailureListener { Log.e("Firestore", "saveMessage msg failed uid=$userId msgId=$messageId: ${it.message}") }
    }

    /**
     * Update the summary field on the conversation document.
     */
    fun updateConversationSummary(
        userId: String,
        subject: String,
        chapter: String,
        summary: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        convsRef(userId).document(convId(subject, chapter))
            .set(mapOf("summary" to summary), SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    fun loadMessages(
        userId: String,
        subject: String,
        chapter: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        convsRef(userId).document(convId(subject, chapter))
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { it.data })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Conversation Context (latest analyzed page for chapter chat) ─────────

    /**
     * Save chapter-level context on conversation document for LLM prompting.
     * Latest uploaded page context replaces any previous page context.
     * Path: users/{uid}/conversations/{subject__chapter}
     */
    fun saveChapterContext(
        userId: String,
        page: PageContent,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        // Resolve the effective userId — prefer the passed-in value, but fall back to
        // live Firebase Auth in case the session wasn't written on this device/login path.
        val effectiveUserId = userId.takeIf { it.isNotBlank() && it != "guest_user" }
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (effectiveUserId.isBlank()) {
            android.util.Log.e("PageContext",
                "saveChapterContext BLOCKED: no valid userId (passed='$userId', " +
                "FirebaseAuth.currentUser=null). Check login flow.")
            onFailure(null)
            return
        }
        val cid = convId(page.subject, page.chapter)
        val doc = mapOf(
            "subject" to page.subject,
            "chapter" to page.chapter,
            "updatedAt" to System.currentTimeMillis(),
            "createdAt" to System.currentTimeMillis(),
            "systemContext" to page.transcript,
            "systemContextPageId" to page.pageId,
            "systemContextUpdatedAt" to System.currentTimeMillis(),
            "systemContextSourceType" to page.sourceType,
            "systemContextPageNumber" to page.pageNumber,
            "systemContextKeyTerms" to page.keyTerms,
            "systemContextParagraphsJson" to page.paragraphsJson,
            "systemContextDiagramsJson" to page.diagramsJson
        )
        convsRef(effectiveUserId).document(cid)
            .set(doc, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load chapter-level context fields used by the tutor system prompt.
     * Reads optional fields from conversation document:
     * - summary
     * - systemContext
     */
    fun loadChapterContext(
        userId: String,
        subject: String,
        chapter: String,
        onSuccess: (summary: String?, systemContext: String?) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") {
            onFailure(null)
            return
        }
        convsRef(userId).document(convId(subject, chapter))
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onSuccess(null, null)
                    return@addOnSuccessListener
                }
                onSuccess(doc.getString("summary"), doc.getString("systemContext"))
            }
            .addOnFailureListener { onFailure(it) }
    }

}

