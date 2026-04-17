package com.aiguruapp.student.config

import android.util.Log
import com.aiguruapp.student.models.AdminConfig
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.models.SubscriptionPlan
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Loads and caches AdminConfig + SubscriptionPlan definitions from Firestore.
 *
 * Firestore layout:
 *   admin_config/global   ← AdminConfig document (server URL, model tiers, limits)
 *   plans/{planId}        ← SubscriptionPlan documents (top-level collection)
 *
 * Cache lifetime is controlled by AdminConfig.cacheMaxAgeMs (default 1 hour).
 * Falls back to safe hardcoded defaults if Firestore is unreachable.
 */
object AdminConfigRepository {

    private const val TAG = "AdminConfig"
    private const val COLLECTION = "admin_config"
    private const val GLOBAL_DOC = "global"
    // Plans are in the top-level plans/ collection (same level as users/, updates/)
    private const val PLANS_COL  = "plans"

    private val db = FirebaseFirestore.getInstance()

    // ── In-memory cache ───────────────────────────────────────────────────────
    @Volatile private var cachedConfig: AdminConfig = AdminConfig()
    @Volatile private var cachedPlans:  Map<String, SubscriptionPlan> = emptyMap()
    @Volatile private var lastFetchMs:  Long = 0L
    @Volatile private var isFetching:   Boolean = false

    /** Returns the currently cached AdminConfig (may be the default if not yet loaded). */
    val config: AdminConfig get() = cachedConfig

    /** Returns all known plans keyed by planId. Populated by [AppStartRepository] too. */
    val plans: Map<String, SubscriptionPlan> get() = cachedPlans

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch AdminConfig + all plans from Firestore if the cache has expired.
     * Non-blocking — results are cached for subsequent [config] reads.
     * Safe to call on every app launch.
     */
    fun fetchIfStale() = fetchIfStale(null)

    /**
     * Same as [fetchIfStale] but calls [onReady] with the loaded (or cached) config
     * once fetching is done. Useful when config values are needed immediately after load.
     */
    fun fetchIfStale(onReady: ((AdminConfig) -> Unit)?) {
        val age = System.currentTimeMillis() - lastFetchMs
        if (age < cachedConfig.cacheMaxAgeMs || isFetching) {
            onReady?.invoke(cachedConfig)
            return
        }
        isFetching = true

        // Fetch global config
        db.collection(COLLECTION).document(GLOBAL_DOC)
            .get()
            .addOnSuccessListener { doc ->
                try {
                    if (doc.exists()) {
                        val loaded = doc.toObject(AdminConfig::class.java)
                        if (loaded != null) {
                            cachedConfig = loaded
                            Log.d(TAG, "AdminConfig loaded: serverUrl=${loaded.serverUrl} maintenance=${loaded.maintenanceMode}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse AdminConfig: ${e.message}")
                }
                lastFetchMs = System.currentTimeMillis()
                isFetching  = false
                onReady?.invoke(cachedConfig)
                // Also fetch plans
                fetchPlans()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "AdminConfig fetch failed, using cached: ${e.message}")
                isFetching  = false
                lastFetchMs = System.currentTimeMillis() // back-off — don't retry immediately
                onReady?.invoke(cachedConfig)
                fetchPlans() // still load plans even if admin config read fails
            }
    }

    /**
     * Force a fresh fetch regardless of cache age.
     * Use this after admin makes changes and wants to test immediately.
     */
    fun forceRefresh() {
        lastFetchMs = 0L
        fetchIfStale()
    }

    /**
     * Resolve the effective [PlanLimits] for a user.
     * Priority: userOverrideLimits > plan limits > global defaultLimits
     * Also applies global hard caps.
     */
    fun resolveEffectiveLimits(
        planId: String,
        userOverrideLimits: PlanLimits? = null
    ): PlanLimits {
        val planLimits = cachedPlans[planId]?.limits ?: cachedConfig.defaultLimits
        val base = userOverrideLimits ?: planLimits

        // Apply global hard caps — admin can never be exceeded regardless of plan
        return base.copy(
            dailyTokenLimit = if (cachedConfig.globalDailyTokenHardCap > 0)
                minOf(base.dailyTokenLimit.takeIf { it > 0 } ?: Int.MAX_VALUE,
                    cachedConfig.globalDailyTokenHardCap)
                else base.dailyTokenLimit,
            monthlyTokenLimit = if (cachedConfig.globalMonthlyTokenHardCap > 0)
                minOf(base.monthlyTokenLimit.takeIf { it > 0 } ?: Int.MAX_VALUE,
                    cachedConfig.globalMonthlyTokenHardCap)
                else base.monthlyTokenLimit
        )
    }

    /**
     * Async version of [resolveEffectiveLimits].
     *
     * If the plan is already in the in-memory cache, [onResult] is called synchronously.
     * Otherwise the plan is fetched directly from Firestore and the cache is updated before
     * calling [onResult].  This prevents premium users from being gated at free-plan limits
     * when [cachedPlans] is empty (e.g. plans haven't loaded yet at the time of the quota check).
     *
     * Safe to call from any thread; [onResult] is delivered on the Firestore callback thread
     * (usually a background thread) — callers must post to the main thread if they touch UI.
     */
    fun resolveEffectiveLimitsAsync(
        planId: String,
        userOverrideLimits: PlanLimits? = null,
        onResult: (PlanLimits) -> Unit
    ) {
        // If the plan is already cached (or the user is on the free plan which uses defaultLimits),
        // resolve synchronously and return immediately.
        // Note: a plan with dailyChatQuestions==0 is legitimately unlimited — 0 is a valid value.
        // Plans only end up missing from cachedPlans if toObject() threw (e.g. before
        // @IgnoreExtraProperties was added). If the plan is present, it was successfully parsed.
        if (planId.isBlank() || planId == "free") {
            onResult(resolveEffectiveLimits(planId, userOverrideLimits))
            return
        }
        if (cachedPlans.containsKey(planId)) {
            onResult(resolveEffectiveLimits(planId, userOverrideLimits))
            return
        }
        // Plan not yet cached — fetch directly from Firestore
        db.collection(PLANS_COL).document(planId)
            .get()
            .addOnSuccessListener { doc ->
                val plan = planFromDoc(doc)
                cachedPlans = cachedPlans.toMutableMap().also { it[doc.id] = plan }
                Log.d(TAG, "Fetched plan $planId on-demand: chat=${plan.limits.dailyChatQuestions} bb=${plan.limits.dailyBlackboardSessions}")
                onResult(resolveEffectiveLimits(planId, userOverrideLimits))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "On-demand plan fetch failed for $planId — using defaults: ${e.message}")
                onResult(resolveEffectiveLimits(planId, userOverrideLimits))
            }
    }

    /** Get a plan by id, with fallback to the free plan defaults. */
    fun getPlan(planId: String): SubscriptionPlan =
        cachedPlans[planId] ?: SubscriptionPlan(planId = planId.ifBlank { "free" })

    /**
     * Returns the server base URL from Firestore admin_config/global.server_url.
     * This is the ONLY place you should obtain the server URL — never hardcode it elsewhere.
     */
    fun effectiveServerUrl(): String = cachedConfig.serverUrl

    /** Razorpay publishable key from Firestore. Falls back to empty string if not loaded yet. */
    fun razorpayKeyId(): String = cachedConfig.razorpayKeyId

    /** Resolve the server model name for a given tier key. */
    fun modelNameForTier(tier: String): String =
        cachedConfig.modelTiers[tier] ?: cachedConfig.modelTiers["standard"] ?: ""

    // ── TTS credentials (fetched from Firestore, never kept in APK) ──────────

    fun ttsSelfHostedUrl(): String =
        cachedConfig.ttsServerUrl.ifBlank { cachedConfig.serverUrl }

    /**
     * Returns the shared guest Firebase UID from admin config.
     * Guests sign in with this UID so the server can apply the guest plan limits.
     */
    fun guestId(): String = cachedConfig.guestId.ifBlank { "BujsVJE2cMX6wU7Jg3acMUlRChm1" }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Parse a plan document using raw map access — same approach as HomeActivity.
     * Avoids toObject() / @IgnoreExtraProperties issues with unknown Firestore fields.
     */
    @Suppress("UNCHECKED_CAST")
    private fun planFromDoc(doc: com.google.firebase.firestore.DocumentSnapshot): SubscriptionPlan {
        val m = doc.get("limits") as? Map<String, Any> ?: emptyMap()
        fun int(key: String, default: Int = 0)      = (m[key] as? Long)?.toInt() ?: default
        fun bool(key: String, default: Boolean = false) = m[key] as? Boolean ?: default
        fun str(key: String, default: String = "")  = m[key] as? String ?: default

        val limits = PlanLimits(
            dailyChatQuestions        = int("daily_chat_questions"),
            dailyBlackboardSessions   = int("daily_bb_sessions"),
            dailyTokenLimit           = int("daily_token_limit", 100_000),
            monthlyTokenLimit         = int("monthly_token_limit"),
            contextWindowMessages     = int("context_window_messages", 20),
            contextWindowChars        = int("context_window_chars", 8_000),
            imageUploadEnabled        = bool("image_upload_enabled", true),
            voiceModeEnabled          = bool("voice_mode_enabled"),
            pdfEnabled                = bool("pdf_enabled"),
            flashcardsEnabled         = bool("flashcards_enabled"),
            conversationSummaryEnabled = bool("conversation_summary_enabled"),
            blackboardEnabled         = bool("blackboard_enabled"),
            modelTier                 = str("model_tier", "standard"),
            messagesPerHour           = int("messages_per_hour", 30),
            maxSessions               = int("max_sessions", 1),
            ttsEnabled                = bool("tts_enabled", true),
            aiTtsEnabled              = bool("ai_tts_enabled"),
            aiTtsQuotaChars           = int("ai_tts_quota_chars")
        )
        return SubscriptionPlan(
            planId       = doc.id,
            displayName  = doc.getString("name") ?: doc.id,
            tagline      = doc.getString("tagline") ?: "",
            priceDisplay = doc.getString("priceDisplay") ?: "",
            isPublic     = doc.getBoolean("isPublic") ?: true,
            displayOrder = (doc.getLong("displayOrder") ?: 0L).toInt(),
            accentColor  = doc.getString("accentColor") ?: "#1565C0",
            limits       = limits
        )
    }

    private fun fetchPlans() {
        db.collection(PLANS_COL)
            .get()
            .addOnSuccessListener { snap ->
                val loaded = mutableMapOf<String, SubscriptionPlan>()
                for (doc in snap.documents) {
                    val plan = planFromDoc(doc)
                    loaded[doc.id] = plan
                    Log.d(TAG, "Parsed plan ${doc.id}: chat=${plan.limits.dailyChatQuestions} bb=${plan.limits.dailyBlackboardSessions}")
                }
                if (loaded.isNotEmpty()) {
                    cachedPlans = loaded
                    Log.d(TAG, "Loaded ${loaded.size} subscription plans: ${loaded.keys}")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Plans fetch failed: ${e.message}")
            }
    }
}
