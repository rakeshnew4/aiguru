package com.example.aiguru.firestore

import android.util.Log
import com.example.aiguru.models.FirestoreOffer
import com.example.aiguru.models.FirestorePlan
import com.example.aiguru.models.PageContent
import com.example.aiguru.models.UserMetadata
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
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

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance().also { firestore ->
        // Enable offline disk persistence so Firestore data (including blackboard
        // cache) is available instantly from local disk on repeat opens.
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(50L * 1024 * 1024) // 50 MB cache
                        .build()
                )
                .build()
            firestore.firestoreSettings = settings
        } catch (_: Exception) {
            // Already configured — safe to ignore
        }
    }

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
     *
     * @param planStartDate  Epoch-ms when the plan was activated (0 = unchanged).
     * @param planExpiryDate Epoch-ms when the plan expires (0 = unchanged / no expiry).
     */
    fun updateUserPlan(
        userId: String,
        planId: String,
        planName: String,
        planStartDate: Long = 0L,
        planExpiryDate: Long = 0L,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") {
            onFailure(null)
            return
        }
        val updates = mutableMapOf<String, Any>(
            "planId"    to planId,
            "planName"  to planName,
            "updatedAt" to System.currentTimeMillis()
        )
        if (planStartDate > 0) updates["plan_start_date"]  = planStartDate
        if (planExpiryDate > 0) updates["plan_expiry_date"] = planExpiryDate
        db.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    // ── App-level plans & offers (Firestore-managed) ───────────────────────────

    /**
     * Fetch active plans from [plans] collection, sorted by display_order.
     * YOU must create and manage this collection manually in Firestore.
     *
     * Document structure:
     *   name, badge, price_inr, duration, validity_days, features[],
     *   display_order, is_active, accent_color
     */
    fun fetchPlans(
        onSuccess: (List<FirestorePlan>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("plans")
            .whereEqualTo("is_active", true)
            .orderBy("display_order")
            .get()
            .addOnSuccessListener { snap ->
                val plans = snap.documents.mapNotNull { doc ->
                    doc.toObject(FirestorePlan::class.java)?.copy(id = doc.id)
                }
                onSuccess(plans)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Fetch active offer/announcement cards from [app_offers] collection.
     * YOU must create and manage this collection manually in Firestore.
     *
     * Document structure:
     *   title, subtitle, emoji, background_color, display_order, is_active
     */
    fun fetchOffers(
        onSuccess: (List<FirestoreOffer>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("app_offers")
            .whereEqualTo("is_active", true)
            .orderBy("display_order")
            .get()
            .addOnSuccessListener { snap ->
                val offers = snap.documents.mapNotNull { doc ->
                    doc.toObject(FirestoreOffer::class.java)?.copy(id = doc.id)
                }
                onSuccess(offers)
            }
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
        pdfAssetPath: String = "",
        ncertUrl: String = ""
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val doc = mapOf(
            "name"         to chapterName,
            "isPdf"        to isPdf,
            "pdfAssetPath" to pdfAssetPath,
            "ncertUrl"     to ncertUrl,
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
        tokens: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        imageUrl: String? = null,
        transcription: String = "",
        extraSummary: String = ""
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val cid = convId(subject, chapter)
        val convRef = convsRef(userId).document(cid)
        Log.d("TokenDebug", "[Firestore] saveMessage uid=$userId cid=$cid role=$role tokens=$tokens in=$inputTokens out=$outputTokens")

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
        if (inputTokens != null && inputTokens > 0) {
            convMeta["totalInputTokens"] = FieldValue.increment(inputTokens.toLong())
        }
        if (outputTokens != null && outputTokens > 0) {
            convMeta["totalOutputTokens"] = FieldValue.increment(outputTokens.toLong())
        }
        convRef.set(convMeta, SetOptions.merge())
            .addOnFailureListener { Log.e("Firestore", "saveMessage header failed uid=$userId cid=$cid: ${it.message}") }

        // Save message
        val msgDoc = mutableMapOf<String, Any>(
            "messageId" to messageId,
            "role"      to role,
            "text"      to text,
            "timestamp" to timestamp
        )
        if (tokens != null) msgDoc["tokens"] = tokens
        if (inputTokens != null) msgDoc["inputTokens"] = inputTokens
        if (outputTokens != null) msgDoc["outputTokens"] = outputTokens
        if (imageUrl != null) msgDoc["imageUrl"] = imageUrl
        if (transcription.isNotBlank()) msgDoc["transcription"] = transcription
        if (extraSummary.isNotBlank()) msgDoc["extraSummary"] = extraSummary
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
                onSuccess(snap.documents.mapNotNull { doc ->
                    // Always include the Firestore doc ID as "_docId" so callers
                    // can use it as a stable message ID even for pre-migration docs
                    // that don't yet have a "messageId" field in their body.
                    doc.data?.toMutableMap()?.also { it["_docId"] = doc.id }
                })
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Delete all messages in a conversation and reset its lastMessage field.
     */
    fun deleteAllMessages(
        userId: String,
        subject: String,
        chapter: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        val cid = convId(subject, chapter)
        val convRef = convsRef(userId).document(cid)
        convRef.collection("messages").get()
            .addOnSuccessListener { snap ->
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.set(convRef,
                    mapOf("lastMessage" to "", "updatedAt" to System.currentTimeMillis()),
                    SetOptions.merge())
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { onFailure(it) }
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

