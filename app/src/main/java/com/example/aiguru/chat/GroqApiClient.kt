package com.example.aiguru.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Thin wrapper around the Groq HTTP API.
 * All methods are blocking — call from a background coroutine (Dispatchers.IO).
 */
class GroqApiClient(private val apiKey: String) {

    private val client  = OkHttpClient()
    private val apiUrl  = "https://api.groq.com/openai/v1/chat/completions"
    private val modelText   = "llama-3.3-70b-versatile"
    private val modelVision = "meta-llama/llama-4-scout-17b-16e-instruct"

    /** Text-only call. [systemPrompt] should already include any language instruction. */
    fun callText(systemPrompt: String, userText: String): String? {
        val json = JSONObject().apply {
            put("model", modelText)
            put("messages", JSONArray().apply {
                put(buildMsg("system", systemPrompt))
                put(buildMsg("user",   userText))
            })
            put("temperature", 0.7)
            put("max_tokens",  2048)
        }
        return execute(json)
    }

    /**
     * Vision call — embeds [base64Image] alongside a text message.
     * [userText] should already include any subject/chapter context prefix.
     */
    fun callWithImage(systemPrompt: String, userText: String, base64Image: String): String? {
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userText)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
            })
        }
        val json = JSONObject().apply {
            put("model", modelVision)
            put("messages", JSONArray().apply {
                put(buildMsg("system", systemPrompt))
                put(JSONObject().apply {
                    put("role",    "user")
                    put("content", contentArray)
                })
            })
            put("temperature", 0.7)
            put("max_tokens",  2048)
        }
        return execute(json)
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

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
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (_: IOException) {
            null
        }
    }

    private fun buildMsg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)
}
