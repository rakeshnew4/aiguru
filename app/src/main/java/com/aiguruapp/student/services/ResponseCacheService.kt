package com.aiguruapp.student.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Service to cache question-answer pairs locally using SharedPreferences.
 *
 * No annotation processing required — pure Android API.
 * 24-hour TTL prevents stale responses.
 *
 * Usage:
 *   ResponseCacheService.init(context)
 *   val cached = ResponseCacheService.get(pageId, question)
 *   if (cached != null) { showCachedAnswer(cached) }
 *   else { val answer = callLLM(); ResponseCacheService.set(pageId, question, answer) }
 */
object ResponseCacheService {

    private const val TAG = "ResponseCache"
    private const val PREFS_NAME = "response_cache"
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000  // 24 hours

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun generateKey(pageId: String, question: String): String =
        "$pageId:${question.take(200)}".hashCode().toString()

    /** Returns cached answer if present and not expired, otherwise null. */
    fun get(pageId: String, question: String): String? {
        val p = prefs() ?: return null
        return try {
            val key = generateKey(pageId, question)
            val answer = p.getString("ans_$key", null) ?: return null
            val ts = p.getLong("ts_$key", 0L)
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                Log.d(TAG, "Cache HIT for $pageId")
                answer
            } else {
                p.edit().remove("ans_$key").remove("ts_$key").apply()
                Log.d(TAG, "Cache EXPIRED for $pageId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache read failed: ${e.message}")
            null
        }
    }

    /** Stores an answer in the cache. */
    fun set(pageId: String, question: String, answer: String) {
        val p = prefs() ?: return
        try {
            val key = generateKey(pageId, question)
            p.edit()
                .putString("ans_$key", answer)
                .putLong("ts_$key", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Cache STORED for $pageId")
        } catch (e: Exception) {
            Log.e(TAG, "Cache write failed: ${e.message}")
        }
    }

    /** Removes all entries older than 24 hours. */
    fun cleanup() {
        val p = prefs() ?: return
        try {
            val expiry = System.currentTimeMillis() - CACHE_TTL_MS
            val editor = p.edit()
            p.all.entries
                .filter { it.key.startsWith("ts_") && (it.value as? Long ?: 0L) < expiry }
                .forEach { entry ->
                    val id = entry.key.removePrefix("ts_")
                    editor.remove("ts_$id").remove("ans_$id")
                }
            editor.apply()
            Log.d(TAG, "Cache cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cache cleanup failed: ${e.message}")
        }
    }

    /** Clears all cached entries. */
    fun clear() {
        try {
            prefs()?.edit()?.clear()?.apply()
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Cache clear failed: ${e.message}")
        }
    }
}
