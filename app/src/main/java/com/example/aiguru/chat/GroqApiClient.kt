package com.example.aiguru.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Groq HTTP API client.
 * Implements [AiClient] — all calls are blocking, use from Dispatchers.IO.
 */
class GroqApiClient(
    private val apiKey: String,
    private val textModel: String   = "llama-3.3-70b-versatile",
    private val visionModel: String = "meta-llama/llama-4-scout-17b-16e-instruct"
) : AiClient {

    private val client = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    private val apiUrl = "https://api.groq.com/openai/v1/chat/completions"

    // ── AiClient (streaming) ──────────────────────────────────────────────────

    override fun streamText(
        systemPrompt: String,
        userText: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val json = JSONObject().apply {
            put("model", textModel)
            put("messages", JSONArray().apply {
                put(buildMsg("system", systemPrompt))
                put(buildMsg("user",   userText))
            })
            put("temperature", 0.7)
            put("max_tokens",  2048)
            put("stream",      true)
        }
        executeStream(json, onToken, onDone, onError)
    }

    override fun streamWithImage(
        systemPrompt: String,
        userText: String,
        base64Image: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply { put("type", "text"); put("text", userText) })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        }
        val json = JSONObject().apply {
            put("model", visionModel)
            put("messages", JSONArray().apply {
                put(buildMsg("system", systemPrompt))
                put(JSONObject().apply {
                    put("role",    "user")
                    put("content", contentArray)
                })
            })
            put("temperature", 0.7)
            put("max_tokens",  2048)
            put("stream",      true)
        }
        executeStream(json, onToken, onDone, onError)
    }

    // ── Legacy non-streaming (kept for any callers that still need full text) ─

    fun callText(systemPrompt: String, userText: String): String? {
        val json = JSONObject().apply {
            put("model", textModel)
            put("messages", JSONArray().apply {
                put(buildMsg("system", systemPrompt))
                put(buildMsg("user",   userText))
            })
            put("temperature", 0.7)
            put("max_tokens",  2048)
        }
        return execute(json)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun executeStream(
        json: JSONObject,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                onError("HTTP ${response.code}: ${response.message}")
                return
            }
            response.body?.source()?.let { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val delta = JSONObject(data)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("delta")
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) onToken(content)
                    } catch (_: Exception) { }
                }
            }
            onDone()
        } catch (e: IOException) {
            onError(e.message ?: "Network error")
        }
    }

    private fun execute(json: JSONObject): String? {
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            JSONObject(body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (_: IOException) {
            null
        }
    }

    private fun buildMsg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)
}
