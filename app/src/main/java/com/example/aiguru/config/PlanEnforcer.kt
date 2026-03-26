package com.example.aiguru.config

import android.util.Log
import com.example.aiguru.models.PlanLimits
import com.example.aiguru.models.UserMetadata
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
        DAILY_TOKENS,
        MONTHLY_TOKENS,
        MESSAGES_PER_HOUR,
        FEATURE_IMAGE,
        FEATURE_VOICE,
        FEATURE_PDF,
        BLACKBOARD,
        FEATURE_BLACKBOARD
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
            FeatureType.BLACKBOARD -> if (!limits.blackboardEnabled) return CheckResult(
                allowed        = false,
                reason         = "Blackboard explanation not available on ${metadata.planName} plan",
                upgradeMessage = "Visual blackboard lessons are a premium feature. Upgrade to unlock step-by-step visual explanations! 🎓",
                limitType      = LimitType.BLACKBOARD
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
    fun recordTokensUsed(userId: String, tokens: Int) {
        if (tokens <= 0 || userId.isBlank() || userId == "guest_user") return

        // Increment hourly counter
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        val (count, prevHour) = hourlyCounters[userId] ?: Pair(0, hour)
        hourlyCounters[userId] = Pair((if (hour == prevHour) count else 0) + 1, hour)

        val now = System.currentTimeMillis()
        val updates = mapOf(
            "tokens_today"      to FieldValue.increment(tokens.toLong()),
            "tokens_this_month" to FieldValue.increment(tokens.toLong()),
            "tokens_updated_at" to now
        )
        db.collection("users").document(userId)
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
            db.collection("users").document(userId)
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

    enum class FeatureType { TEXT_CHAT, IMAGE_UPLOAD, VOICE_MODE, PDF_UPLOAD, BLACKBOARD }
}
