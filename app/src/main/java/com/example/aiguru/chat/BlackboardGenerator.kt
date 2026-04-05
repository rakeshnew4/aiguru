package com.example.aiguru.chat

import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.utils.PromptRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
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
        val languageTag: String = "en-US",
        val image_description: String = ""
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

        // Cache doc ID is always the messageId.
        // Preferred path: users/{userId}/conversations/{conversationId}/blackboard_cache/{messageId}
        // Fallback path:  blackboard_cache/{messageId}
        // If no messageId is available caching is skipped entirely.
        val cacheDocRef = when {
            !userId.isNullOrBlank() && !conversationId.isNullOrBlank() && !messageId.isNullOrBlank() ->
                db.collection("users").document(userId)
                    .collection("conversations").document(conversationId)
                    .collection("blackboard_cache").document(messageId)
            !messageId.isNullOrBlank() ->
                db.collection("blackboard_cache").document(messageId)
            else -> null
        }

        // ── 1. Try cache ──────────────────────────────────────────────────────
        // Strategy: Source.CACHE first (instant, no network), then Source.SERVER
        // on a cache miss.  Only hits the LLM if Firestore has no record at all.
        val cacheLatch = java.util.concurrent.CountDownLatch(1)
        var cachedSteps: List<BlackboardStep>? = null

        if (cacheDocRef == null) {
            cacheLatch.countDown()
        } else {
            // Helper that parses a Firestore document into BlackboardStep list
            fun parseDoc(doc: com.google.firebase.firestore.DocumentSnapshot): List<BlackboardStep>? {
                if (!doc.exists()) return null
                return try {
                    val stepsJson = doc.getString("steps") ?: return null
                    if (stepsJson.isEmpty()) return null
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
                            title             = stepObj.optString("title", ""),
                            frames            = frames,
                            languageTag       = langTag,
                            image_description = stepObj.optString("image_description", "")
                        )
                    }
                    steps.ifEmpty { null }
                } catch (_: Exception) { null }
            }

            // Step 1a: local cache — zero network latency
            val localLatch = java.util.concurrent.CountDownLatch(1)
            cacheDocRef.get(Source.CACHE)
                .addOnSuccessListener { doc ->
                    cachedSteps = parseDoc(doc)
                    localLatch.countDown()
                }
                .addOnFailureListener {
                    // Cache miss is expected the first time — not an error
                    localLatch.countDown()
                }
            localLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)

            if (cachedSteps == null) {
                // Step 1b: cache miss — fetch from server (also populates local cache)
                cacheDocRef.get(Source.SERVER)
                    .addOnSuccessListener { doc ->
                        cachedSteps = parseDoc(doc)
                        cacheLatch.countDown()
                    }
                    .addOnFailureListener { cacheLatch.countDown() }
                cacheLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                cacheLatch.countDown()
            }
        } // end if (cacheDocRef != null)

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
                    title             = stepObj.optString("title", ""),
                    frames            = frames,
                    languageTag       = langTag,
                    image_description = stepObj.optString("image_description", "")
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
                            .put("lang", step.languageTag)
                            .put("image_description", step.image_description)
                        )
                    }
                }.toString()
                cacheDocRef
                    ?.set(mapOf("steps" to stepsJson, "createdAt" to System.currentTimeMillis()))
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
