package com.example.aiguru.chat

import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.utils.PromptRepository
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates a short, audio-first step-by-step lesson from a chat message.
 * Call from Dispatchers.IO — uses a blocking HTTP request.
 */
object BlackboardGenerator {

    data class BlackboardFrame(
        val text: String,
        val highlight: List<String> = emptyList(),
        val speech: String,
        val durationMs: Long = 2000
    )

    data class BlackboardStep(
        val title: String = "",
        val frames: List<BlackboardFrame>,
        val languageTag: String = "en-US"
    )

    // System prompt is loaded from assets/tutor_prompts.json → "blackboard_system_prompt"

    /**
     * Generate teaching steps from [messageContent].
     *
     * Cache path: users/{userId}/conversations/{conversationId}/blackboard_cache/{messageId}
     * Falls back to a content-hash document when [messageId] or routing IDs are absent.
     * Must be called from a background thread (blocks on network).
     */
    fun generate(
        messageContent: String,
        messageId: String? = null,
        userId: String? = null,
        conversationId: String? = null,
        preferredLanguageTag: String? = null,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val db = FirebaseFirestore.getInstance()

        // Build the Firestore document reference for the cache:
        // Preferred: users/{userId}/conversations/{conversationId}/blackboard_cache/{messageId}
        // Fallback:  blackboard_cache/bbc_{contentHash}
        val cacheDocRef = if (!userId.isNullOrBlank() && !conversationId.isNullOrBlank() && !messageId.isNullOrBlank()) {
            db.collection("users").document(userId)
                .collection("conversations").document(conversationId)
                .collection("blackboard_cache").document(messageId)
        } else {
            val fallbackKey = if (!messageId.isNullOrBlank()) "bbc_$messageId"
                              else "bbc_${messageContent.take(500).hashCode()}"
            db.collection("blackboard_cache").document(fallbackKey)
        }

        // ── 1. Try cache ──────────────────────────────────────────────────────
        val cacheLatch = java.util.concurrent.CountDownLatch(1)
        var cachedSteps: List<BlackboardStep>? = null
        cacheDocRef.get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    try {
                        val stepsJson = doc.getString("steps") ?: ""
                        if (stepsJson.isNotEmpty()) {
                            val arr = JSONArray(stepsJson)
                            val steps = (0 until arr.length()).map { i ->
                                val stepObj = arr.getJSONObject(i)
                                val langTag = normalizeLanguageTag(
                                    raw = stepObj.optString("lang", stepObj.optString("language", "")),
                                    fallback = preferredLanguageTag ?: "en-US"
                                )
                                val framesArr = stepObj.getJSONArray("frames")
                                val frames = (0 until framesArr.length()).map { j ->
                                    val frameObj = framesArr.getJSONObject(j)
                                    val hlArr = frameObj.optJSONArray("highlight")
                                    BlackboardFrame(
                                        text       = frameObj.getString("text"),
                                        highlight  = if (hlArr != null) (0 until hlArr.length()).map { hlArr.getString(it) } else emptyList(),
                                        speech     = frameObj.optString("speech", ""),
                                        durationMs = frameObj.optLong("duration_ms", 2000)
                                    )
                                }
                                BlackboardStep(
                                    title  = stepObj.optString("title", ""),
                                    frames = frames,
                                    languageTag = langTag
                                )
                            }
                            if (steps.isNotEmpty()) cachedSteps = steps
                        }
                    } catch (_: Exception) {}
                }
                cacheLatch.countDown()
            }
            .addOnFailureListener { cacheLatch.countDown() }
        cacheLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)

        if (cachedSteps != null) {
            onSuccess(cachedSteps!!)
            return
        }


        // ── 2. Generate via LLM ───────────────────────────────────────────────
        val cfg = AdminConfigRepository.config
        val serverUrl = cfg.serverUrl.ifBlank { "http://108.181.187.227:8003" }

        val server = ServerProxyClient(
            serverUrl = serverUrl,
            modelName = "",
            apiKey    = cfg.serverApiKey
        )

        val buffer   = StringBuilder()
        var streamErr: String? = null
        val latch    = java.util.concurrent.CountDownLatch(1)

        val systemPrompt = PromptRepository.getBlackboardSystemPrompt()
        val languageHint = preferredLanguageTag?.takeIf { it.isNotBlank() }
            ?.let { "\n\nPreferred speech language tag: $it" }
            ?: ""
        server.streamChat(
            question     = systemPrompt + languageHint + "\n\nExplanation to convert:\n" + messageContent.take(3000),
            pageId       = "blackboard__lesson",
            mode         = "blackboard",
            languageTag  = preferredLanguageTag ?: "en-US",
            studentLevel = 5,
            history      = emptyList(),
            onToken      = { token -> buffer.append(token) },
            onDone       = { _, _, _ -> latch.countDown() },
            onError      = { err -> streamErr = err; latch.countDown() }
        )

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)

        if (streamErr != null) { onError(streamErr!!); return }

        val response = buffer.toString()
        try {
            val start = response.indexOf('{')
            val end   = response.lastIndexOf('}')
            if (start < 0 || end <= start) { onError("Invalid response format"); return }
            val arr = JSONObject(response.substring(start, end + 1)).getJSONArray("steps")
            val result = (0 until arr.length()).map { i ->
                val stepObj = arr.getJSONObject(i)
                val langTag = normalizeLanguageTag(
                    raw = stepObj.optString("lang", stepObj.optString("language", "")),
                    fallback = preferredLanguageTag ?: "en-US"
                )
                val framesArr = stepObj.getJSONArray("frames")
                val frames = (0 until framesArr.length()).map { j ->
                    val frameObj = framesArr.getJSONObject(j)
                    val hlArr = frameObj.optJSONArray("highlight")
                    BlackboardFrame(
                        text       = frameObj.getString("text"),
                        highlight  = if (hlArr != null) (0 until hlArr.length()).map { hlArr.getString(it) } else emptyList(),
                        speech     = frameObj.optString("speech", ""),
                        durationMs = frameObj.optLong("duration_ms", 2000)
                    )
                }
                BlackboardStep(
                    title  = stepObj.optString("title", ""),
                    frames = frames,
                    languageTag = langTag
                )
            }
            if (result.isEmpty()) { onError("No steps were generated"); return }

            // ── 3. Save result to Firestore cache ─────────────────────────────
            try {
                val stepsJson = JSONArray().apply {
                    result.forEach { step ->
                        val framesJson = JSONArray().apply {
                            step.frames.forEach { frame ->
                                put(JSONObject()
                                    .put("text", frame.text)
                                    .put("highlight", JSONArray(frame.highlight))
                                    .put("speech", frame.speech)
                                    .put("duration_ms", frame.durationMs))
                            }
                        }
                        put(JSONObject()
                            .put("title", step.title)
                            .put("frames", framesJson)
                            .put("lang", step.languageTag))
                    }
                }.toString()
                cacheDocRef
                    .set(mapOf("steps" to stepsJson, "createdAt" to System.currentTimeMillis()))
            } catch (_: Exception) { /* cache write failure is non-fatal */ }

            onSuccess(result)
        } catch (e: Exception) {
            onError("Parse error: ${e.message}")
        }
    }

    private fun normalizeLanguageTag(raw: String?, fallback: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return fallback
        return when (value.lowercase()) {
            "en", "english", "en-us" -> "en-US"
            "hi", "hindi", "hi-in" -> "hi-IN"
            "bn", "bengali", "bn-in" -> "bn-IN"
            "te", "telugu", "te-in" -> "te-IN"
            "ta", "tamil", "ta-in" -> "ta-IN"
            "mr", "marathi", "mr-in" -> "mr-IN"
            "kn", "kannada", "kn-in" -> "kn-IN"
            "gu", "gujarati", "gu-in" -> "gu-IN"
            else -> if (value.contains('-')) value else fallback
        }
    }
}
