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
            .limitToLast(30)
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

    // ── Guest Device Management ───────────────────────────────────────────────

    /**
     * Initialize a guest device in /devices collection with initial quota.
     * Called when user taps "Continue as Guest".
     */
    fun initializeGuestDevice(
        deviceId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        val deviceData = mapOf(
            "device_id" to deviceId,
            "created_at" to System.currentTimeMillis(),
            "guest_chat_used" to 0,
            "guest_bb_used" to 0,
            "guest_signup_date" to System.currentTimeMillis(),
            "linked_user_id" to null,
            "linked_at" to null
        )
        db.collection("devices").document(deviceId)
            .set(deviceData, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Fetch guest device quota usage.
     */
    fun getGuestDeviceQuota(
        deviceId: String,
        onSuccess: (guestChatUsed: Int, guestBbUsed: Int) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("devices").document(deviceId).get()
            .addOnSuccessListener { snap ->
                val chatUsed = snap.getLong("guest_chat_used")?.toInt() ?: 0
                val bbUsed = snap.getLong("guest_bb_used")?.toInt() ?: 0
                onSuccess(chatUsed, bbUsed)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Increment guest quota after successful question/blackboard session.
     */
    fun recordGuestUsage(
        deviceId: String,
        isBlackboard: Boolean,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        val fieldToIncrement = if (isBlackboard) "guest_bb_used" else "guest_chat_used"
        db.collection("devices").document(deviceId)
            .update(fieldToIncrement, FieldValue.increment(1))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * When user logs in from guest mode, link the device to their account.
     */
    fun linkDeviceToUser(
        deviceId: String,
        userId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("devices").document(deviceId)
            .update(
                mapOf(
                    "linked_user_id" to userId,
                    "linked_at" to System.currentTimeMillis()
                )
            )
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Saved BB Sessions ────────────────────────────────────────────────────────
    // Path: users/{uid}/subjects/{subjectId}/chapters/{chapterId}/bb_sessions/{sessionId}

    /**
     * Save blackboard session metadata so users can revisit sessions per chapter.
     * Only metadata is stored — no LLM content.
     */
    fun saveBbSession(
        userId: String,
        subject: String,
        chapter: String,
        sessionId: String,
        messageId: String,
        conversationId: String,
        topic: String,
        stepCount: Int,
        ttsKeys: List<String> = emptyList(),
        stepsJson: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        val preview = topic.trim().take(120)
        val data = mapOf(
            "session_id"      to sessionId,
            "message_id"      to messageId,
            "conversation_id" to conversationId,
            "topic"           to topic,
            "subject"         to subject,
            "chapter"         to chapter,
            "step_count"      to stepCount,
            "preview"         to preview,
            "saved_at"        to System.currentTimeMillis(),
            "tts_keys"        to ttsKeys,
            "steps_json"      to stepsJson
        )
        usersRef(userId)
            .collection("subjects").document(safeId(subject))
            .collection("chapters").document(safeId(chapter))
            .collection("bb_sessions").document(sessionId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }

        // Also write to flat collection so we can list all sessions without subject/chapter
        usersRef(userId)
            .collection("saved_bb_sessions_flat").document(sessionId)
            .set(data, SetOptions.merge())
    }

    /**
     * Load all saved BB sessions for a user across ALL subjects/chapters.
     * Uses the flat mirror collection written by saveBbSession.
     */
    fun loadAllSavedBbSessions(
        userId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId)
            .collection("saved_bb_sessions_flat")
            .orderBy("saved_at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .addOnSuccessListener { snap ->
                val sessions = snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                }
                onSuccess(sessions)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load all saved BB sessions for a chapter, sorted by saved_at descending.
     */
    fun loadBbSessions(
        userId: String,
        subject: String,
        chapter: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId)
            .collection("subjects").document(safeId(subject))
            .collection("chapters").document(safeId(chapter))
            .collection("bb_sessions")
            .orderBy("saved_at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val sessions = snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                }
                onSuccess(sessions)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Delete a saved BB session.
     */
    fun deleteBbSession(
        userId: String,
        subject: String,
        chapter: String,
        sessionId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId)
            .collection("subjects").document(safeId(subject))
            .collection("chapters").document(safeId(chapter))
            .collection("bb_sessions").document(sessionId)
            .delete()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }

        // Mirror delete on flat collection
        usersRef(userId)
            .collection("saved_bb_sessions_flat").document(sessionId)
            .delete()
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────
    // Path: school_tasks/{taskId}  (global, filtered by school_id + grade)
    // task_type: "bb_lesson" | "quiz" | "both"
    //
    // New fields (replacing heavy embedded data):
    //   bb_cache_id → references bb_cache/{id} (teacher's shared lesson)
    //   quiz_id     → references quizzes/{id}  (teacher's validated quiz)
    //   bb_topic    → kept as display text (set from bb_cache.preview)
    // The old quiz_json field is no longer written for new tasks.

    /**
     * Create or update a task assigned by a teacher to a school/grade.
     *
     * For new tasks use [bbCacheId] + [quizId] instead of [bbTopic] + [quizJson].
     * Legacy tasks that still carry quiz_json will keep working via TasksActivity
     * fallback logic.
     */
    fun saveTask(
        taskId: String,
        teacherId: String,
        schoolId: String,
        grade: String,
        title: String,
        description: String,
        taskType: String,
        subject: String,
        chapter: String,
        bbTopic: String = "",       // display text / fallback topic for older tasks
        quizJson: String = "",      // DEPRECATED — use quizId instead
        bbCacheId: String = "",     // NEW: reference to bb_cache/{id}
        quizId: String = "",        // NEW: reference to quizzes/{id}
        dueDate: Long = 0L,         // NEW: optional due date (epoch ms, 0 = no deadline)
        section: String = "",       // NEW: class section/division e.g. "A", "B" (empty = all sections)
        onSuccess: (String) -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        val docId = taskId.ifBlank { db.collection("school_tasks").document().id }
        val data = mutableMapOf<String, Any>(
            "task_id"     to docId,
            "teacher_id"  to teacherId,
            "school_id"   to schoolId,
            "grade"       to grade,
            "title"       to title,
            "description" to description,
            "task_type"   to taskType,
            "subject"     to subject,
            "chapter"     to chapter,
            "bb_topic"    to bbTopic,
            "created_at"  to System.currentTimeMillis(),
            "is_active"   to true
        )
        // Only write the new reference fields when provided; backwards compat for legacy tasks
        if (bbCacheId.isNotBlank()) data["bb_cache_id"] = bbCacheId
        if (quizId.isNotBlank())    data["quiz_id"]     = quizId
        if (dueDate > 0)            data["due_date"]    = dueDate
        if (section.isNotBlank())   data["section"]     = section
        // Legacy fallback: if no quizId but quizJson provided (old flow), keep it
        if (quizJson.isNotBlank() && quizId.isBlank()) data["quiz_json"] = quizJson
        db.collection("school_tasks").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess(docId) }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load active tasks for a school + grade (student view).
     * If [section] is provided, only tasks for that section (or tasks with no section set) are returned.
     */
    fun loadTasksForSchool(
        schoolId: String,
        grade: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {},
        section: String = ""
    ) {
        if (schoolId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("school_tasks")
            .whereEqualTo("school_id", schoolId)
            .whereEqualTo("is_active", true)
            .orderBy("created_at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val all = snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                }
                // Filter by grade (empty grade = all grades)
                val gradeFiltered = if (grade.isBlank()) all else all.filter { task ->
                    val tGrade = task["grade"] as? String ?: ""
                    tGrade.isBlank() || tGrade == grade
                }
                // Filter by section: tasks with no section set are visible to everyone
                val filtered = if (section.isBlank()) gradeFiltered else gradeFiltered.filter { task ->
                    val tSection = task["section"] as? String ?: ""
                    tSection.isBlank() || tSection.equals(section, ignoreCase = true)
                }
                onSuccess(filtered)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load tasks created by a specific teacher.
     */
    fun loadTasksByTeacher(
        teacherId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (teacherId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("school_tasks")
            .whereEqualTo("teacher_id", teacherId)
            .orderBy("created_at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val tasks = snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                }
                onSuccess(tasks)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Deactivate (soft-delete) a task.
     */
    fun deactivateTask(
        taskId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("school_tasks").document(taskId)
            .update("is_active", false)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Mark a task as completed by a student (legacy — marks entire task done).
     */
    fun markTaskComplete(
        userId: String,
        taskId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        usersRef(userId)
            .collection("task_completions").document(taskId)
            .set(mapOf("task_id" to taskId, "completed_at" to System.currentTimeMillis()))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Mark the BB-lesson part of a task as completed by a student.
     * Writes to both the student record and the task's completions subcollection
     * so the teacher can query completions from a single path.
     */
    fun markTaskBbComplete(
        userId: String,
        taskId: String,
        schoolId: String = "",
        studentName: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        val now = System.currentTimeMillis()
        val studentData = mapOf(
            "task_id"       to taskId,
            "bb_completed"  to true,
            "bb_completed_at" to now,
            "last_updated_at" to now
        )
        val completionData = mapOf(
            "user_id"         to userId,
            "student_name"    to studentName,
            "bb_completed"    to true,
            "bb_completed_at" to now,
            "last_updated_at" to now
        )
        // Write to student's own record
        usersRef(userId).collection("task_completions").document(taskId)
            .set(studentData, SetOptions.merge())
            .addOnSuccessListener {
                // Also write to school_tasks/{taskId}/completions/{userId} for teacher report
                if (taskId.isNotBlank()) {
                    db.collection("school_tasks").document(taskId)
                        .collection("completions").document(userId)
                        .set(completionData, SetOptions.merge())
                }
                onSuccess()
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Mark the quiz part of a task as completed by a student.
     */
    fun markTaskQuizComplete(
        userId: String,
        taskId: String,
        score: Int,
        total: Int,
        studentName: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        val now = System.currentTimeMillis()
        val studentData = mapOf(
            "task_id"          to taskId,
            "quiz_completed"   to true,
            "quiz_completed_at" to now,
            "quiz_score"       to score,
            "quiz_total"       to total,
            "last_updated_at"  to now
        )
        val completionData = mapOf(
            "user_id"           to userId,
            "student_name"      to studentName,
            "quiz_completed"    to true,
            "quiz_completed_at" to now,
            "quiz_score"        to score,
            "quiz_total"        to total,
            "last_updated_at"   to now
        )
        usersRef(userId).collection("task_completions").document(taskId)
            .set(studentData, SetOptions.merge())
            .addOnSuccessListener {
                if (taskId.isNotBlank()) {
                    db.collection("school_tasks").document(taskId)
                        .collection("completions").document(userId)
                        .set(completionData, SetOptions.merge())
                }
                onSuccess()
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load per-student completions for a task (teacher report view).
     * Reads from school_tasks/{taskId}/completions/
     */
    fun loadTaskCompletions(
        taskId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (taskId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("school_tasks").document(taskId)
            .collection("completions")
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["doc_id"] = doc.id }
                }
                onSuccess(list)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load the set of task IDs completed by a student.
     */
    fun loadCompletedTaskIds(
        userId: String,
        onSuccess: (Set<String>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onSuccess(emptySet()); return }
        usersRef(userId)
            .collection("task_completions")
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.map { it.id }.toSet())
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Save granular BB lesson read progress for a task.
     * Called on each forward step-navigation so the teacher can see exactly how far
     * each student has progressed (e.g. "3/5 steps").
     * Automatically sets bb_completed = true when stepsViewed >= totalSteps.
     * Safe to call repeatedly — uses merge so partial writes accumulate.
     */
    fun saveTaskBbProgress(
        userId: String,
        taskId: String,
        stepsViewed: Int,
        totalSteps: Int,
        durationMs: Long,
        isCompleted: Boolean,
        studentName: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onFailure(null); return }
        val now = System.currentTimeMillis()
        val base = mutableMapOf<String, Any>(
            "task_id"         to taskId,
            "bb_steps_viewed" to stepsViewed,
            "bb_total_steps"  to totalSteps,
            "bb_duration_ms"  to durationMs,
            "last_updated_at" to now
        )
        if (isCompleted) {
            base["bb_completed"]    = true
            base["bb_completed_at"] = now
        }
        val completionData = base.toMutableMap().also {
            it["user_id"]      = userId
            it["student_name"] = studentName
        }
        usersRef(userId).collection("task_completions").document(taskId)
            .set(base, SetOptions.merge())
            .addOnSuccessListener {
                if (taskId.isNotBlank()) {
                    db.collection("school_tasks").document(taskId)
                        .collection("completions").document(userId)
                        .set(completionData, SetOptions.merge())
                }
                onSuccess()
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load full task-progress map for all tasks a student has interacted with.
     * Returns Map<taskId, progressData> enabling detailed progress UIs
     * (steps read, quiz score, completed flags, etc.)
     */
    fun loadAllTaskProgress(
        userId: String,
        onSuccess: (Map<String, Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onSuccess(emptyMap()); return }
        usersRef(userId).collection("task_completions").get()
            .addOnSuccessListener { snap ->
                val result = snap.documents.associate { doc ->
                    doc.id to (doc.data?.toMutableMap() ?: mutableMapOf<String, Any>())
                }
                onSuccess(result)
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Teacher Chat Defaults ─────────────────────────────────────────────────
    // Path: users/{teacherId}/teacher_settings/chat_defaults
    // Fields: subject, chapter, grade, school_id

    fun getTeacherChatDefaults(
        userId: String,
        onResult: (subject: String, chapter: String, grade: String, schoolId: String) -> Unit
    ) {
        if (userId.isBlank() || userId == "guest_user") {
            onResult("", "", "", ""); return
        }
        usersRef(userId)
            .collection("teacher_settings").document("chat_defaults")
            .get()
            .addOnSuccessListener { doc ->
                onResult(
                    doc.getString("subject")   ?: "",
                    doc.getString("chapter")   ?: "",
                    doc.getString("grade")     ?: "",
                    doc.getString("school_id") ?: ""
                )
            }
            .addOnFailureListener { onResult("", "", "", "") }
    }

    fun setTeacherChatDefaults(
        userId: String,
        subject: String,
        chapter: String,
        grade: String,
        schoolId: String,
        onDone: () -> Unit = {}
    ) {
        if (userId.isBlank() || userId == "guest_user") { onDone(); return }
        val data = mapOf(
            "subject"   to subject,
            "chapter"   to chapter,
            "grade"     to grade,
            "school_id" to schoolId,
            "updatedAt" to System.currentTimeMillis()
        )
        usersRef(userId)
            .collection("teacher_settings").document("chat_defaults")
            .set(data, SetOptions.merge())
            .addOnCompleteListener { onDone() }
    }

    /**
     * Validate teacher credentials against schools/{schoolId}/teachers/{username}.
     * Calls onSuccess(name, teacherId) on match, onFailure(message) otherwise.
     */
    fun validateTeacher(
        schoolId: String,
        username: String,
        password: String,
        onSuccess: (name: String, teacherId: String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        db.collection("schools")
            .document(schoolId)
            .collection("teachers")
            .document(username.lowercase())
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val stored = doc.getString("password") ?: ""
                    if (stored == password) {
                        val name      = doc.getString("name") ?: username
                        val teacherId = doc.getString("teacher_id") ?: username
                        onSuccess(name, teacherId)
                    } else {
                        onFailure("Incorrect password. Check with your school admin.")
                    }
                } else {
                    onFailure("Username not found in teacher roster.")
                }
            }
            .addOnFailureListener { onFailure(it.message ?: "Verification failed") }
    }

    /**
     * Fetch students for a given school + grade from schools/{schoolId}/students.
     * If grade is blank or "All", returns all students.
     */
    fun getStudentsByGrade(
        schoolId: String,
        grade: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit = {}
    ) {
        var query: com.google.firebase.firestore.Query =
            db.collection("schools").document(schoolId).collection("students")
        if (grade.isNotBlank() && grade.lowercase() != "all") {
            query = query.whereEqualTo("grade", grade)
        }
        query.get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { it.data })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Shared BB Lesson Library ──────────────────────────────────────────────
    // Path: bb_cache/{bbCacheId}
    // Teacher publishes a generated lesson once; all students load from this cache.
    // This eliminates per-student LLM calls for teacher-assigned BB lessons.

    /**
     * Publish a BB lesson to the global lesson library.
     * Returns the generated doc ID via [onSuccess].
     *
     * @param stepsJson  Full BlackboardStep[] serialized as JSON string.
     * @param preview    First 120 chars of the lesson (for list UI).
     */
    fun publishBbLesson(
        teacherId: String,
        schoolId: String,
        subject: String,
        chapter: String,
        topic: String,
        preview: String,
        stepsJson: String,
        languageTag: String = "en-US",
        stepCount: Int = 0,
        existingId: String = "",
        onSuccess: (bbCacheId: String) -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (teacherId.isBlank()) { onFailure(null); return }
        val docId = existingId.ifBlank { db.collection("bb_cache").document().id }
        val data = mapOf(
            "bb_cache_id"  to docId,
            "teacher_id"   to teacherId,
            "school_id"    to schoolId,
            "subject"      to subject,
            "chapter"      to chapter,
            "topic"        to topic,
            "preview"      to preview.take(150),
            "steps_json"   to stepsJson,
            "language_tag" to languageTag,
            "step_count"   to stepCount,
            "created_at"   to System.currentTimeMillis()
        )
        db.collection("bb_cache").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess(docId) }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load a single BB lesson from the global library.
     * Returns the raw Firestore data map (contains steps_json, topic, etc.)
     */
    fun loadBbLesson(
        bbCacheId: String,
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (bbCacheId.isBlank()) { onFailure(null); return }
        db.collection("bb_cache").document(bbCacheId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) onSuccess(doc.data ?: emptyMap())
                else onFailure(null)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * List all BB lessons published by a teacher, newest first.
     */
    fun loadTeacherBbLessons(
        teacherId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (teacherId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("bb_cache")
            .whereEqualTo("teacher_id", teacherId)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Quiz Library ──────────────────────────────────────────────────────────
    // Path: quizzes/{quizId}
    // Teacher validates a quiz once; task references quiz_id, students load it.

    /**
     * Publish a validated quiz to the global quiz library.
     * Returns the generated quiz ID via [onSuccess].
     *
     * @param questionsJson  Quiz.toTransferJson() output.
     * @param bbCacheId      Optional ID linking this quiz to a BB lesson.
     */
    fun publishQuizToLibrary(
        teacherId: String,
        schoolId: String,
        subject: String,
        chapter: String,
        title: String,
        difficulty: String = "medium",
        questionsJson: String,
        bbCacheId: String = "",
        questionCount: Int = 0,
        existingId: String = "",
        onSuccess: (quizId: String) -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (teacherId.isBlank()) { onFailure(null); return }
        val docId = existingId.ifBlank { db.collection("quizzes").document().id }
        val data = mutableMapOf<String, Any>(
            "quiz_id"         to docId,
            "teacher_id"      to teacherId,
            "school_id"       to schoolId,
            "subject"         to subject,
            "chapter"         to chapter,
            "title"           to title,
            "difficulty"      to difficulty,
            "questions_json"  to questionsJson,
            "question_count"  to questionCount,
            "created_at"      to System.currentTimeMillis()
        )
        if (bbCacheId.isNotBlank()) data["bb_cache_id"] = bbCacheId
        db.collection("quizzes").document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess(docId) }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load a single quiz from the global library.
     */
    fun loadQuizFromLibrary(
        quizId: String,
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (quizId.isBlank()) { onFailure(null); return }
        db.collection("quizzes").document(quizId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) onSuccess(doc.data ?: emptyMap())
                else onFailure(null)
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * List all quizzes published by a teacher, newest first.
     */
    fun loadTeacherQuizzes(
        teacherId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (teacherId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("quizzes")
            .whereEqualTo("teacher_id", teacherId)
            .orderBy("created_at", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id }
                })
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── School Admin & Engagement ─────────────────────────────────────────────

    /**
     * Returns engagement summary for a school today.
     * Queries users_table for students in this school whose questions_updated_at
     * is from today's UTC date, giving a live "active today" count.
     *
     * @param onSuccess Map with keys:
     *   "totalStudents" (Int), "activeToday" (Int), "activeTodayIds" (List<String>)
     */
    fun getSchoolEngagementToday(
        schoolId: String,
        grade: String = "",
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (schoolId.isBlank()) { onSuccess(mapOf("totalStudents" to 0, "activeToday" to 0)); return }
        var query = db.collection("users_table")
            .whereEqualTo("schoolId", schoolId)
        if (grade.isNotBlank()) query = query.whereEqualTo("grade", grade)
        query.get()
            .addOnSuccessListener { snap ->
                val nowMs = System.currentTimeMillis()
                val todayStart = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val activeTodayIds = mutableListOf<String>()
                snap.documents.forEach { doc ->
                    val updatedAt = doc.getLong("questions_updated_at") ?: 0L
                    if (updatedAt >= todayStart) {
                        activeTodayIds.add(doc.getString("userId") ?: doc.id)
                    }
                }
                onSuccess(mapOf(
                    "totalStudents" to snap.size(),
                    "activeToday"   to activeTodayIds.size,
                    "activeTodayIds" to activeTodayIds
                ))
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Load all teachers for a school from schools/{schoolId}/teachers subcollection.
     */
    fun loadSchoolTeachers(
        schoolId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (schoolId.isBlank()) { onSuccess(emptyList()); return }
        db.collection("schools").document(schoolId)
            .collection("teachers")
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.also { it["username"] = doc.id }
                })
            }
            .addOnFailureListener { onFailure(it) }
    }

    /**
     * Aggregates per-teacher metrics for school admin view.
     * Returns one map per teacher containing:
     * - username, teacher_id, name
     * - studentCount (students in grades where this teacher assigned tasks)
     * - taskCount (active tasks assigned by teacher)
     * - engagedStudents (students who submitted any completion)
     * - completionRate (0..100, based on completion submissions)
     */
    fun loadSchoolTeacherMetrics(
        schoolId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        if (schoolId.isBlank()) { onSuccess(emptyList()); return }

        loadSchoolTeachers(
            schoolId = schoolId,
            onSuccess = { teachers ->
                db.collection("schools").document(schoolId)
                    .collection("students")
                    .get()
                    .addOnSuccessListener { studentsSnap ->
                        db.collection("school_tasks")
                            .whereEqualTo("school_id", schoolId)
                            .whereEqualTo("is_active", true)
                            .get()
                            .addOnSuccessListener { tasksSnap ->
                                val taskDocs = tasksSnap.documents

                                val teacherRows = teachers.map { t -> t.toMutableMap() }.toMutableList()
                                val teacherIndexByKey = mutableMapOf<String, Int>()
                                teacherRows.forEachIndexed { idx, row ->
                                    val username = (row["username"] as? String ?: "").trim()
                                    val teacherId = (row["teacher_id"] as? String ?: "").trim()
                                    if (username.isNotBlank()) teacherIndexByKey[username] = idx
                                    if (teacherId.isNotBlank()) teacherIndexByKey[teacherId] = idx
                                }

                                val taskCount = IntArray(teacherRows.size)
                                val completionTotal = IntArray(teacherRows.size)
                                val completionDone = IntArray(teacherRows.size)
                                val gradesByTeacher = Array(teacherRows.size) { mutableSetOf<String>() }
                                val engagedUsersByTeacher = Array(teacherRows.size) { mutableSetOf<String>() }
                                val tasksByTeacher = Array(teacherRows.size) { mutableListOf<com.google.firebase.firestore.DocumentSnapshot>() }

                                taskDocs.forEach { taskDoc ->
                                    val taskTeacher = (taskDoc.getString("teacher_id") ?: "").trim()
                                    val idx = teacherIndexByKey[taskTeacher] ?: return@forEach
                                    tasksByTeacher[idx].add(taskDoc)
                                    taskCount[idx] += 1
                                    val grade = (taskDoc.getString("grade") ?: "").trim()
                                    if (grade.isNotBlank()) gradesByTeacher[idx].add(grade)
                                }

                                val totalTaskDocs = tasksByTeacher.sumOf { it.size }
                                if (totalTaskDocs == 0) {
                                    teacherRows.forEachIndexed { idx, row ->
                                        val grades = gradesByTeacher[idx]
                                        val rosterCount = if (grades.isEmpty()) {
                                            studentsSnap.size()
                                        } else {
                                            studentsSnap.documents.count { (it.getString("grade") ?: "").trim() in grades }
                                        }
                                        row["taskCount"] = 0
                                        row["studentCount"] = rosterCount
                                        row["engagedStudents"] = 0
                                        row["completionRate"] = 0
                                    }
                                    onSuccess(teacherRows)
                                    return@addOnSuccessListener
                                }

                                var pending = totalTaskDocs
                                fun maybeFinish() {
                                    if (pending > 0) return
                                    teacherRows.forEachIndexed { idx, row ->
                                        val grades = gradesByTeacher[idx]
                                        val rosterCount = if (grades.isEmpty()) {
                                            studentsSnap.size()
                                        } else {
                                            studentsSnap.documents.count { (it.getString("grade") ?: "").trim() in grades }
                                        }
                                        val total = completionTotal[idx]
                                        val done = completionDone[idx]
                                        val rate = if (total <= 0) 0 else ((done * 100.0) / total).toInt()

                                        row["taskCount"] = taskCount[idx]
                                        row["studentCount"] = rosterCount
                                        row["engagedStudents"] = engagedUsersByTeacher[idx].size
                                        row["completionRate"] = rate
                                    }
                                    onSuccess(teacherRows)
                                }

                                tasksByTeacher.forEachIndexed { idx, docs ->
                                    docs.forEach { taskDoc ->
                                        val taskType = (taskDoc.getString("task_type") ?: "quiz").trim()
                                        db.collection("school_tasks").document(taskDoc.id)
                                            .collection("completions")
                                            .get()
                                            .addOnSuccessListener { compSnap ->
                                                compSnap.documents.forEach { comp ->
                                                    completionTotal[idx] += 1
                                                    val uid = (comp.getString("user_id") ?: comp.id).trim()
                                                    if (uid.isNotBlank()) engagedUsersByTeacher[idx].add(uid)

                                                    val bbDone = comp.getBoolean("bb_completed") == true
                                                    val quizDone = comp.getBoolean("quiz_completed") == true
                                                    val isDone = when (taskType) {
                                                        "bb_lesson" -> bbDone
                                                        "quiz" -> quizDone
                                                        "both" -> bbDone && quizDone
                                                        else -> bbDone || quizDone
                                                    }
                                                    if (isDone) completionDone[idx] += 1
                                                }
                                                pending -= 1
                                                maybeFinish()
                                            }
                                            .addOnFailureListener {
                                                pending -= 1
                                                maybeFinish()
                                            }
                                    }
                                }
                            }
                            .addOnFailureListener { onFailure(it) }
                    }
                    .addOnFailureListener { onFailure(it) }
            },
            onFailure = { onFailure(it) }
        )
    }

}


