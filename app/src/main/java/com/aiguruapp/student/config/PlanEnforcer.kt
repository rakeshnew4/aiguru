package com.aiguruapp.student.config

import android.util.Log
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.models.UserMetadata
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Calendar
import java.util.TimeZone

/**
 * Checks plan limits before allowing an AI request.
 *
 * Usage in ChatActivity:
 *   val check = PlanEnforcer.check(userId, limits, tokensToUse = estimatedTokens)
 *   if (!check.allowed) { showUpgradePrompt(check.reason); return }
 *
 * Token counters live in users/{uid} as tokensToday / tokensThisMonth and are
 * incremented after each successful response via [recordTokensUsed].
 *
 * Day/month rollover is detected by comparing the stored tokensUpdatedAt
 * against the current UTC day/month — counters are reset when they diverge.
 */
object PlanEnforcer {

    private const val TAG = "PlanEnforcer"
    private val db = FirebaseFirestore.getInstance()

    // ── In-memory session counters ─────────────────────────────────────────────
    // These mirror what recordQuestionAsked() writes to Firestore.
    // Because Firestore writes are async (and Source.SERVER reads may return stale
    // data before the write is server-acknowledged), getQuestionsLeft() uses
    // max(Firestore value, in-memory value) so HomeActivity always shows the
    // correct remaining count immediately after the user sends a message.
    @Volatile private var inMemUserId:    String = ""
    @Volatile private var inMemDayKey:    Int    = -1
    @Volatile private var inMemChatUsed:  Int    = 0
    @Volatile private var inMemBbUsed:    Int    = 0

    private fun inMemEffectiveChat(userId: String): Int {
        val today = utcDayOf(System.currentTimeMillis())
        return if (inMemUserId == userId && inMemDayKey == today) inMemChatUsed else 0
    }

    private fun inMemEffectiveBb(userId: String): Int {
        val today = utcDayOf(System.currentTimeMillis())
        return if (inMemUserId == userId && inMemDayKey == today) inMemBbUsed else 0
    }

    data class CheckResult(
        val allowed: Boolean,
        val reason: String = "",
        /** Human-readable upgrade message for the UI. */
        val upgradeMessage: String = "",
        /** Which limit enum triggered the block. */
        val limitType: LimitType = LimitType.NONE
    )

    enum class LimitType {
        NONE,
        MAINTENANCE,
        PLAN_EXPIRED,
        DAILY_TOKENS,
        MONTHLY_TOKENS,
        MESSAGES_PER_HOUR,
        FEATURE_IMAGE,
        FEATURE_VOICE,
        FEATURE_PDF,
        BLACKBOARD,
        FEATURE_BLACKBOARD,
        CHAT_QUESTIONS,
        BB_SESSIONS,
        FEATURE_TTS,
        FEATURE_AI_TTS
    }

    // In-memory hourly rate-limit counter (resets each clock-hour, per process)
    private val hourlyCounters = mutableMapOf<String, Pair<Int, Int>>() // uid → (count, hour)

    // ── Main check (synchronous against cached data) ──────────────────────────

    /**
     * Synchronous check against cached UserMetadata + AdminConfig.
     * Call before dispatching a request to the AI server.
     *
     * @param metadata      The user's current UserMetadata (loaded at login, kept fresh)
     * @param limits        Effective PlanLimits resolved via AdminConfigRepository
     * @param featureType   Which feature is being used (null = plain text chat)
     */
    fun check(
        metadata: UserMetadata,
        limits: PlanLimits,
        featureType: FeatureType = FeatureType.TEXT_CHAT
    ): CheckResult {
        val adminCfg = AdminConfigRepository.config

        // Global maintenance mode
        if (adminCfg.maintenanceMode) {
            return CheckResult(
                allowed        = false,
                reason         = adminCfg.maintenanceMessage,
                upgradeMessage = adminCfg.maintenanceMessage,
                limitType      = LimitType.MAINTENANCE
            )
        }

        // ── Plan expiry check ───────────────────────────────────────────────
        // If the user is on a paid plan but its expiry has passed, block access.
        // planExpiryDate == 0L means no expiry stored (free / lifetime plan).
        if (metadata.planId.isNotBlank() && metadata.planId != "free") {
            val expiry = metadata.planExpiryDate
            if (expiry > 0L && System.currentTimeMillis() > expiry) {
                return CheckResult(
                    allowed        = false,
                    reason         = "${metadata.planName} plan has expired",
                    upgradeMessage = "Your ${metadata.planName} plan has expired. 📄 Renew to keep learning — tap here to upgrade!",
                    limitType      = LimitType.PLAN_EXPIRED
                )
            }
        }


        // ── Daily token budget ────────────────────────────────────────────────
        if (limits.dailyTokenLimit > 0) {
            val todayTokens = getTodayTokens(metadata)
            if (todayTokens >= limits.dailyTokenLimit) {
                return CheckResult(
                    allowed        = false,
                    reason         = "Daily token limit reached ($todayTokens / ${limits.dailyTokenLimit})",
                    upgradeMessage = "You've used all your daily AI credits. Upgrade your plan or come back tomorrow! 🚀",
                    limitType      = LimitType.DAILY_TOKENS
                )
            }
        }

        // ── Monthly token budget ──────────────────────────────────────────────
        if (limits.monthlyTokenLimit > 0) {
            val monthTokens = getMonthTokens(metadata)
            if (monthTokens >= limits.monthlyTokenLimit) {
                return CheckResult(
                    allowed        = false,
                    reason         = "Monthly token limit reached ($monthTokens / ${limits.monthlyTokenLimit})",
                    upgradeMessage = "You've used all your monthly AI credits. Upgrade to continue learning! 📚",
                    limitType      = LimitType.MONTHLY_TOKENS
                )
            }
        }

        // ── Per-hour rate limit ───────────────────────────────────────────────
        if (limits.messagesPerHour > 0) {
            val currentHour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
            val (count, hour) = hourlyCounters[metadata.userId] ?: Pair(0, currentHour)
            val effectiveCount = if (hour == currentHour) count else 0
            if (effectiveCount >= limits.messagesPerHour) {
                return CheckResult(
                    allowed        = false,
                    reason         = "Hourly message limit reached ($effectiveCount / ${limits.messagesPerHour})",
                    upgradeMessage = "You've sent too many messages this hour. Take a short break or upgrade your plan! ⏱",
                    limitType      = LimitType.MESSAGES_PER_HOUR
                )
            }
        }

        // ── Feature access ────────────────────────────────────────────────────
        when (featureType) {
            FeatureType.IMAGE_UPLOAD -> if (!limits.imageUploadEnabled) return CheckResult(
                allowed        = false,
                reason         = "Image upload not available on ${metadata.planName} plan",
                upgradeMessage = "Image upload is a premium feature. Upgrade your plan to analyse images! 🖼️",
                limitType      = LimitType.FEATURE_IMAGE
            )
            FeatureType.VOICE_MODE -> if (!limits.voiceModeEnabled) return CheckResult(
                allowed        = false,
                reason         = "Voice mode not available on ${metadata.planName} plan",
                upgradeMessage = "Voice mode is a premium feature. Upgrade to talk to your AI tutor! 🎙️",
                limitType      = LimitType.FEATURE_VOICE
            )
            FeatureType.PDF_UPLOAD -> if (!limits.pdfEnabled) return CheckResult(
                allowed        = false,
                reason         = "PDF upload not available on ${metadata.planName} plan",
                upgradeMessage = "PDF analysis is a premium feature. Upgrade to share your textbooks! 📄",
                limitType      = LimitType.FEATURE_PDF
            )
            FeatureType.BLACKBOARD -> if (!limits.blackboardEnabled && !metadata.planBlackboardEnabled) return CheckResult(
                allowed        = false,
                reason         = "Blackboard explanation not available on ${metadata.planName} plan",
                upgradeMessage = "Visual blackboard lessons are a premium feature. Upgrade to unlock step-by-step visual explanations! 🎓",
                limitType      = LimitType.BLACKBOARD
            )
            FeatureType.NORMAL_TTS -> if (!limits.ttsEnabled && !metadata.planTtsEnabled) return CheckResult(
                allowed        = false,
                reason         = "Read-aloud TTS not available on ${metadata.planName} plan",
                upgradeMessage = "Read-aloud is not available on your current plan. Upgrade to listen to answers! 🔊",
                limitType      = LimitType.FEATURE_TTS
            )
            FeatureType.AI_TTS -> if (!limits.aiTtsEnabled && !metadata.planAiTtsEnabled) return CheckResult(
                allowed        = false,
                reason         = "AI voice synthesis not available on ${metadata.planName} plan",
                upgradeMessage = "AI voice is a premium feature. Upgrade to experience natural AI voice! 🎙️",
                limitType      = LimitType.FEATURE_AI_TTS
            )
            else -> { /* TEXT_CHAT — always allowed if above checks pass */ }
        }

        return CheckResult(allowed = true)
    }

    /**
     * Trim the conversation history to respect context window limits.
     * Returns a sublist of messages safe to send to the LLM.
     * Always keeps the most recent messages (oldest are dropped first).
     */
    fun <T> trimContextWindow(
        messages: List<T>,
        limits: PlanLimits,
        getContent: (T) -> String
    ): List<T> {
        if (limits.contextWindowMessages > 0 && messages.size > limits.contextWindowMessages) {
            val trimmed = messages.takeLast(limits.contextWindowMessages)
            Log.d(TAG, "Context window trimmed: ${messages.size} → ${trimmed.size} messages")
            return trimmed
        }
        if (limits.contextWindowChars > 0) {
            var totalChars = 0
            val result = mutableListOf<T>()
            for (msg in messages.reversed()) {
                totalChars += getContent(msg).length
                if (totalChars > limits.contextWindowChars) break
                result.add(0, msg)
            }
            if (result.size < messages.size) {
                Log.d(TAG, "Context window trimmed by chars: ${messages.size} → ${result.size} messages")
            }
            return result
        }
        return messages
    }

    /**
     * Record tokens used after a successful AI response.
     * Increments both daily and monthly counters in the user's Firestore doc.
     * Also increments the in-memory hourly rate counter.
     */
    fun recordTokensUsed(userId: String, tokens: Int, inputTokens: Int = 0, outputTokens: Int = 0) {
        if (tokens <= 0 || userId.isBlank() || userId == "guest_user") return

        // Increment hourly counter
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        val (count, prevHour) = hourlyCounters[userId] ?: Pair(0, hour)
        hourlyCounters[userId] = Pair((if (hour == prevHour) count else 0) + 1, hour)

        val now = System.currentTimeMillis()
        val updates = mutableMapOf<String, Any>(
            "tokens_today"      to FieldValue.increment(tokens.toLong()),
            "tokens_this_month" to FieldValue.increment(tokens.toLong()),
            "tokens_updated_at" to now
        )
        if (inputTokens > 0) {
            updates["input_tokens_today"]        = FieldValue.increment(inputTokens.toLong())
            updates["input_tokens_this_month"]   = FieldValue.increment(inputTokens.toLong())
        }
        if (outputTokens > 0) {
            updates["output_tokens_today"]       = FieldValue.increment(outputTokens.toLong())
            updates["output_tokens_this_month"]  = FieldValue.increment(outputTokens.toLong())
        }
        db.collection("users_table").document(userId)
            .set(updates, SetOptions.merge())
            .addOnFailureListener { Log.e(TAG, "recordTokensUsed failed uid=$userId: ${it.message}") }
    }

    /**
     * Reset daily counter if last update was on a different UTC day.
     * Called automatically by [getTodayTokens] but can be called explicitly.
     */
    fun resetDailyIfNeeded(userId: String, metadata: UserMetadata) {
        val lastDay = utcDayOf(metadata.tokensUpdatedAt)
        val today   = utcDayOf(System.currentTimeMillis())
        if (lastDay != today && metadata.tokensToday > 0) {
            db.collection("users_table").document(userId)
                .set(mapOf("tokens_today" to 0, "tokens_updated_at" to System.currentTimeMillis()), SetOptions.merge())
                .addOnFailureListener { Log.e(TAG, "resetDaily failed: ${it.message}") }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getTodayTokens(metadata: UserMetadata): Int {
        // If the stored counter is from a previous day, treat it as 0
        val lastDay = utcDayOf(metadata.tokensUpdatedAt)
        val today   = utcDayOf(System.currentTimeMillis())
        return if (lastDay == today) metadata.tokensToday else 0
    }

    private fun getMonthTokens(metadata: UserMetadata): Int {
        val lastMonth = utcMonthOf(metadata.tokensUpdatedAt)
        val thisMonth = utcMonthOf(System.currentTimeMillis())
        return if (lastMonth == thisMonth) metadata.tokensThisMonth else 0
    }

    /** Returns true if [lastUpdatedAt] is from a previous UTC calendar day (or never set). */
    fun isNewQuotaDay(lastUpdatedAt: Long): Boolean =
        utcDayOf(System.currentTimeMillis()) != utcDayOf(lastUpdatedAt)

    private fun utcDayOf(epochMs: Long): Int {
        if (epochMs == 0L) return -1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun utcMonthOf(epochMs: Long): Int {
        if (epochMs == 0L) return -1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    enum class FeatureType { TEXT_CHAT, IMAGE_UPLOAD, VOICE_MODE, PDF_UPLOAD, BLACKBOARD, NORMAL_TTS, AI_TTS }

    // ── Question-quota API ────────────────────────────────────────────────────

    /**
     * Check whether the user has question quota remaining for today.
     *
     * @param isBlackboard  true = check BB sessions limit, false = check chat questions limit
     */
    fun checkQuestionsQuota(
        metadata: UserMetadata,
        limits: PlanLimits,
        isBlackboard: Boolean
    ): CheckResult {
        val isSameDay = metadata.questionsUpdatedAt > 0L &&
            utcDayOf(metadata.questionsUpdatedAt) == utcDayOf(System.currentTimeMillis())

        if (isBlackboard) {
            val limit = limits.dailyBlackboardSessions
            if (limit <= 0) return CheckResult(allowed = true)
            val fsUsed = if (isSameDay) metadata.bbSessionsToday else 0
            val used = maxOf(fsUsed, inMemEffectiveBb(metadata.userId))
            if (used >= limit) {
                return CheckResult(
                    allowed        = false,
                    reason         = "Daily Blackboard session limit reached ($used / $limit)",
                    upgradeMessage = "You've used all $limit Blackboard sessions for today 🎓\nUpgrade your plan for more visual lessons!",
                    limitType      = LimitType.BB_SESSIONS
                )
            }
        } else {
            val baseLimit = limits.dailyChatQuestions
            if (baseLimit <= 0) return CheckResult(allowed = true)
            val effectiveLimit = baseLimit + metadata.bonusQuestionsToday.coerceAtLeast(0)
            val fsUsed = if (isSameDay) metadata.chatQuestionsToday else 0
            val used = maxOf(fsUsed, inMemEffectiveChat(metadata.userId))
            if (used >= effectiveLimit) {
                return CheckResult(
                    allowed        = false,
                    reason         = "Daily chat question limit reached ($used / $effectiveLimit)",
                    upgradeMessage = "You've asked all $effectiveLimit questions for today 💬\nUpgrade your plan for unlimited questions!",
                    limitType      = LimitType.CHAT_QUESTIONS
                )
            }
        }
        return CheckResult(allowed = true)
    }

    /**
     * How many questions / sessions the user has left today.
     * Returns -1 if the limit is 0 (= unlimited).
     */
    fun getQuestionsLeft(
        metadata: UserMetadata,
        limits: PlanLimits,
        isBlackboard: Boolean
    ): Int {
        val isSameDay = metadata.questionsUpdatedAt > 0L &&
            utcDayOf(metadata.questionsUpdatedAt) == utcDayOf(System.currentTimeMillis())
        return if (isBlackboard) {
            val limit = limits.dailyBlackboardSessions
            if (limit <= 0) -1
            else {
                val fsUsed = if (isSameDay) metadata.bbSessionsToday else 0
                maxOf(0, limit - maxOf(fsUsed, inMemEffectiveBb(metadata.userId)))
            }
        } else {
            val baseLimit = limits.dailyChatQuestions
            if (baseLimit <= 0) -1
            else {
                val effectiveLimit = baseLimit + metadata.bonusQuestionsToday.coerceAtLeast(0)
                val fsUsed = if (isSameDay) metadata.chatQuestionsToday else 0
                maxOf(0, effectiveLimit - maxOf(fsUsed, inMemEffectiveChat(metadata.userId)))
            }
        }
    }

    /**
     * Increment the question counter in Firestore after a successful AI response.
     * Also resets the counter if the stored date is a previous day.
     */
    fun recordQuestionAsked(userId: String, isBlackboard: Boolean, previousUpdatedAt: Long = 0L) {
        if (userId.isBlank() || userId == "guest_user") return

        // Update in-memory counter immediately so getQuestionsLeft() reflects
        // the increment before the Firestore server acknowledges the write.
        val today = utcDayOf(System.currentTimeMillis())
        synchronized(this) {
            if (inMemUserId != userId || inMemDayKey != today) {
                inMemUserId   = userId
                inMemDayKey   = today
                inMemChatUsed = 0
                inMemBbUsed   = 0
            }
            if (isBlackboard) inMemBbUsed++ else inMemChatUsed++
        }

        val now = System.currentTimeMillis()
        // On day rollover: reset counter to 1 instead of incrementing yesterday's stale value.
        // Guard against unset (0L) timestamp — 0L must NOT be treated as "previous day"
        // because it simply means the doc was just created or the caller passed no value.
        val isNewDay = previousUpdatedAt > 0L && isNewQuotaDay(previousUpdatedAt)
        val updates = mutableMapOf<String, Any>(
            "questions_updated_at" to now
        )
        if (isBlackboard) {
            updates["bb_sessions_today"] = if (isNewDay) 1L else FieldValue.increment(1L)
        } else {
            updates["chat_questions_today"] = if (isNewDay) 1L else FieldValue.increment(1L)
        }
        db.collection("users_table").document(userId)
            .update(updates)
            .addOnFailureListener { Log.e(TAG, "recordQuestionAsked failed uid=$userId: ${it.message}") }
    }

    // ── Guest Quota Checking ───────────────────────────────────────────────────

    /**
     * Check whether a guest device has quota remaining.
     * Guests get 10 chat questions + 3 blackboard sessions per device.
     */
    fun checkGuestQuota(
        deviceId: String,
        isBlackboard: Boolean,
        callback: (CheckResult) -> Unit
    ) {
        db.collection("devices").document(deviceId).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    // Device doesn't exist yet — allow
                    callback(CheckResult(allowed = true))
                    return@addOnSuccessListener
                }
                val chatUsed = snap.getLong("guest_chat_used")?.toInt() ?: 0
                val bbUsed = snap.getLong("guest_bb_used")?.toInt() ?: 0

                if (isBlackboard) {
                    val limit = 3
                    if (bbUsed >= limit) {
                        callback(CheckResult(
                            allowed = false,
                            reason = "Guest blackboard quota exhausted ($bbUsed / $limit)",
                            upgradeMessage = "You've used all 3 blackboard sessions 🎓\nLogin or upgrade for more!",
                            limitType = LimitType.BB_SESSIONS
                        ))
                    } else {
                        callback(CheckResult(allowed = true))
                    }
                } else {
                    val limit = 10
                    if (chatUsed >= limit) {
                        callback(CheckResult(
                            allowed = false,
                            reason = "Guest chat quota exhausted ($chatUsed / $limit)",
                            upgradeMessage = "You've used all 10 chat questions 💬\nLogin to get daily free questions!",
                            limitType = LimitType.CHAT_QUESTIONS
                        ))
                    } else {
                        callback(CheckResult(allowed = true))
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "checkGuestQuota failed: ${e.message}")
                // On failure, allow to not block user experience
                callback(CheckResult(allowed = true))
            }
    }

    /**
     * Record a guest's question or blackboard session in Firestore.
     * Call after successful AI response.
     */
    fun recordGuestUsage(deviceId: String, isBlackboard: Boolean) {
        val fieldToIncrement = if (isBlackboard) "guest_bb_used" else "guest_chat_used"
        db.collection("devices").document(deviceId)
            .update(fieldToIncrement, FieldValue.increment(1))
            .addOnFailureListener { Log.e(TAG, "recordGuestUsage failed: ${it.message}") }
    }

    /**
     * Increment the AI TTS usage counter in Firestore.
     * Called once per text spoken via AI TTS (with character count).
     */
    fun recordAiTtsUsed(userId: String, charsUsed: Int = 0) {
        if (userId.isBlank() || userId == "guest_user") return
        val updates = mutableMapOf<String, Any>(
            "ai_tts_chars_used_today" to FieldValue.increment(charsUsed.toLong()),
            "ai_tts_updated_at" to System.currentTimeMillis()
        )
        db.collection("users_table").document(userId)
            .update(updates)
            .addOnFailureListener { Log.w(TAG, "recordAiTtsUsed failed uid=$userId charsUsed=$charsUsed: ${it.message}") }
    }

    /**
     * Check if user has exceeded their daily AI TTS character quota.
     * Returns CheckResult.allowed = false if exceeded.
     */
    fun checkAiTtsQuota(
        metadata: UserMetadata,
        limits: PlanLimits,
        charsAboutToUse: Int
    ): CheckResult {
        val quotaLimit = limits.aiTtsQuotaChars
        if (quotaLimit <= 0) return CheckResult(allowed = true)  // 0 or negative = unlimited

        val isSameDay = metadata.aiTtsUpdatedAt > 0L &&
            utcDayOf(metadata.aiTtsUpdatedAt) == utcDayOf(System.currentTimeMillis())

        val charsUsedToday = if (isSameDay) metadata.aiTtsCharsUsedToday else 0
        val charsRemaining = (quotaLimit - charsUsedToday).coerceAtLeast(0)

        if (charsAboutToUse > charsRemaining) {
            return CheckResult(
                allowed        = false,
                reason         = "AI TTS quota exceeded ($charsUsedToday / $quotaLimit chars)",
                upgradeMessage = "AI voice quota reached! You have $charsRemaining chars remaining today.\nUpgrade your plan for more AI voice! 🎙️",
                limitType      = LimitType.FEATURE_AI_TTS
            )
        }
        return CheckResult(allowed = true)
    }

    /**
     * Get AI TTS characters remaining today (or -1 if unlimited).
     */
    fun getAiTtsCharsRemaining(metadata: UserMetadata, limits: PlanLimits): Int {
        val quotaLimit = limits.aiTtsQuotaChars
        if (quotaLimit <= 0) return -1  // unlimited

        val isSameDay = metadata.aiTtsUpdatedAt > 0L &&
            utcDayOf(metadata.aiTtsUpdatedAt) == utcDayOf(System.currentTimeMillis())

        val charsUsedToday = if (isSameDay) metadata.aiTtsCharsUsedToday else 0
        return (quotaLimit - charsUsedToday).coerceAtLeast(0)
    }
}
