package com.aiguruapp.student.chat

import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.utils.PromptRepository
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
        val durationMs: Long = 2000,
        // concept | quiz | memory | summary | quiz_mcq | quiz_typed | quiz_voice
        // | quiz_fill (fill-in-the-blank) | quiz_order (drag to order steps)
        val frameType: String = "concept",
        // ── Voice Engine & Role ───────────────────────────────────────────────
        // ttsEngine: android | gemini | google
        //   android = instant built-in TTS (quiz frames, first frame)
        //   gemini  = premium AI voice (concept / memory frames)
        //   google  = neural cloud voice (summary / assistant frames)
        // voiceRole: teacher | assistant | quiz | feedback
        val ttsEngine: String = "",           // "" = auto-assign via smartAssignTts()
        val voiceRole: String = "",           // "" = auto-assign via smartAssignTts()
        val quizAnswer: String = "",          // legacy tap-to-reveal answer (frame_type "quiz")
        // ── Interactive quiz fields ───────────────────────────────────────────
        val quizOptions: List<String> = emptyList(),  // quiz_mcq / quiz_order: option texts
        val quizCorrectIndex: Int = -1,               // quiz_mcq: 0–3 index of correct option
        val quizModelAnswer: String = "",             // quiz_typed/voice: reference answer for AI grading
        val quizKeywords: List<String> = emptyList(), // keywords the student answer must cover
        // ── quiz_fill specific ────────────────────────────────────────────────
        val fillBlanks: List<String> = emptyList(),   // correct words for each blank in text
        // ── quiz_order specific ───────────────────────────────────────────────
        // quizOptions holds the steps in SHUFFLED order; quizCorrectOrder holds correct 0-based indices
        val quizCorrectOrder: List<Int> = emptyList()
    )

    data class BlackboardStep(
        val title: String = "",
        val frames: List<BlackboardFrame>,
        val languageTag: String = "en-US",
        val image_description: String = "",
        val imageConfidenceScore: Float = 0f   // 0.0 = skip, 0.4–0.69 = tap-only, ≥0.7 = inline
    )

    /**
     * Returns (ttsEngine, voiceRole) for a frame that lacks explicit values.
     *
     * Rules:
     *   concept / memory  → gemini  / teacher   (premium feel — LLM explains)
     *   summary           → google  / assistant  (neural but cheap — brief recap)
     *   quiz_*            → android / quiz       (instant, no latency during quiz)
     *   everything else   → android / teacher    (safe default)
     *
     * The very first frame of a lesson is always overridden to android/teacher
     * in BlackboardActivity.speakFrame() to guarantee zero-latency playback.
     */
    fun smartAssignTts(frameType: String): Pair<String, String> = when {
        frameType == "concept"              -> Pair("gemini",  "teacher")
        frameType == "memory"               -> Pair("gemini",  "teacher")
        frameType == "summary"              -> Pair("google",  "assistant")
        frameType.startsWith("quiz")        -> Pair("android", "quiz")
        else                                -> Pair("android", "teacher")
    }

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
        onStatus: ((String, Int) -> Unit)? = null,
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
                            val hlArr    = frameObj.optJSONArray("highlight")
                            val optsArr  = frameObj.optJSONArray("quiz_options")
                            val kwArr    = frameObj.optJSONArray("quiz_keywords")
                            val fillArr  = frameObj.optJSONArray("fill_blanks")
                            val orderArr = frameObj.optJSONArray("quiz_correct_order")
                            val fType    = frameObj.optString("frame_type", "concept")
                            // Parse engine/role; fall back to smart assignment for old/cached frames
                            val rawEngine = frameObj.optString("tts_engine", "")
                            val rawRole   = frameObj.optString("voice_role",  "")
                            val (assignedEngine, assignedRole) = smartAssignTts(fType)
                            BlackboardFrame(
                                text              = frameObj.getString("text"),
                                highlight         = if (hlArr != null) (0 until hlArr.length()).map { hlArr.getString(it) } else emptyList(),
                                speech            = frameObj.optString("speech", ""),
                                durationMs        = frameObj.optLong("duration_ms", 2000),
                                frameType         = fType,
                                ttsEngine         = rawEngine.ifBlank { assignedEngine },
                                voiceRole         = rawRole.ifBlank { assignedRole },
                                quizAnswer        = frameObj.optString("quiz_answer", ""),
                                quizOptions       = if (optsArr != null) (0 until optsArr.length()).map { optsArr.getString(it) } else emptyList(),
                                quizCorrectIndex  = frameObj.optInt("quiz_correct_index", -1),
                                quizModelAnswer   = frameObj.optString("quiz_model_answer", ""),
                                quizKeywords      = if (kwArr != null) (0 until kwArr.length()).map { kwArr.getString(it) } else emptyList(),
                                fillBlanks        = if (fillArr != null) (0 until fillArr.length()).map { fillArr.getString(it) } else emptyList(),
                                quizCorrectOrder  = if (orderArr != null) (0 until orderArr.length()).map { orderArr.getInt(it) } else emptyList()
                            )
                        }
                        BlackboardStep(
                            title                = stepObj.optString("title", ""),
                            frames               = frames,
                            languageTag          = langTag,
                            image_description    = stepObj.optString("image_description", ""),
                            imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat()
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
        val serverUrl = AdminConfigRepository.effectiveServerUrl()

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
            question     = "\n\nExplanation to convert:\n" + messageContent.take(3000),
            pageId       = "blackboard__lesson",
            mode         = "blackboard",
            languageTag  = preferredLanguageTag ?: "en-US",
            studentLevel = 5,
            history      = emptyList(),
            onToken      = { token -> buffer.append(token) },
            onStatus     = onStatus,
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
                    val hlArr    = frameObj.optJSONArray("highlight")
                    val optsArr  = frameObj.optJSONArray("quiz_options")
                    val kwArr    = frameObj.optJSONArray("quiz_keywords")
                    val fillArr  = frameObj.optJSONArray("fill_blanks")
                    val orderArr = frameObj.optJSONArray("quiz_correct_order")
                    val fType2   = frameObj.optString("frame_type", "concept")
                    val rawEng2  = frameObj.optString("tts_engine", "")
                    val rawRole2 = frameObj.optString("voice_role",  "")
                    val (aEng2, aRole2) = smartAssignTts(fType2)
                    BlackboardFrame(
                        text             = frameObj.getString("text"),
                        highlight        = if (hlArr != null) (0 until hlArr.length()).map { hlArr.getString(it) } else emptyList(),
                        speech           = frameObj.optString("speech", ""),
                        durationMs       = frameObj.optLong("duration_ms", 2000),
                        frameType        = fType2,
                        ttsEngine        = rawEng2.ifBlank { aEng2 },
                        voiceRole        = rawRole2.ifBlank { aRole2 },
                        quizAnswer       = frameObj.optString("quiz_answer", ""),
                        quizOptions      = if (optsArr != null) (0 until optsArr.length()).map { optsArr.getString(it) } else emptyList(),
                        quizCorrectIndex = frameObj.optInt("quiz_correct_index", -1),
                        quizModelAnswer  = frameObj.optString("quiz_model_answer", ""),
                        quizKeywords     = if (kwArr != null) (0 until kwArr.length()).map { kwArr.getString(it) } else emptyList(),
                        fillBlanks       = if (fillArr != null) (0 until fillArr.length()).map { fillArr.getString(it) } else emptyList(),
                        quizCorrectOrder = if (orderArr != null) (0 until orderArr.length()).map { orderArr.getInt(it) } else emptyList()
                    )
                }
                BlackboardStep(
                    title                = stepObj.optString("title", ""),
                    frames               = frames,
                    languageTag          = langTag,
                    image_description    = stepObj.optString("image_description", ""),
                    imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat()
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
                                    .put("duration_ms", frame.durationMs)
                                    .put("frame_type", frame.frameType)
                                    .put("tts_engine", frame.ttsEngine)
                                    .put("voice_role", frame.voiceRole)
                                    .put("quiz_answer", frame.quizAnswer)
                                    .put("quiz_options", JSONArray(frame.quizOptions))
                                    .put("quiz_correct_index", frame.quizCorrectIndex)
                                    .put("quiz_model_answer", frame.quizModelAnswer)
                                    .put("quiz_keywords", JSONArray(frame.quizKeywords))
                                    .put("fill_blanks", JSONArray(frame.fillBlanks))
                                    .put("quiz_correct_order", JSONArray(frame.quizCorrectOrder)))
                            }
                        }
                        put(JSONObject()
                            .put("title", step.title)
                            .put("frames", framesJson)
                            .put("lang", step.languageTag)
                            .put("image_description", step.image_description)
                            .put("image_show_confidencescore", step.imageConfidenceScore.toDouble())
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

    // ── Serialization helpers ─────────────────────────────────────────────────

    /**
     * Serialize a list of [BlackboardStep] to a compact JSON string.
     * Used when publishing a lesson to the global bb_cache collection.
     */
    fun serializeSteps(steps: List<BlackboardStep>): String {
        val arr = JSONArray()
        steps.forEach { step ->
            val framesJson = JSONArray()
            step.frames.forEach { frame ->
                framesJson.put(
                    JSONObject()
                        .put("text", frame.text)
                        .put("highlight", JSONArray(frame.highlight))
                        .put("speech", frame.speech)
                        .put("duration_ms", frame.durationMs)
                        .put("frame_type", frame.frameType)
                        .put("tts_engine", frame.ttsEngine)
                        .put("voice_role", frame.voiceRole)
                        .put("quiz_answer", frame.quizAnswer)
                        .put("quiz_options", JSONArray(frame.quizOptions))
                        .put("quiz_correct_index", frame.quizCorrectIndex)
                        .put("quiz_model_answer", frame.quizModelAnswer)
                        .put("quiz_keywords", JSONArray(frame.quizKeywords))
                        .put("fill_blanks", JSONArray(frame.fillBlanks))
                        .put("quiz_correct_order", JSONArray(frame.quizCorrectOrder))
                )
            }
            arr.put(
                JSONObject()
                    .put("title", step.title)
                    .put("frames", framesJson)
                    .put("lang", step.languageTag)
                    .put("image_description", step.image_description)
                    .put("image_show_confidencescore", step.imageConfidenceScore.toDouble())
            )
        }
        return arr.toString()
    }

    /**
     * Load a BB lesson from the teacher's own conversation cache:
     * [users/{userId}/conversations/{conversationId}/blackboard_cache/{messageId}]
     *
     * Used by [TeacherSavedContentActivity] before publishing to the global [bb_cache/] collection.
     * Must be called from a background thread.
     */
    fun loadFromUserCache(
        userId: String,
        conversationId: String,
        messageId: String,
        preferredLanguageTag: String? = null,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<BlackboardStep>? = null
        var errorMsg = ""

        val ref = FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("conversations").document(conversationId)
            .collection("blackboard_cache").document(messageId)

        ref.get().addOnSuccessListener { doc ->
            if (!doc.exists()) { errorMsg = "Session cache not found"; latch.countDown(); return@addOnSuccessListener }
            val stepsJson = doc.getString("steps") ?: ""
            if (stepsJson.isEmpty()) { errorMsg = "No steps data in cache"; latch.countDown(); return@addOnSuccessListener }
            try {
                val arr = JSONArray(stepsJson)
                val langFallback = preferredLanguageTag ?: "en-US"
                result = (0 until arr.length()).map { i ->
                    val stepObj = arr.getJSONObject(i)
                    val langTag = normalizeLanguageTag(
                        raw      = stepObj.optString("lang", stepObj.optString("language", "")),
                        fallback = langFallback
                    )
                    val framesArr = stepObj.getJSONArray("frames")
                    val frames = (0 until framesArr.length()).map { j ->
                        val f = framesArr.getJSONObject(j)
                        val hlArr    = f.optJSONArray("highlight")
                        val optsArr  = f.optJSONArray("quiz_options")
                        val kwArr    = f.optJSONArray("quiz_keywords")
                        val fillArr  = f.optJSONArray("fill_blanks")
                        val orderArr = f.optJSONArray("quiz_correct_order")
                        val fType    = f.optString("frame_type", "concept")
                        val rawEngine = f.optString("tts_engine", "")
                        val rawRole   = f.optString("voice_role",  "")
                        val (aEngine, aRole) = smartAssignTts(fType)
                        BlackboardFrame(
                            text             = f.getString("text"),
                            highlight        = hlArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                            speech           = f.optString("speech", ""),
                            durationMs       = f.optLong("duration_ms", 2000),
                            frameType        = fType,
                            ttsEngine        = rawEngine.ifBlank { aEngine },
                            voiceRole        = rawRole.ifBlank { aRole },
                            quizAnswer       = f.optString("quiz_answer", ""),
                            quizOptions      = optsArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                            quizCorrectIndex = f.optInt("quiz_correct_index", -1),
                            quizModelAnswer  = f.optString("quiz_model_answer", ""),
                            quizKeywords     = kwArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                            fillBlanks       = fillArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                            quizCorrectOrder = orderArr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
                        )
                    }
                    BlackboardStep(
                        title                = stepObj.optString("title", ""),
                        frames               = frames,
                        languageTag          = langTag,
                        image_description    = stepObj.optString("image_description", ""),
                        imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat()
                    )
                }
            } catch (e: Exception) {
                errorMsg = "Parse error: ${e.message}"
            }
            latch.countDown()
        }.addOnFailureListener { e ->
            errorMsg = e.message ?: "Failed to load cache"
            latch.countDown()
        }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        if (result != null && result!!.isNotEmpty()) onSuccess(result!!)
        else onError(errorMsg.ifBlank { "Session cache could not be loaded" })
    }

    /**
     * Load a teacher-shared BB lesson directly from the global [bb_cache/{bbCacheId}] collection.
     * No LLM call — returns cached steps immediately.
     * Must be called from a background thread.
     */
    fun loadFromGlobalCache(
        bbCacheId: String,
        preferredLanguageTag: String? = null,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<BlackboardStep>? = null
        var errorMsg = ""

        FirebaseFirestore.getInstance()
            .collection("bb_cache").document(bbCacheId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    errorMsg = "Lesson not found (bb_cache/$bbCacheId)"
                    latch.countDown()
                    return@addOnSuccessListener
                }
                val stepsJson = doc.getString("steps_json") ?: ""
                if (stepsJson.isEmpty()) {
                    errorMsg = "Lesson data is empty"
                    latch.countDown()
                    return@addOnSuccessListener
                }
                try {
                    val arr = JSONArray(stepsJson)
                    val langFallback = preferredLanguageTag
                        ?: doc.getString("language_tag")
                        ?: "en-US"
                    result = (0 until arr.length()).map { i ->
                        val stepObj = arr.getJSONObject(i)
                        val langTag = normalizeLanguageTag(
                            raw      = stepObj.optString("lang", stepObj.optString("language", "")),
                            fallback = langFallback
                        )
                        val framesArr = stepObj.getJSONArray("frames")
                        val frames = (0 until framesArr.length()).map { j ->
                            val frameObj  = framesArr.getJSONObject(j)
                            val hlArr     = frameObj.optJSONArray("highlight")
                            val optsArr   = frameObj.optJSONArray("quiz_options")
                            val kwArr     = frameObj.optJSONArray("quiz_keywords")
                            val fillArr   = frameObj.optJSONArray("fill_blanks")
                            val orderArr  = frameObj.optJSONArray("quiz_correct_order")
                            val fType     = frameObj.optString("frame_type", "concept")
                            val rawEngine = frameObj.optString("tts_engine", "")
                            val rawRole   = frameObj.optString("voice_role", "")
                            val (aEngine, aRole) = smartAssignTts(fType)
                            BlackboardFrame(
                                text             = frameObj.getString("text"),
                                highlight        = hlArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                speech           = frameObj.optString("speech", ""),
                                durationMs       = frameObj.optLong("duration_ms", 2000),
                                frameType        = fType,
                                ttsEngine        = rawEngine.ifBlank { aEngine },
                                voiceRole        = rawRole.ifBlank { aRole },
                                quizAnswer       = frameObj.optString("quiz_answer", ""),
                                quizOptions      = optsArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                quizCorrectIndex = frameObj.optInt("quiz_correct_index", -1),
                                quizModelAnswer  = frameObj.optString("quiz_model_answer", ""),
                                quizKeywords     = kwArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                fillBlanks       = fillArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                                quizCorrectOrder = orderArr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
                            )
                        }
                        BlackboardStep(
                            title                = stepObj.optString("title", ""),
                            frames               = frames,
                            languageTag          = langTag,
                            image_description    = stepObj.optString("image_description", ""),
                            imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat()
                        )
                    }
                } catch (e: Exception) {
                    errorMsg = "Parse error: ${e.message}"
                }
                latch.countDown()
            }
            .addOnFailureListener { e ->
                errorMsg = e.message ?: "Failed to load lesson"
                latch.countDown()
            }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        if (result != null && result!!.isNotEmpty()) onSuccess(result!!)
        else onError(errorMsg.ifBlank { "Lesson could not be loaded" })
    }
}
