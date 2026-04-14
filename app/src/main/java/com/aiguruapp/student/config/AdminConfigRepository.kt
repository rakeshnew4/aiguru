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

    fun ttsBbProvider(): com.aiguruapp.student.tts.BbAiTtsEngine.Provider =
        when (cachedConfig.ttsProvider.lowercase()) {
            "google"      -> com.aiguruapp.student.tts.BbAiTtsEngine.Provider.GOOGLE
            "elevenlabs"  -> com.aiguruapp.student.tts.BbAiTtsEngine.Provider.ELEVEN_LABS
            "openai"      -> com.aiguruapp.student.tts.BbAiTtsEngine.Provider.OPENAI
            "self_hosted" -> com.aiguruapp.student.tts.BbAiTtsEngine.Provider.SELF_HOSTED
            else          -> com.aiguruapp.student.tts.BbAiTtsEngine.Provider.GOOGLE
        }

    fun ttsGoogleApiKey(): String = cachedConfig.ttsGoogleApiKey
    fun ttsElevenLabsApiKey(): String = cachedConfig.ttsElevenLabsApiKey
    fun ttsOpenAiApiKey(): String = cachedConfig.ttsOpenAiApiKey
    fun ttsSelfHostedUrl(): String =
        cachedConfig.ttsServerUrl.ifBlank { cachedConfig.serverUrl }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun fetchPlans() {
        // Top-level plans/ collection (same hierarchy as users/ and updates/)
        db.collection(PLANS_COL)
            .get()
            .addOnSuccessListener { snap ->
                val loaded = mutableMapOf<String, SubscriptionPlan>()
                for (doc in snap.documents) {
                    try {
                        val plan = doc.toObject(SubscriptionPlan::class.java)
                            ?.copy(planId = doc.id)  // document ID is the authoritative planId
                        if (plan != null) loaded[doc.id] = plan
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse plan ${doc.id}: ${e.message}")
                    }
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
