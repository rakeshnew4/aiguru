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

    data class BlackboardStep(
        /** Keywords / title for the screen (3–6 words, can use \\n and →). */
        val text: String,
        /** Speech for TTS (1–2 short sentences). */
        val speech: String
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
                                val obj = arr.getJSONObject(i)
                                BlackboardStep(
                                    text   = obj.getString("text"),
                                    speech = obj.getString("speech")
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
        server.streamChat(
            question     = systemPrompt + "\n\nExplanation to convert:\n" + messageContent.take(3000),
            pageId       = "blackboard__lesson",
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
                val obj = arr.getJSONObject(i)
                BlackboardStep(
                    text   = obj.getString("text"),
                    speech = obj.getString("speech")
                )
            }
            if (result.isEmpty()) { onError("No steps were generated"); return }

            // ── 3. Save result to Firestore cache ─────────────────────────────
            try {
                val stepsJson = JSONArray().apply {
                    result.forEach { step ->
                        put(JSONObject().put("text", step.text).put("speech", step.speech))
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
}
