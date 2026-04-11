package com.aiguruapp.student.config

import android.util.Log
import com.aiguruapp.student.models.AppNotification
import com.aiguruapp.student.models.AppUpdateConfig
import com.aiguruapp.student.models.FirestoreOffer
import com.aiguruapp.student.models.FirestorePlan
import com.aiguruapp.student.models.School
import com.aiguruapp.student.models.SchoolBranding
import com.aiguruapp.student.models.SchoolPlan
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * Fetches and caches all bootstrap data required at app start.
 *
 * Firestore layout (all top-level collections):
 *   plans/{planId}              – subscription plan definitions (FirestorePlan fields)
 *   app_offers/{offerId}        – promotional banner cards
 *   notifications/{notifId}     – in-app broadcast notifications
 *   updates/app_config          – version / maintenance config
 *
 * AdminConfig (server routing, global limits) is still loaded by
 * [AdminConfigRepository] from admin_config/global.
 *
 * Call [fetchAll] once from SplashActivity. Data is then available via
 * the typed properties on this object for the lifetime of the process.
 */
object AppStartRepository {

    private const val TAG = "AppStartRepo"

    private val db = FirebaseFirestore.getInstance()

    // ── Cached data ───────────────────────────────────────────────────────────
    @Volatile var plans:         List<FirestorePlan>      = emptyList(); private set
    @Volatile var offers:        List<FirestoreOffer>     = emptyList(); private set
    @Volatile var notifications: List<AppNotification>   = emptyList(); private set
    @Volatile var updateConfig:  AppUpdateConfig          = AppUpdateConfig(); private set
    @Volatile var schools:       List<School>             = emptyList(); private set

    @Volatile private var fetched = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches all bootstrap collections in parallel.
     * [onComplete] is called on the **calling thread's looper** once every
     * collection has either succeeded or failed (errors fall back to defaults).
     * Safe to call multiple times — subsequent calls are no-ops if data is
     * already loaded.
     */
    fun fetchAll(onComplete: () -> Unit) {
        if (fetched) {
            onComplete()
            return
        }

        var pending = 5  // number of parallel fetches

        fun done() {
            if (--pending == 0) {
                fetched = true
                onComplete()
            }
        }

        fetchPlans         { done() }
        fetchOffers        { done() }
        fetchNotifications { done() }
        fetchUpdateConfig  { done() }
        fetchSchools       { done() }

        // Also kick AdminConfig refresh (non-blocking, no callback needed here)
        AdminConfigRepository.fetchIfStale()
    }

    /** Force re-fetch on next [fetchAll] call (e.g. after settings change). */
    fun invalidate() { fetched = false }

    // ── Private fetchers ──────────────────────────────────────────────────────

    private fun fetchPlans(onDone: () -> Unit) {
        db.collection("plans")
            .get(Source.DEFAULT)
            .addOnSuccessListener { snapshot ->
                try {
                    plans = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(FirestorePlan::class.java)
                            ?.copy(id = doc.id)
                    }.filter { it.isActive }.sortedBy { it.displayOrder }
                    Log.d(TAG, "Plans loaded: ${plans.map { it.id }}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse plans: ${e.message}")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Plans fetch failed: ${e.message}")
                onDone()
            }
    }

    private fun fetchOffers(onDone: () -> Unit) {
        db.collection("app_offers")
            .whereEqualTo("is_active", true)
            .get(Source.DEFAULT)
            .addOnSuccessListener { snapshot ->
                try {
                    offers = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(FirestoreOffer::class.java)
                            ?.copy(id = doc.id)
                    }.sortedBy { it.displayOrder }
                    Log.d(TAG, "Offers loaded: ${offers.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse offers: ${e.message}")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Offers fetch failed: ${e.message}")
                onDone()
            }
    }

    private fun fetchNotifications(onDone: () -> Unit) {
        db.collection("notifications")
            .whereEqualTo("is_active", true)
            .get(Source.DEFAULT)
            .addOnSuccessListener { snapshot ->
                try {
                    notifications = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(AppNotification::class.java)
                            ?.copy(id = doc.id)
                    }.sortedBy { it.displayOrder }
                    Log.d(TAG, "Notifications loaded: ${notifications.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse notifications: ${e.message}")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Notifications fetch failed: ${e.message}")
                onDone()
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchSchools(onDone: () -> Unit) {
        db.collection("schools")
            .get(Source.DEFAULT)
            .addOnSuccessListener { snapshot ->
                try {
                    schools = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        val b = data["branding"] as? Map<String, Any> ?: emptyMap()
                        val plansRaw = data["plans"] as? List<Map<String, Any>> ?: emptyList()
                        val testIds = (data["testStudentIds"] as? List<*>)
                            ?.filterIsInstance<String>() ?: emptyList()
                        School(
                            id            = doc.id,
                            name          = data["name"] as? String ?: "",
                            shortName     = data["shortName"] as? String ?: "",
                            city          = data["city"] as? String ?: "",
                            state         = data["state"] as? String ?: "",
                            code          = data["code"] as? String ?: "",
                            contactEmail  = data["contactEmail"] as? String ?: "",
                            branding      = SchoolBranding(
                                primaryColor         = b["primaryColor"] as? String ?: "#1565C0",
                                primaryDarkColor     = b["primaryDarkColor"] as? String ?: "#003c8f",
                                accentColor          = b["accentColor"] as? String ?: "#FF6F00",
                                backgroundColor      = b["backgroundColor"] as? String ?: "#E3F2FD",
                                headerTextColor      = b["headerTextColor"] as? String ?: "#FFFFFF",
                                headerSubtextColor   = b["headerSubtextColor"] as? String ?: "#90CAF9",
                                bodyTextPrimaryColor = b["bodyTextPrimaryColor"] as? String ?: "#1565C0",
                                logoText             = b["logoText"] as? String ?: "",
                                logoEmoji            = b["logoEmoji"] as? String ?: "🏫"
                            ),
                            plans         = plansRaw.map { p ->
                                SchoolPlan(
                                    id       = p["id"] as? String ?: "",
                                    name     = p["name"] as? String ?: "",
                                    badge    = p["badge"] as? String ?: "",
                                    priceINR = (p["priceINR"] as? Long)?.toInt() ?: 0,
                                    duration = p["duration"] as? String ?: "",
                                    features = (p["features"] as? List<*>)
                                        ?.filterIsInstance<String>() ?: emptyList()
                                )
                            },
                            testStudentIds = testIds
                        )
                    }
                    Log.d(TAG, "Schools loaded: ${schools.map { it.id }}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse schools: ${e.message}")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Schools fetch failed, will use bundled JSON: ${e.message}")
                onDone()
            }
    }

    private fun fetchUpdateConfig(onDone: () -> Unit) {        db.collection("updates")
            .document("app_config")
            .get(Source.DEFAULT)
            .addOnSuccessListener { doc ->
                try {
                    if (doc.exists()) {
                        updateConfig = doc.toAppUpdateConfig()
                        Log.d(TAG, "UpdateConfig loaded: active=${updateConfig.isActive}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse updateConfig: ${e.message}")
                }
                onDone()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "UpdateConfig fetch failed: ${e.message}")
                onDone()
            }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Find a plan by id, falling back to a blank FirestorePlan. */
    fun getPlan(planId: String): FirestorePlan? =
        plans.firstOrNull { it.id == planId }

    /** Find a school by id. */
    fun getSchool(schoolId: String): School? =
        schools.firstOrNull { it.id == schoolId }
}

// ── Extension reused from AppUpdateManager ────────────────────────────────────
// Duplicating the mapping here avoids a circular dependency between
// AppUpdateManager and AppStartRepository.
private fun com.google.firebase.firestore.DocumentSnapshot.toAppUpdateConfig(): AppUpdateConfig {
    return AppUpdateConfig(
        minVersionCode    = getLong("min_version_code")    ?: 0L,
        latestVersionCode = getLong("latest_version_code") ?: 0L,
        latestVersionName = getString("latest_version_name") ?: "",
        updateUrl         = getString("update_url") ?: AppUpdateConfig().updateUrl,
        updateMessage     = getString("update_message") ?: AppUpdateConfig().updateMessage,
        releaseNotes      = getString("release_notes") ?: "",
        isMaintenance     = getBoolean("is_maintenance") ?: false,
        maintenanceMessage = getString("maintenance_message") ?: AppUpdateConfig().maintenanceMessage,
        isActive          = getBoolean("is_active") ?: true,
        supportContact    = getString("support_contact") ?: "",
    )
}
