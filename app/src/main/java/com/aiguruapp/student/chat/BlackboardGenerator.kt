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
 *
 * Flow:
 *  1. [callIntent]    — quick LLM call → structured step outline + metadata
 *  2. [generateChunk] — LLM call for N steps using the outline as a guide
 *     BlackboardActivity calls generateChunk() repeatedly as the student
 *     approaches the end of each loaded batch (progressive loading).
 */
object BlackboardGenerator {

    /** Number of steps generated per LLM call. */
    const val CHUNK_SIZE = 5

    // ── Duration enum ─────────────────────────────────────────────────────────

    /**
     * Session length options shown to the user.
     * [totalSteps]  — total steps to generate across all chunks.
     * [framesPerStep] — hint to the LLM for how many frames per step to create.
     */
    enum class BbDuration(
        val label: String,
        val totalSteps: Int,
        val framesPerStep: Int
    ) {
        MIN_2 ("2 min",   5,  3),  // default — compact 5-step lesson
        MIN_4 ("4 min",  10,  4),
        MIN_6 ("6 min",  15,  4),
        MIN_8 ("8 min",  20,  4),
        MIN_10("10 min", 25,  5);

        companion object {
            fun fromLabel(label: String): BbDuration =
                values().find { it.label == label } ?: MIN_2

            /** All option labels for the UI picker. */
            val labels: Array<String> get() = values().map { it.label }.toTypedArray()
        }
    }

    // ── Intent result ─────────────────────────────────────────────────────────

    /**
     * Structured lesson plan returned by [callIntent].
     * Used to guide each subsequent [generateChunk] call for coherence.
     */
    data class BlackboardIntent(
        val lessonTitle: String,
        /** One entry per target step, e.g. "Step 1: What is Photosynthesis?" */
        val stepTitles: List<String>,
        val useSvg: Boolean,
        val category: String,
        /** Real-world curiosity question used as the opening hook frame */
        val hookQuestion: String = "",
        /** Suggested next topic after this lesson ends */
        val continuationTopic: String = ""
    )

    data class YouTubeClip(
        val videoId: String,
        val startSeconds: Int,
        val endSeconds: Int,
        val title: String,
        val startUrl: String = "https://www.youtube.com/watch?v=$videoId&t=${startSeconds}s",
        val clipDurationSeconds: Int = (endSeconds - startSeconds).coerceAtLeast(10),
        val channel: String = ""
    )

    data class BlackboardFrame(
        val text: String,
        val highlight: List<String> = emptyList(),
        val speech: String,
        val durationMs: Long = 2000,
        // concept | memory | diagram | summary
        val frameType: String = "concept",
        // ── Voice Engine & Role ───────────────────────────────────────────────
        // ttsEngine: android | gemini | google
        val ttsEngine: String = "",   // "" = auto-assign via smartAssignTts()
        val voiceRole: String = "",   // "" = auto-assign via smartAssignTts()
        // ── Legacy / unused quiz stubs (kept for saved-session deserialization compat) ──
        val quizAnswer: String = "",
        val quizOptions: List<String> = emptyList(),
        val quizCorrectIndex: Int = -1,
        val quizModelAnswer: String = "",
        val quizKeywords: List<String> = emptyList(),
        val fillBlanks: List<String> = emptyList(),
        val quizCorrectOrder: List<Int> = emptyList(),
        // ── diagram frame: self-contained inline SVG ──────────────────────────
        val svgHtml: String = "",
        // ── YouTube clip (optional — attached server-side for best-match steps) ─
        val youtubeClip: YouTubeClip? = null
    )

    data class BlackboardStep(
        val title: String = "",
        val frames: List<BlackboardFrame>,
        val languageTag: String = "en-US",
        val image_description: String = "",
        val imageConfidenceScore: Float = 0f,  // 0.0 = skip, 0.4–0.69 = tap-only, ≥0.7 = inline
        val followupQuestions: List<FollowupQuestion> = emptyList()  // Only on last step
    )

    /** Inline tap-to-ask questions surfaced after the lesson completes. */
    data class FollowupQuestion(
        val question: String,
        val speech: String = "",        // For Android TTS to read aloud
        val ttsEngine: String = "android",
        val voiceRole: String = "teacher"
    )

    /**
     * Returns (ttsEngine, voiceRole) for a frame that lacks explicit values.
     */
    fun smartAssignTts(frameType: String): Pair<String, String> = when {
        frameType == "concept"              -> Pair("gemini",  "teacher")
        frameType == "memory"               -> Pair("gemini",  "teacher")
        frameType == "diagram"              -> Pair("gemini",  "teacher")
        frameType == "summary"              -> Pair("google",  "assistant")
        frameType.startsWith("quiz")        -> Pair("android", "quiz")
        else                                -> Pair("android", "teacher")
    }

    // ── Intent call ───────────────────────────────────────────────────────────

    /**
     * Quick pre-generation call that returns a structured lesson outline.
     * Used to keep multi-chunk lessons coherent.
     * Must be called from a background thread.
     *
     * @param topic         The student's question / topic.
     * @param totalSteps    Target total steps (controls stepTitles array size).
     * @param preferredLanguageTag BCP-47 tag for language instructions.
     */
    fun callIntent(
        topic: String,
        totalSteps: Int,
        imageBase64: String? = null,
        preferredLanguageTag: String? = null,
        onSuccess: (BlackboardIntent) -> Unit,
        onError: (String) -> Unit
    ) {
        val cfg       = AdminConfigRepository.config
        val serverUrl = AdminConfigRepository.effectiveServerUrl()
        val server    = ServerProxyClient(serverUrl = serverUrl, modelName = "", apiKey = cfg.serverApiKey)

        val intentPrompt = PromptRepository.getBlackboardIntentPrompt()
        val langHint = preferredLanguageTag?.let {
            PromptRepository.getLanguageInstruction(it)
        }.orEmpty()

        val userMsg = "Topic: $topic\nRequested number of steps: $totalSteps$langHint"

        val buffer = StringBuilder()
        val latch  = java.util.concurrent.CountDownLatch(1)
        var err: String? = null

        server.streamChat(
            question    = userMsg,
            pageId      = "blackboard__intent",
            mode        = "blackboard_intent",
            languageTag = preferredLanguageTag ?: "en-US",
            history     = emptyList(),
            imageBase64 = imageBase64,
            onToken     = { token -> buffer.append(token) },
            onDone      = { _, _, _ -> latch.countDown() },
            onError     = { e -> err = e; latch.countDown() }
        )
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)

        if (err != null) { onError(err!!); return }

        val raw = buffer.toString()
        try {
            val start = raw.indexOf('{')
            val end   = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw Exception("No JSON in response")
            val obj = JSONObject(raw.substring(start, end + 1))
            val titlesArr = obj.optJSONArray("steps") ?: JSONArray()
            val titles = (0 until titlesArr.length()).map { titlesArr.getString(it) }
            onSuccess(BlackboardIntent(
                lessonTitle       = obj.optString("lesson_title", topic),
                stepTitles        = titles.ifEmpty { (1..totalSteps).map { "Step $it" } },
                useSvg            = obj.optBoolean("use_svg", false),
                category          = obj.optString("category", "general"),
                hookQuestion      = obj.optString("hook_question", ""),
                continuationTopic = obj.optString("continuation_topic", "")
            ))
        } catch (e: Exception) {
            // Intent parse failure → supply a default outline so generation can still proceed
            onSuccess(BlackboardIntent(
                lessonTitle = topic,
                stepTitles  = (1..totalSteps).map { "Step $it: ${topic.take(40)}" },
                useSvg      = false,
                category    = "general"
            ))
        }
    }

    // ── Chunk generation ──────────────────────────────────────────────────────

    /**
     * Generates a batch of steps via LLM, respecting the [intent] outline for coherence.
     * Must be called from a background thread.
     *
     * @param topic              Original student topic/question.
     * @param intent             Outline returned by [callIntent].
     * @param chunkStepTitles    Step titles for THIS chunk only (subset of intent.stepTitles).
     * @param framesPerStep      Hint: how many frames per step to create.
     * @param previousContext    Summary of what was already taught (prior chunks).
     * @param isLastChunk        When true, the last frame becomes a summary frame.
     * @param preferredLanguageTag BCP-47 language tag.
     */
    fun generateChunk(
        topic: String,
        intent: BlackboardIntent,
        chunkStepTitles: List<String>,
        framesPerStep: Int,
        previousContext: String = "",
        isLastChunk: Boolean = false,
        imageBase64: String? = null,
        preferredLanguageTag: String? = null,
        bbFeatures: Map<String, Boolean> = emptyMap(),
        onStatus: ((String, Int) -> Unit)? = null,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val cfg       = AdminConfigRepository.config
        val serverUrl = AdminConfigRepository.effectiveServerUrl()
        val server    = ServerProxyClient(serverUrl = serverUrl, modelName = "", apiKey = cfg.serverApiKey)

        val systemPrompt = PromptRepository.getBlackboardSystemPrompt()
        val langInstruction = preferredLanguageTag
            ?.let { PromptRepository.getLanguageInstruction(it) }
            .orEmpty()

        val stepList = chunkStepTitles.joinToString("\n") { "- $it" }

        val lastFrameNote = if (isLastChunk)
            "\nThe very last frame of the last step MUST be a summary frame recapping the entire lesson."
        else
            "\nDo NOT include a summary frame — the lesson continues after these steps."

        val svgNote = if (intent.useSvg)
            "\nThis topic benefits from SVG diagrams — use at least one diagram frame per step." else ""

        // For continuation chunks, explicitly forbid repeating what was already taught.
        // For the first chunk, explain the topic from scratch.
        val chunkDirective = if (previousContext.isNotBlank()) {
            """
CONTINUATION — DO NOT repeat anything already taught.
$previousContext
Now continue teaching the NEXT steps listed below. Build on what was already covered — go deeper, add detail, advance the lesson. Do NOT reintroduce or re-explain concepts from the above steps."""
        } else {
            "This is the FIRST chunk of the lesson — begin with an engaging introduction to the topic."
        }

        val userMsg = """Topic: $topic
Lesson title: ${intent.lessonTitle}
Category: ${intent.category}

$chunkDirective

Generate EXACTLY ${chunkStepTitles.size} steps with APPROXIMATELY $framesPerStep frames each.
Steps to generate in this batch:
$stepList
$svgNote$lastFrameNote$langInstruction"""

        val buffer   = StringBuilder()
        var streamErr: String? = null
        val latch    = java.util.concurrent.CountDownLatch(1)

        server.streamChat(
            question     = userMsg,
            pageId       = "blackboard__chunk",
            mode         = "blackboard",
            languageTag  = preferredLanguageTag ?: "en-US",
            history      = emptyList(),
            imageBase64  = imageBase64,
            bbFeatures   = bbFeatures,
            onToken      = { token -> buffer.append(token) },
            onStatus     = onStatus,
            onDone       = { _, _, _ -> latch.countDown() },
            onError      = { e -> streamErr = e; latch.countDown() }
        )
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)

        if (streamErr != null) { onError(streamErr!!); return }

        val response = buffer.toString()
        try {
            val start = response.indexOf('{')
            val end   = response.lastIndexOf('}')
            if (start < 0 || end <= start) { onError("Invalid response format"); return }
            val arr = JSONObject(response.substring(start, end + 1)).getJSONArray("steps")
            val result = parseStepsArray(arr, preferredLanguageTag)
                .filter { step -> step.frames.none { it.frameType.startsWith("quiz") } }
            if (result.isEmpty()) { onError("No steps were generated"); return }
            onSuccess(result)
        } catch (e: Exception) {
            onError("Parse error: ${e.message}")
        }
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
                            val ytObj    = frameObj.optJSONObject("youtube_clip")
                            // Parse engine/role; fall back to smart assignment for old/cached frames
                            val rawEngine = frameObj.optString("tts_engine", "")
                            val rawRole   = frameObj.optString("voice_role",  "")
                            val (assignedEngine, assignedRole) = smartAssignTts(fType)
                            val ytClip = if (ytObj != null) {
                                val vid   = ytObj.optString("video_id", "")
                                val start = ytObj.optInt("start_seconds", 0)
                                val end   = ytObj.optInt("end_seconds", 0)
                                val defUrl = "https://www.youtube.com/watch?v=$vid&t=${start}s"
                                YouTubeClip(
                                    videoId             = vid,
                                    startSeconds        = start,
                                    endSeconds          = end,
                                    title               = ytObj.optString("title", ""),
                                    startUrl            = ytObj.optString("start_url", defUrl).ifBlank { defUrl },
                                    clipDurationSeconds = ytObj.optInt("clip_duration_seconds", (end - start).coerceAtLeast(10)),
                                    channel             = ytObj.optString("channel", "")
                                ).takeIf { it.videoId.isNotBlank() }
                            } else null
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
                                quizCorrectOrder  = if (orderArr != null) (0 until orderArr.length()).map { orderArr.getInt(it) } else emptyList(),
                                svgHtml           = frameObj.optString("svg_html", ""),
                                youtubeClip       = ytClip
                            )
                        }
                        val fqArr = stepObj.optJSONArray("followup_questions")
                        val followups = if (fqArr != null) {
                            (0 until fqArr.length()).mapNotNull { i ->
                                val o = fqArr.optJSONObject(i) ?: return@mapNotNull null
                                val q = o.optString("question", "").trim()
                                if (q.isBlank()) null else FollowupQuestion(
                                    question = q,
                                    speech = o.optString("speech", q),
                                    ttsEngine = o.optString("tts_engine", "android"),
                                    voiceRole = o.optString("voice_role", "teacher")
                                )
                            }
                        } else emptyList()
                        BlackboardStep(
                            title                = stepObj.optString("title", ""),
                            frames               = frames,
                            languageTag          = langTag,
                            image_description    = stepObj.optString("image_description", ""),
                            imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat(),
                            followupQuestions    = followups
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

        // ── 2. Generate via LLM using a default intent outline ─────────────────
        // generate() is used for cache-aware single-topic generation (teacher tasks, etc.).
        // For the normal student flow, BlackboardActivity calls callIntent() + generateChunk().
        val defaultIntent = BlackboardIntent(
            lessonTitle = messageContent.take(60),
            stepTitles  = emptyList(),
            useSvg      = false,
            category    = "general"
        )
        generateChunk(
            topic            = messageContent,
            intent           = defaultIntent,
            chunkStepTitles  = emptyList(),   // prompt will use topic directly
            framesPerStep    = 4,
            isLastChunk      = true,
            preferredLanguageTag = preferredLanguageTag,
            onStatus         = onStatus,
            onSuccess        = { result ->
                // ── 3. Save result to Firestore cache ─────────────────────────
                try {
                    val stepsJson = serializeSteps(result)
                    cacheDocRef?.set(mapOf("steps" to stepsJson, "createdAt" to System.currentTimeMillis()))
                } catch (_: Exception) { /* cache write failure is non-fatal */ }
                onSuccess(result)
            },
            onError = onError
        )
    }

    // ── Shared parse helper ───────────────────────────────────────────────────

    /**
     * Parses a [JSONArray] of step objects into a list of [BlackboardStep].
     * Quiz frames are NOT filtered here — callers decide whether to filter.
     */
    fun parseStepsArray(arr: JSONArray, preferredLanguageTag: String?): List<BlackboardStep> {
        return (0 until arr.length()).map { i ->
            val stepObj   = arr.getJSONObject(i)
            val langTag   = normalizeLanguageTag(
                raw      = stepObj.optString("lang", stepObj.optString("language", "")),
                fallback = preferredLanguageTag ?: "en-US"
            )
            val framesArr = stepObj.getJSONArray("frames")
            val frames    = (0 until framesArr.length()).map { j ->
                val frameObj = framesArr.getJSONObject(j)
                val hlArr    = frameObj.optJSONArray("highlight")
                val optsArr  = frameObj.optJSONArray("quiz_options")
                val kwArr    = frameObj.optJSONArray("quiz_keywords")
                val fillArr  = frameObj.optJSONArray("fill_blanks")
                val orderArr = frameObj.optJSONArray("quiz_correct_order")
                val fType    = frameObj.optString("frame_type", "concept")
                val rawEng   = frameObj.optString("tts_engine", "")
                val rawRole  = frameObj.optString("voice_role",  "")
                val (aEng, aRole) = smartAssignTts(fType)
                val ytObj = frameObj.optJSONObject("youtube_clip")
                val ytClip = if (ytObj != null) {
                    val vid   = ytObj.optString("video_id", "")
                    val start = ytObj.optInt("start_seconds", 0)
                    val end   = ytObj.optInt("end_seconds", 0)
                    val defUrl = "https://www.youtube.com/watch?v=$vid&t=${start}s"
                    YouTubeClip(
                        videoId             = vid,
                        startSeconds        = start,
                        endSeconds          = end,
                        title               = ytObj.optString("title", ""),
                        startUrl            = ytObj.optString("start_url", defUrl).ifBlank { defUrl },
                        clipDurationSeconds = ytObj.optInt("clip_duration_seconds", (end - start).coerceAtLeast(10)),
                        channel             = ytObj.optString("channel", "")
                    ).takeIf { it.videoId.isNotBlank() }
                } else null
                BlackboardFrame(
                    text             = frameObj.optString("text", ""),
                    highlight        = hlArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    speech           = frameObj.optString("speech", ""),
                    durationMs       = frameObj.optLong("duration_ms", 2000),
                    frameType        = fType,
                    ttsEngine        = rawEng.ifBlank { aEng },
                    voiceRole        = rawRole.ifBlank { aRole },
                    quizAnswer       = frameObj.optString("quiz_answer", ""),
                    quizOptions      = optsArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    quizCorrectIndex = frameObj.optInt("quiz_correct_index", -1),
                    quizModelAnswer  = frameObj.optString("quiz_model_answer", ""),
                    quizKeywords     = kwArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    fillBlanks       = fillArr?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    quizCorrectOrder = orderArr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                    svgHtml          = frameObj.optString("svg_html", ""),
                    youtubeClip      = ytClip
                )
            }
            // Parse follow-up questions (only present on last step typically)
            val fqArr = stepObj.optJSONArray("followup_questions")
            val followups = if (fqArr != null) {
                (0 until fqArr.length()).mapNotNull { i ->
                    val o = fqArr.optJSONObject(i) ?: return@mapNotNull null
                    val q = o.optString("question", "").trim()
                    if (q.isBlank()) null else FollowupQuestion(
                        question = q,
                        speech = o.optString("speech", q),
                        ttsEngine = o.optString("tts_engine", "android"),
                        voiceRole = o.optString("voice_role", "teacher")
                    )
                }
            } else emptyList()

            BlackboardStep(
                title                = stepObj.optString("title", ""),
                frames               = frames,
                languageTag          = langTag,
                image_description    = stepObj.optString("image_description", ""),
                imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat(),
                followupQuestions    = followups
            )
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
                val frameJson = JSONObject()
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
                    .put("svg_html", frame.svgHtml)
                frame.youtubeClip?.let { clip ->
                    frameJson.put(
                        "youtube_clip",
                        JSONObject()
                            .put("video_id", clip.videoId)
                            .put("start_seconds", clip.startSeconds)
                            .put("end_seconds", clip.endSeconds)
                            .put("title", clip.title)
                            .put("start_url", clip.startUrl)
                            .put("clip_duration_seconds", clip.clipDurationSeconds)
                            .put("channel", clip.channel)
                    )
                }
                framesJson.put(frameJson)
            }
            val followupsJson = JSONArray()
            step.followupQuestions.forEach { q ->
                followupsJson.put(
                    JSONObject()
                        .put("question", q.question)
                        .put("speech", q.speech)
                        .put("tts_engine", q.ttsEngine)
                        .put("voice_role", q.voiceRole)
                )
            }
            arr.put(
                JSONObject()
                    .put("title", step.title)
                    .put("frames", framesJson)
                    .put("lang", step.languageTag)
                    .put("image_description", step.image_description)
                    .put("image_show_confidencescore", step.imageConfidenceScore.toDouble())
                    .put("followup_questions", followupsJson)
            )
        }
        return arr.toString()
    }

    /**
     * Parse a JSON string produced by [serializeSteps] back into a list of [BlackboardStep].
     * Returns an empty list if the JSON is blank or malformed.
     */
    fun deserializeSteps(json: String, langFallback: String = "en-US"): List<BlackboardStep> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val stepObj = arr.getJSONObject(i)
                val langTag = normalizeLanguageTag(
                    raw      = stepObj.optString("lang", stepObj.optString("language", "")),
                    fallback = langFallback
                )
                val framesArr = stepObj.getJSONArray("frames")
                val frames = (0 until framesArr.length()).map { j ->
                    val f        = framesArr.getJSONObject(j)
                    val hlArr    = f.optJSONArray("highlight")
                    val optsArr  = f.optJSONArray("quiz_options")
                    val kwArr    = f.optJSONArray("quiz_keywords")
                    val fillArr  = f.optJSONArray("fill_blanks")
                    val orderArr = f.optJSONArray("quiz_correct_order")
                    val fType    = f.optString("frame_type", "concept")
                    val ytObj    = f.optJSONObject("youtube_clip")
                    val rawEngine = f.optString("tts_engine", "")
                    val rawRole   = f.optString("voice_role",  "")
                    val (aEngine, aRole) = smartAssignTts(fType)
                    val ytClip = if (ytObj != null) {
                        val vid   = ytObj.optString("video_id", "")
                        val start = ytObj.optInt("start_seconds", 0)
                        val end   = ytObj.optInt("end_seconds", 0)
                        val defUrl = "https://www.youtube.com/watch?v=$vid&t=${start}s"
                        YouTubeClip(
                            videoId             = vid,
                            startSeconds        = start,
                            endSeconds          = end,
                            title               = ytObj.optString("title", ""),
                            startUrl            = ytObj.optString("start_url", defUrl).ifBlank { defUrl },
                            clipDurationSeconds = ytObj.optInt("clip_duration_seconds", (end - start).coerceAtLeast(10)),
                            channel             = ytObj.optString("channel", "")
                        ).takeIf { it.videoId.isNotBlank() }
                    } else null
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
                        quizCorrectOrder = orderArr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                        svgHtml          = f.optString("svg_html", ""),
                        youtubeClip      = ytClip
                    )
                }
                val followups = stepObj.optJSONArray("followup_questions")?.let { arrQ ->
                    (0 until arrQ.length()).mapNotNull { idx ->
                        val qObj = arrQ.optJSONObject(idx) ?: return@mapNotNull null
                        val question = qObj.optString("question", "").trim()
                        if (question.isBlank()) null else FollowupQuestion(
                            question = question,
                            speech = qObj.optString("speech", question),
                            ttsEngine = qObj.optString("tts_engine", "android"),
                            voiceRole = qObj.optString("voice_role", "teacher")
                        )
                    }
                } ?: emptyList()
                BlackboardStep(
                    title                = stepObj.optString("title", ""),
                    frames               = frames,
                    languageTag          = langTag,
                    image_description    = stepObj.optString("image_description", ""),
                    imageConfidenceScore = stepObj.optDouble("image_show_confidencescore", 0.0).toFloat(),
                    followupQuestions    = followups
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
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
                result = deserializeSteps(stepsJson, preferredLanguageTag ?: "en-US")
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
                    val langFallback = preferredLanguageTag
                        ?: doc.getString("language_tag")
                        ?: "en-US"
                    result = deserializeSteps(stepsJson, langFallback)
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

    /**
     * Load a previously saved BB lesson directly from the user's
     * [users/{userId}/saved_bb_sessions_flat/{sessionId}] document.
     *
     * The document must contain a [steps_json] field (written by [saveBbSession]).
     * No LLM call is made — this is a pure Firestore fetch.
     * Must be called from a background thread.
     */
    fun loadFromSavedSession(
        userId: String,
        sessionId: String,
        preferredLanguageTag: String? = null,
        onSuccess: (List<BlackboardStep>) -> Unit,
        onError: (String) -> Unit
    ) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<BlackboardStep>? = null
        var errorMsg = ""

        FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("saved_bb_sessions_flat").document(sessionId)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    errorMsg = "Saved session not found ($sessionId)"
                    latch.countDown()
                    return@addOnSuccessListener
                }
                val stepsJson = doc.getString("steps_json") ?: ""
                if (stepsJson.isBlank()) {
                    errorMsg = "Session has no steps data (old save — please re-save)"
                    latch.countDown()
                    return@addOnSuccessListener
                }
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
                            val frameObj  = framesArr.getJSONObject(j)
                            val hlArr     = frameObj.optJSONArray("highlight")
                            val optsArr   = frameObj.optJSONArray("quiz_options")
                            val kwArr     = frameObj.optJSONArray("quiz_keywords")
                            val fillArr   = frameObj.optJSONArray("fill_blanks")
                            val orderArr  = frameObj.optJSONArray("quiz_correct_order")
                            val fType     = frameObj.optString("frame_type", "concept")
                            val rawEngine = frameObj.optString("tts_engine", "")
                            val rawRole   = frameObj.optString("voice_role",  "")
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
                                quizCorrectOrder = orderArr?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                                svgHtml          = frameObj.optString("svg_html", "")
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
                errorMsg = e.message ?: "Failed to load saved session"
                latch.countDown()
            }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        if (result != null && result!!.isNotEmpty()) onSuccess(result!!)
        else onError(errorMsg.ifBlank { "Saved session could not be loaded" })
    }
}
