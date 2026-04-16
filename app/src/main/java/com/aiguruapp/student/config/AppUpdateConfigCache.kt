package com.aiguruapp.student.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aiguruapp.student.models.AppUpdateConfig
import org.json.JSONObject

/**
 * Local SharedPreferences cache for app_config from Firestore.
 *
 * Provides offline fallback + longer retention when Firestore is unavailable.
 *
 * Cache lifetime: 7 days (allows users to stay on old version if Firestore is down).
 *
 * Usage:
 *   AppUpdateConfigCache.save(context, config)
 *   val cached = AppUpdateConfigCache.get(context)  // returns null if expired
 */
object AppUpdateConfigCache {

    private const val TAG = "AppUpdateConfigCache"
    private const val PREFS_NAME = "app_update_config_cache"
    private const val KEY_CONFIG = "app_config_json"
    private const val KEY_TIMESTAMP = "app_config_ts"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save an app config to the local cache.
     */
    fun save(context: Context, config: AppUpdateConfig) {
        try {
            val json = JSONObject().apply {
                put("min_version_code", config.minVersionCode)
                put("latest_version_code", config.latestVersionCode)
                put("latest_version_name", config.latestVersionName)
                put("update_url", config.updateUrl)
                put("update_message", config.updateMessage)
                put("release_notes", config.releaseNotes)
                put("is_maintenance", config.isMaintenance)
                put("maintenance_message", config.maintenanceMessage)
                put("is_active", config.isActive)
                put("support_contact", config.supportContact)
            }
            prefs(context).edit()
                .putString(KEY_CONFIG, json.toString())
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Cached app config: minVer=${config.minVersionCode}, active=${config.isActive}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache app config: ${e.message}")
        }
    }

    /**
     * Retrieve cached app config if available and not expired.
     * Returns null if no cache or cache expired.
     */
    fun get(context: Context): AppUpdateConfig? {
        return try {
            val p = prefs(context)
            val json = p.getString(KEY_CONFIG, null) ?: return null
            val ts = p.getLong(KEY_TIMESTAMP, 0L)
            
            // Check if cache has expired
            if (System.currentTimeMillis() - ts > CACHE_TTL_MS) {
                Log.d(TAG, "Cached app config expired (> 7 days)")
                p.edit().remove(KEY_CONFIG).remove(KEY_TIMESTAMP).apply()
                return null
            }
            
            val obj = JSONObject(json)
            AppUpdateConfig(
                minVersionCode = obj.optLong("min_version_code", 0L),
                latestVersionCode = obj.optLong("latest_version_code", 0L),
                latestVersionName = obj.optString("latest_version_name", ""),
                updateUrl = obj.optString("update_url", "https://play.google.com/store/apps/details?id=com.aiguruapp.student"),
                updateMessage = obj.optString("update_message", ""),
                releaseNotes = obj.optString("release_notes", ""),
                isMaintenance = obj.optBoolean("is_maintenance", false),
                maintenanceMessage = obj.optString("maintenance_message", ""),
                isActive = obj.optBoolean("is_active", true),
                supportContact = obj.optString("support_contact", "")
            ).also {
                Log.d(TAG, "Retrieved cached app config: minVer=${it.minVersionCode}, active=${it.isActive}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached app config: ${e.message}")
            null
        }
    }

    /**
     * Clear the cache (useful after maintenance or for testing).
     */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_CONFIG).remove(KEY_TIMESTAMP).apply()
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache: ${e.message}")
        }
    }
}
