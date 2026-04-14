package com.aiguruapp.student.firestore

import android.util.Log
import com.aiguruapp.student.models.FirestoreOffer
import com.aiguruapp.student.models.FirestorePlan
import com.aiguruapp.student.models.PageContent
import com.aiguruapp.student.models.UserMetadata
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source

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

    /**
     * Primary user profile + plan collection. Written exclusively by the server
     * (admin SDK / FastAPI) to prevent client-side plan tampering. Android reads only.
     * Plan activations are written here by the server after payment verification.
     */
    private fun usersTableRef(userId: String) = db.collection("users_table").document(userId)

    /**
     * Legacy reference for conversation/subject/chapter subcollections under /users/{userId}.
     * Android may write subject and conversation data here. Plan data goes to users_table.
     */
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
        
        db.collection("users_table").document(userId)
            .set(docData, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }

        // Mirror identity fields to /users/{userId} so the legacy collection
        // document exists for conversation subcollections and any legacy reads.
        val identityData = mapOf(
            "userId"     to userId,
            "name"       to docData.name,
            "email"      to (docData.email ?: ""),
            "grade"      to docData.grade,
            "schoolId"   to docData.schoolId,
            "schoolName" to docData.schoolName,
            "updatedAt"  to now
        )
        db.collection("users").document(userId)
            .set(identityData, SetOptions.merge())
            // fire-and-forget — don't propagate failure to callers of saveUserMetadata
    }

    /**
     * Removes any ProGuard-renamed single-letter fields (a, b, c … h) that were
     * written by older builds where the ProGuard keep rules had the wrong package name.
     * Safe to call even if these fields don't exist — Firestore ignores missing fields
     * in an update() call's FieldValue.delete() entries.
     */
    fun cleanupMangledFields(userId: String) {
        if (userId.isBlank() || userId == "guest_user") return
        db.collection("users_table").document(userId)
            .update(
                mapOf(
                    "a" to FieldValue.delete(),
                    "b" to FieldValue.delete(),
                    "c" to FieldValue.delete(),
                    "d" to FieldValue.delete(),
                    "e" to FieldValue.delete(),
                    "f" to FieldValue.delete(),
                    "g" to FieldValue.delete(),
                    "h" to FieldValue.delete()
                )
            )
            .addOnFailureListener { /* ignore — fields may not exist */ }
    }

    /**
     * Get user metadata.
     *
     * @param forceServer  When true, bypasses the local Firestore disk cache and
     *                     always fetches fresh data from the server. Use this
     *                     when displaying quota counts that may have just been
     *                     incremented by another screen (e.g. HomeActivity after
     *                     returning from chat).
     */
    fun getUserMetadata(
        userId: String,
        onSuccess: (UserMetadata?) -> Unit,
        onFailure: (Exception?) -> Unit = {},
        forceServer: Boolean = false
    ) {
        val source = if (forceServer) Source.SERVER else Source.DEFAULT
        // Read from users_table (server-managed). Falls back gracefully if doc absent.
        db.collection("users_table").document(userId)
            .get(source)
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
        db.collection("users_table").document(userId)
            .update(field, value)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Update subscription plan fields for a user after successful payment verification.
     * Optionally writes a snapshot of the plan's effective limits as flat fields so that
     * quota enforcement works correctly even when admin config is not yet loaded.
     *
     * @param planStartDate  Epoch-ms when the plan was activated (0 = unchanged).
     * @param planExpiryDate Epoch-ms when the plan expires (0 = unchanged / no expiry).
     * @param limits         Effective PlanLimits for this plan; written as flat fields when non-null.
     */
    fun updateUserPlan(
        userId: String,
        planId: String,
        planName: String,
        planStartDate: Long = 0L,
        planExpiryDate: Long = 0L,
        limits: com.aiguruapp.student.models.PlanLimits? = null,
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

        // Persist the plan's effective quotas/feature flags so they are always
        // available in the user document for cross-device quota enforcement.
        if (limits != null) {
            updates["plan_daily_chat_limit"]  = limits.dailyChatQuestions
            updates["plan_daily_bb_limit"]    = limits.dailyBlackboardSessions
            updates["plan_tts_enabled"]       = limits.ttsEnabled
            updates["plan_ai_tts_enabled"]    = limits.aiTtsEnabled
            updates["plan_blackboard_enabled"] = limits.blackboardEnabled
            updates["plan_image_enabled"]     = limits.imageUploadEnabled
        }

        db.collection("users_table").document(userId)
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
        // Fetch all docs without a compound query (avoids requiring a composite index).
        db.collection("plans")
            .get()
            .addOnSuccessListener { snap ->
                val plans = snap.documents
                    .mapNotNull { doc -> doc.toObject(FirestorePlan::class.java)?.copy(id = doc.id) }
                    .filter { it.isActive }
                    .sortedBy { it.displayOrder }
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
        // Fetch all docs without a compound query (avoids requiring a composite index).
        // Filter is_active and sort by display_order client-side.
        // Always fetch from server to avoid stale Firestore cache.
        db.collection("app_offers")
            .get(Source.SERVER)
            .addOnSuccessListener { snap ->
                val offers = snap.documents
                    .mapNotNull { doc -> doc.toObject(FirestoreOffer::class.java)?.copy(id = doc.id) }
                    .filter { it.isActive }
                    .sortedBy { it.displayOrder }
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

    // ── Account Deletion ──────────────────────────────────────────────────────

    /**
     * Permanently deletes all Firestore data for the given user:
     *   - subjects/{id}/chapters/{id}
     *   - conversations/{id}/messages/{id}
     *   - referralCodes where ownerUserId == userId
     *   - users/{userId}  (the root document)
     *
     * Subcollections are deleted recursively, subject to Firestore batch limits.
     * Call this AFTER re-authenticating and BEFORE calling FirebaseAuth.delete().
     */
    fun deleteUserData(
        userId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (userId.isBlank() || userId == "guest_user") { onSuccess(); return }
        val userRef = usersRef(userId)

        // Step 1: delete subjects + chapters
        userRef.collection("subjects").get()
            .addOnSuccessListener { subjectSnap ->
                deleteSubjectsRecursive(userId, subjectSnap.documents.map { it.id }, 0) {
                    // Step 2: delete conversations + messages
                    userRef.collection("conversations").get()
                        .addOnSuccessListener { convSnap ->
                            deleteConversationsRecursive(userId, convSnap.documents.map { it.id }, 0) {
                                // Step 3: delete referral code owned by this user
                                db.collection("referralCodes")
                                    .whereEqualTo("ownerUserId", userId)
                                    .get()
                                    .addOnSuccessListener { refSnap ->
                                        val batch = db.batch()
                                        refSnap.documents.forEach { batch.delete(it.reference) }
                                        batch.delete(userRef)
                                        batch.commit()
                                            .addOnSuccessListener { onSuccess() }
                                            .addOnFailureListener { onFailure(it) }
                                    }
                                    .addOnFailureListener { onFailure(it) }
                            }
                        }
                        .addOnFailureListener { onFailure(it) }
                }
            }
            .addOnFailureListener { onFailure(it) }
    }

    private fun deleteSubjectsRecursive(
        userId: String,
        subjectIds: List<String>,
        index: Int,
        onDone: () -> Unit
    ) {
        if (index >= subjectIds.size) { onDone(); return }
        val subjectRef = usersRef(userId).collection("subjects").document(subjectIds[index])
        subjectRef.collection("chapters").get()
            .addOnSuccessListener { chapSnap ->
                val batch = db.batch()
                chapSnap.documents.forEach { batch.delete(it.reference) }
                batch.delete(subjectRef)
                batch.commit().addOnCompleteListener {
                    deleteSubjectsRecursive(userId, subjectIds, index + 1, onDone)
                }
            }
            .addOnFailureListener {
                // best-effort: continue even if this subject fails
                deleteSubjectsRecursive(userId, subjectIds, index + 1, onDone)
            }
    }

    private fun deleteConversationsRecursive(
        userId: String,
        convIds: List<String>,
        index: Int,
        onDone: () -> Unit
    ) {
        if (index >= convIds.size) { onDone(); return }
        val convRef = convsRef(userId).document(convIds[index])
        convRef.collection("messages").get()
            .addOnSuccessListener { msgSnap ->
                val batch = db.batch()
                msgSnap.documents.forEach { batch.delete(it.reference) }
                batch.delete(convRef)
                batch.commit().addOnCompleteListener {
                    deleteConversationsRecursive(userId, convIds, index + 1, onDone)
                }
            }
            .addOnFailureListener {
                deleteConversationsRecursive(userId, convIds, index + 1, onDone)
            }
    }

}

