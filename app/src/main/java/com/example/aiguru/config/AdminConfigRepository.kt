package com.example.aiguru.config

import android.util.Log
import com.example.aiguru.models.AdminConfig
import com.example.aiguru.models.PlanLimits
import com.example.aiguru.models.SubscriptionPlan
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Loads and caches AdminConfig + SubscriptionPlan definitions from Firestore.
 *
 * Firestore layout:
 *   admin_config/global            ← AdminConfig document
 *   admin_config/plans/{planId}    ← SubscriptionPlan documents
 *
 * Cache lifetime is controlled by AdminConfig.cacheMaxAgeMs (default 1 hour).
 * Falls back to safe hardcoded defaults if Firestore is unreachable.
 */
object AdminConfigRepository {

    private const val TAG = "AdminConfig"
    private const val COLLECTION = "admin_config"
    private const val GLOBAL_DOC = "global"
    private const val PLANS_COL  = "plans"

    private val db = FirebaseFirestore.getInstance()

    // ── In-memory cache ───────────────────────────────────────────────────────
    @Volatile private var cachedConfig: AdminConfig = AdminConfig()
    @Volatile private var cachedPlans:  Map<String, SubscriptionPlan> = emptyMap()
    @Volatile private var lastFetchMs:  Long = 0L
    @Volatile private var isFetching:   Boolean = false

    /** Returns the currently cached AdminConfig (may be the default if not yet loaded). */
    val config: AdminConfig get() = cachedConfig

    /** Returns all known plans keyed by planId. */
    val plans: Map<String, SubscriptionPlan> get() = cachedPlans

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch AdminConfig + all plans from Firestore if the cache has expired.
     * Non-blocking — results are cached for subsequent [config] reads.
     * Safe to call on every app launch.
     */
    fun fetchIfStale() {
        val age = System.currentTimeMillis() - lastFetchMs
        if (age < cachedConfig.cacheMaxAgeMs || isFetching) return
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
                // Also fetch plans
                fetchPlans()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "AdminConfig fetch failed, using cached: ${e.message}")
                isFetching  = false
                lastFetchMs = System.currentTimeMillis() // back-off — don't retry immediately
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

    /** Resolve the server model name for a given tier key. */
    fun modelNameForTier(tier: String): String =
        cachedConfig.modelTiers[tier] ?: cachedConfig.modelTiers["standard"] ?: ""

    // ── Private ───────────────────────────────────────────────────────────────

    private fun fetchPlans() {
        db.collection(COLLECTION).document(GLOBAL_DOC)
            .collection(PLANS_COL)
            .get()
            .addOnSuccessListener { snap ->
                val loaded = mutableMapOf<String, SubscriptionPlan>()
                for (doc in snap.documents) {
                    try {
                        val plan = doc.toObject(SubscriptionPlan::class.java)
                        if (plan != null) loaded[plan.planId] = plan
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
