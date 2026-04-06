package com.example.aiguru.chat

import android.util.Log
import com.example.aiguru.config.AdminConfigRepository
import com.example.aiguru.models.PageContent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Analyzes an educational image/page by calling the backend `/analyze-image`
 * endpoint (Gemini vision).  Previously called Groq directly from Android —
 * now the API key lives solely on the server.
 *
 * All calls are blocking — invoke from Dispatchers.IO.
 */
object PageAnalyzer {

    private const val TAG = "PageAnalyzer"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    fun analyze(
        base64Image: String,
        subject: String,
        chapter: String,
        pageNumber: Int = 0,
        sourceType: String = "image",
        onSuccess: (PageContent) -> Unit,
        onError: (String) -> Unit
    ) {
        val cfg = AdminConfigRepository.config
        val serverUrl = cfg.serverUrl.ifBlank { "http://108.181.187.227:8003" }.trimEnd('/')
        val endpoint = "$serverUrl/analyze-image"

        val body = JSONObject().apply {
            put("image_base64", base64Image)
            put("subject", subject)
            put("chapter", chapter)
            put("page_number", pageNumber)
            put("source_type", sourceType)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder().url(endpoint).post(body)
        if (cfg.serverApiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${cfg.serverApiKey}")
        }

        try {
            val response = http.newCall(reqBuilder.build()).execute()
            val raw = response.body?.string() ?: throw Exception("Empty response from server")
            if (!response.isSuccessful) {
                onError("analyze-image HTTP ${response.code}: $raw")
                return
            }
            val obj = JSONObject(raw)
            val pageId = generatePageId(subject, chapter)
            val content = PageContent(
                pageId         = pageId,
                subject        = subject,
                chapter        = chapter,
                pageNumber     = pageNumber,
                sourceType     = sourceType,
                transcript     = obj.optString("transcript", ""),
                paragraphsJson = obj.optString("paragraphs_json", "[]"),
                diagramsJson   = obj.optString("diagrams_json", "[]"),
                keyTerms       = parseStringList(obj.optJSONArray("key_terms")),
                analyzedAt     = System.currentTimeMillis()
            )
            Log.d(TAG, "Server analysis complete [$pageId]: ${content.transcript.length} chars, " +
                    "${content.parsedParagraphs().size} paragraphs, " +
                    "${content.parsedDiagrams().size} diagrams, " +
                    "${content.keyTerms.size} key terms" +
                    if (obj.optBoolean("cached")) " (cached)" else "")
            onSuccess(content)
        } catch (e: Exception) {
            Log.e(TAG, "Server image analysis failed: ${e.message}")
            onError(e.message ?: "Unknown error")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Generate a unique, stable page ID for a given subject+chapter pair. */
    fun generatePageId(subject: String, chapter: String): String {
        val s = subject.trim().replace(Regex("[/\\\\#%?*\\[\\]]"), "_").take(40)
        val c = chapter.trim().replace(Regex("[/\\\\#%?*\\[\\]]"), "_").take(40)
        return "${s}__${c}__p${System.currentTimeMillis()}"
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { arr.getString(it) }.getOrNull() }
    }
}
//object PageAnalyzer {
//
//    private const val TAG = "PageAnalyzer"
//
//    private const val SYSTEM_PROMPT = """You are an expert educational content extractor.
//Analyze this image of a textbook or classroom material.
//Return ONLY a valid JSON object — no markdown fences, no explanation."""
//
//    private const val USER_PROMPT = """Analyze this educational page image in detail. Return a JSON object with EXACTLY this structure:
//
//{
//  "transcript": "Full verbatim text extracted from the page — every word, equation, caption",
//  "paragraphs": [
//    {
//      "number": 1,
//      "text": "Exact text of this paragraph",
//      "summary": "One-sentence summary of what this paragraph explains"
//    }
//  ],
//  "diagrams": [
//    {
//      "heading": "Figure label/title visible in the image, e.g. 'Figure 2.1: Mitosis'",
//      "context": "Which topic or concept this diagram relates to",
//      "description": "All visible elements — labels, arrows, components, colours, numbers",
//      "depiction": "What the diagram is illustrating, proving, or teaching the student",
//      "position": "Where on the page — e.g. top-right, center, bottom-left",
//      "labelled_parts": ["Label A", "Label B", "Label C"]
//    }
//  ],
//  "key_terms": ["term1", "term2", "term3"]
//}
//
//Rules:
//- Include ALL text, ALL diagrams/figures/tables visible in the image.
//- If there are no diagrams, set "diagrams" to [].
//- Return ONLY the JSON — no extra text before or after."""
//
//    // ── Public API ────────────────────────────────────────────────────────────
//
//    /**
//     * Analyze [base64Image] and return a [PageContent] via [onSuccess].
//     * Falls back to storing raw text on JSON parse failure.
//     *
//     * @param base64Image  JPEG/PNG base64 string (no data-URI prefix)
//     * @param subject      Subject name for context
//     * @param chapter      Chapter name for context
//     * @param pageNumber   PDF page number (0 = unknown / ad-hoc image)
//     * @param sourceType   "image" | "camera" | "pdf"
//     * @param onSuccess    Called on IO thread with the parsed [PageContent]
//     * @param onError      Called on IO thread with a human-readable error message
//     */
//    fun analyze(
//        base64Image: String,
//        subject: String,
//        chapter: String,
//        pageNumber: Int = 0,
//        sourceType: String = "image",
//        onSuccess: (PageContent) -> Unit,
//        onError: (String) -> Unit
//    ) {
//        val groqKey = BuildConfig.GROQ_API_KEY
//        if (groqKey.isBlank() || groqKey == "null") {
//            onError("Groq API key not configured — page analysis unavailable")
//            return
//        }
//
//        val client       = GroqApiClient(apiKey = groqKey)
//        val systemPrompt = "$SYSTEM_PROMPT\nSubject: $subject. Chapter: $chapter."
//        val response     = StringBuilder()
//
//        client.streamWithImage(
//            systemPrompt = systemPrompt,
//            userText     = USER_PROMPT,
//            base64Image  = base64Image,
//            onToken      = { response.append(it) },
//            onDone       = { _, _, _ ->
//                val pageId  = generatePageId(subject, chapter)
//                val content = parseResponse(response.toString(), pageId, subject, chapter, pageNumber, sourceType)
//                Log.d(TAG, "Analysis complete [$pageId]: " +
//                        "${content.transcript.length} chars, " +
//                        "${content.parsedParagraphs().size} paragraphs, " +
//                        "${content.parsedDiagrams().size} diagrams, " +
//                        "${content.keyTerms.size} key terms")
//                onSuccess(content)
//            },
//            onError = { err ->
//                Log.e(TAG, "Vision analysis failed: $err")
//                onError(err)
//            }
//        )
//    }
//
//    // ── Parsing ───────────────────────────────────────────────────────────────
//
//    private fun parseResponse(
//        raw: String,
//        pageId: String,
//        subject: String,
//        chapter: String,
//        pageNumber: Int,
//        sourceType: String
//    ): PageContent {
//        val jsonStr = extractJsonObject(raw)
//        return try {
//            val obj             = JSONObject(jsonStr)
//            val transcript      = obj.optString("transcript", "")
//            val paragraphsJson  = obj.optJSONArray("paragraphs")?.toString()  ?: "[]"
//            val diagramsJson    = obj.optJSONArray("diagrams")?.toString()    ?: "[]"
//            val keyTerms        = parseStringList(obj.optJSONArray("key_terms"))
//
//            PageContent(
//                pageId         = pageId,
//                subject        = subject,
//                chapter        = chapter,
//                pageNumber     = pageNumber,
//                sourceType     = sourceType,
//                transcript     = transcript,
//                paragraphsJson = paragraphsJson,
//                diagramsJson   = diagramsJson,
//                keyTerms       = keyTerms,
//                analyzedAt     = System.currentTimeMillis()
//            )
//        } catch (e: Exception) {
//            Log.w(TAG, "JSON parse failed, storing raw response as transcript: ${e.message}")
//            PageContent(
//                pageId     = pageId,
//                subject    = subject,
//                chapter    = chapter,
//                pageNumber = pageNumber,
//                sourceType = sourceType,
//                transcript = raw.take(4000),
//                analyzedAt = System.currentTimeMillis()
//            )
//        }
//    }
//
//    // ── Helpers ───────────────────────────────────────────────────────────────
//
//    /** Generate a unique, stable page ID for a given subject+chapter pair. */
//    fun generatePageId(subject: String, chapter: String): String {
//        val s = subject.trim().replace(Regex("[/\\\\#%?*\\[\\]]"), "_").take(40)
//        val c = chapter.trim().replace(Regex("[/\\\\#%?*\\[\\]]"), "_").take(40)
//        return "${s}__${c}__p${System.currentTimeMillis()}"
//    }
//
//    /**
//     * Strip markdown code fences and find the outermost `{ ... }` block.
//     * Handles responses like:
//     *   ```json\n{...}\n```
//     *   Here is the analysis: {...}
//     */
//    private fun extractJsonObject(text: String): String {
//        var s = text.trim()
//        // Strip ```json ... ``` fences
//        if (s.startsWith("```")) {
//            s = s.removePrefix("```json").removePrefix("```").trimStart()
//            val endFence = s.lastIndexOf("```")
//            if (endFence > 0) s = s.substring(0, endFence).trimEnd()
//        }
//        val start = s.indexOf('{')
//        val end   = s.lastIndexOf('}')
//        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
//    }
//
//    private fun parseStringList(arr: JSONArray?): List<String> {
//        if (arr == null) return emptyList()
//        return (0 until arr.length()).mapNotNull { runCatching { arr.getString(it) }.getOrNull() }
//    }
//}
