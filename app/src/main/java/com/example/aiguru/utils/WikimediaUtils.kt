package com.example.aiguru.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fetches educational images from Wikimedia Commons.
 *
 * Kotlin equivalent of the Python snippet:
 *   requests.get("https://commons.wikimedia.org/w/api.php",
 *                params={action:query, generator:search, gsrnamespace:6, prop:imageinfo, …})
 */
object WikimediaUtils {

    private val client = OkHttpClient()
    private const val API_URL = "https://commons.wikimedia.org/w/api.php"
    private const val TAG = "WikimediaUtils"

    /**
     * Searches Wikimedia Commons for images matching [query].
     *
     * @param query  Search term, e.g. "quadratic equation" or "photosynthesis diagram".
     * @param limit  Max results to request from the API (1–50).
     * @return       Map of image title → direct image URL for raster images only (jpg/png/webp).
     *               Returns an empty map on any error.
     */
    suspend fun searchImages(query: String, limit: Int = 20): Map<String, String> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(query, limit.coerceIn(1, 101))
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AIGuruApp/1.0 (rakeshkolipaka4@gmail.com)")
                .header("Accept", "application/json")
                .build()

            val body = try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code} for query='$query'")
                        return@withContext emptyMap()
                    }
                    resp.body?.string() ?: return@withContext emptyMap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.message}")
                return@withContext emptyMap()
            }

            parseImages(body)
        }

    /**
     * Convenience wrapper — returns the first suitable image URL found for [query], or null.
     * Requests only 1 result to keep latency low.
     */
    suspend fun firstImageUrl(query: String): String? =
        searchImages(query, limit = 1).values.firstOrNull()

    /**
     * Returns the best-scoring (url, confidence 0–10f) pair for [query], or null if no image found.
     * Fetches up to 5 results and picks the one with the highest keyword overlap with the query.
     *
     * Confidence = (matching query words in image title / total query words) × 10
     */
    suspend fun searchWithConfidence(query: String): Pair<String, Float>? {
        val results = searchImages(query, limit = 5)
        if (results.isEmpty()) return null

        val queryWords = query.lowercase()
            .split(Regex("[\\s_\\-,()]+"))
            .filter { it.length > 2 }
            .toSet()

        if (queryWords.isEmpty()) return results.values.first() to 5f

        var bestUrl   = ""
        var bestScore = -1f

        results.forEach { (title, url) ->
            val titleWords = title.lowercase()
                .split(Regex("[\\s_\\-,()]+"))
                .filter { it.length > 2 }
                .toSet()
            val overlap = queryWords.count { qw ->
                titleWords.any { tw -> tw.contains(qw) || qw.contains(tw) }
            }
            val score = (overlap.toFloat() / queryWords.size) * 2f
            if (score > bestScore) {
                bestScore = score
                bestUrl   = url
            }
        }
        return if (bestUrl.isNotBlank()) bestUrl to bestScore else null
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun buildUrl(query: String, limit: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "$API_URL" +
               "?action=query" +
               "&format=json" +
               "&generator=search" +
               "&gsrsearch=${encoded}" +
               "&gsrnamespace=6" +          // File namespace (images)
               "&gsrlimit=$limit" +
               "&prop=imageinfo" +
               "&iiprop=url" +
               "&iiurlwidth=600"
    }

    private fun parseImages(json: String): Map<String, String> = try {
        val pages = JSONObject(json)
            .optJSONObject("query")
            ?.optJSONObject("pages") ?: return emptyMap()

        buildMap {
            pages.keys().forEach { key ->
                val page  = pages.getJSONObject(key)
                val title = page.optString("title", "").removePrefix("File:")
                val url   = page
                    .optJSONArray("imageinfo")
                    ?.optJSONObject(0)
                    ?.optString("url") ?: return@forEach

                if (url.isNotBlank() && isSupportedImage(url)) {
                    put(title, url)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Parse error: ${e.message}")
        emptyMap()
    }

    /** Skip OGG / audio / video; allow jpg, png, webp, and svg. */
    private fun isSupportedImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".jpg")  ||
               lower.endsWith(".jpeg") ||
               lower.endsWith(".png")  ||
               lower.endsWith(".webp") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".svg")
    }
}
