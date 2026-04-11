package com.aiguruapp.student.chat

import android.util.Log
import com.aiguruapp.student.http.HttpClientManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Streams responses from a custom self-hosted server.
 *
 * Expected server contract:
 *   POST <serverUrl>/chat-stream
 *   Request body : {
 *     "question":      "<user message>",
 *     "page_id":       "<subject>__<chapter>",
 *     "mode":          "normal|blackboard|...",
 *     "language":      "en-US|hi-IN|...",
 *     "language_tag":  "en-US|hi-IN|...",
 *     "student_level": <int 1-12>,
 *     "history":       ["user: ...", "assistant: ...", ...],
 *     "image_data":    { "transcript": "...", "paragraphs": [...],
 *                        "diagrams": [...], "key_terms": [...] }  // optional
 *   }
 *   Response SSE : data: {"text": "<token>"}  (repeated)
 *                  data: {"done": true}          (terminal frame)
 *
 * [serverUrl] should be the base URL, e.g. "http://192.168.1.10:8000".
 * [apiKey] is optional — sent as Bearer token when non-empty.
 *
 * All calls are blocking — invoke from Dispatchers.IO.
 */
class ServerProxyClient(
    private val serverUrl: String,
    private val modelName: String,   // kept for API compatibility, not sent to this server
    private val apiKey: String = "",
    private val userId: String = ""
) : AiClient {

    // Use singleton HTTP client from manager (connection pooling + reuse)
    private val client = HttpClientManager.longTimeoutClient

    private val endpoint: String get() {
        val base = serverUrl.trimEnd('/')
        return if (base.endsWith("/chat-stream")) base else "$base/chat-stream"
    }

    override fun streamText(
        systemPrompt: String,
        userText: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        // Fallback: no page_id / history available — wrap system prompt into question
        val combined = if (systemPrompt.isBlank()) userText else "$systemPrompt\n\n$userText"
        streamChat(
            question     = combined,
            pageId       = "",
            mode         = "normal",
            languageTag  = "en-US",
            studentLevel = 5,
            history      = emptyList(),
            onToken      = onToken,
            onDone       = onDone,
            onError      = onError
        )
    }

    /**
     * Primary entry point — builds the full ChatRequest payload.
     *
     * @param question      The student's question (plain text, no system prompt)
     * @param pageId        "subject__chapter" identifier for the server's RAG context
      * @param mode          Routing hint for server-side prompt/control logic.
    * @param languageTag   Preferred response language tag (BCP-47), e.g. en-US, hi-IN.
     * @param studentLevel  Grade level as integer (1–12). Default 5.
     * @param history       Prior turns as ["user: ...", "assistant: ..."] strings
     * @param imageData     Optional structured data extracted from an attached image/PDF page.
     *                      Sent as `image_data` so the server can use full transcript + diagrams.
     * @param imageBase64   Optional raw image bytes (base64) for server-side vision fallback.
     */
    fun streamChat(
        question: String,
        pageId: String,
                mode: String = "normal",
                languageTag: String = "en-US",
        studentLevel: Int = 5,
        history: List<String> = emptyList(),
        imageData: JSONObject? = null,
        imageBase64: String? = null,
        onPageTranscript: ((String) -> Unit)? = null,
        onSuggestBlackboard: ((Boolean) -> Unit)? = null,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val historyArray = JSONArray().apply { history.forEach { put(it) } }
        val json = JSONObject().apply {
            put("question",      question)
            put("page_id",       pageId)
            put("mode",          mode)
            put("language",      languageTag)
            put("language_tag",  languageTag)
            put("student_level", studentLevel)
            put("history",       historyArray)
            if (userId.isNotBlank()) put("user_id", userId)
            if (imageData != null) put("image_data", imageData)
            if (!imageBase64.isNullOrBlank()) put("image_base64", imageBase64)
        }
        Log.d(
            "ServerProxyClient",
            "streamChat → imageData=${if (imageData != null) "present (${imageData.optString("transcript", "").take(40)}…)" else "none"}, imageBase64=${if (imageBase64.isNullOrBlank()) "none" else "present(${imageBase64.length})"}"
        )
        executeStream(json, onPageTranscript, onSuggestBlackboard, onToken, onDone, onError)
    }

    override fun streamWithImage(
        systemPrompt: String,
        userText: String,
        base64Image: String,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        // Image not supported by this server format — fall back to text only
        streamText(systemPrompt, userText, onToken, onDone, onError)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun executeStream(
        json: JSONObject,
        onPageTranscript: ((String) -> Unit)? = null,
        onSuggestBlackboard: ((Boolean) -> Unit)? = null,
        onToken: (String) -> Unit,
        onDone: (inputTokens: Int, outputTokens: Int, totalTokens: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotEmpty()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        Log.d("ServerProxyClient", "→ POST $endpoint")
        try {
            val response = client.newCall(reqBuilder.build()).execute()
            Log.d("ServerProxyClient", "← HTTP ${response.code}")
            if (!response.isSuccessful) {
                onError("HTTP ${response.code}: ${response.message}")
                return
            }
            var inputTokens = 0; var outputTokens = 0; var totalTokens = 0
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    try {
                        val obj = JSONObject(data)
                        val pageTranscript = obj.optString("page_transcript", "")
                        if (pageTranscript.isNotBlank()) onPageTranscript?.invoke(pageTranscript)
                        if (obj.optBoolean("done", false)) {
                            inputTokens  = obj.optInt("inputTokens",  0)
                            outputTokens = obj.optInt("outputTokens", 0)
                            totalTokens  = obj.optInt("totalTokens",  0)
                            val suggestBb = obj.optBoolean("suggest_blackboard", false)
                            if (suggestBb) onSuggestBlackboard?.invoke(true)
                            Log.d("TokenDebug", "[ServerProxy] done frame → in=$inputTokens out=$outputTokens total=$totalTokens suggest_bb=$suggestBb raw=$data")
                            break
                        }
                        val token = obj.optString("text", "")
                        if (token.isNotEmpty()) onToken(token)
                    } catch (_: Exception) { }
                }
            }
            onDone(inputTokens, outputTokens, totalTokens)
            Log.d("TokenDebug", "[ServerProxy] onDone called with in=$inputTokens out=$outputTokens total=$totalTokens")
        } catch (e: IOException) {
            Log.e("ServerProxyClient", "IOException: ${e.message}", e)
            onError(e.message ?: "Network error")
        }
    }
}
