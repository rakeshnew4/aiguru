package com.aiguruapp.student.tts

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * AI Text-to-Speech provider functions.
 *
 * Each function calls a specific TTS service and returns raw MP3 bytes
 * via [onSuccess], or an error message via [onError].
 *
 * These are blocking calls — run them from a background thread (e.g. Dispatchers.IO).
 *
 * ┌────────────────────────────────────────────────────────────┐
 * │  TO ADD A NEW PROVIDER: copy any function below,          │
 * │  update the endpoint / request body, and register it      │
 * │  in BbAiTtsEngine.Provider + generateAndCache().          │
 * └────────────────────────────────────────────────────────────┘
 */
object AiTtsProvider {

    private const val TAG = "AiTtsProvider"
    private const val TIMEOUT_MS = 15_000

    // ── Google Cloud TTS ───────────────────────────────────────────────────────
    // Docs  : https://cloud.google.com/text-to-speech/docs/reference/rest
    // Voices: https://cloud.google.com/text-to-speech/docs/voices
    //
    // Setup:
    //   1. Enable Cloud TTS API in Google Cloud Console
    //   2. Create an API key restricted to Cloud TTS API
    //   3. Pass the key as [apiKey]
    //
    // Free tier: 1 million chars / month (WaveNet: 1M chars free, then $16/1M)
    fun googleTts(
        text: String,
        languageCode: String = "en-US",    // e.g. "hi-IN", "en-IN", "ta-IN"
        voiceName: String    = "",          // "" = auto-select best Neural2 voice for lang
        speakingRate: Double = 1.0,         // 0.25 – 4.0
        apiKey: String       = "",
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (apiKey.isBlank()) { onError("Google TTS API key not configured"); return }
        try {
            val voice = buildGoogleVoice(languageCode, voiceName)
            val body = JSONObject().apply {
                put("input",       JSONObject().put("text", text))
                put("voice",       voice)
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate",  speakingRate)
                })
            }.toString()

            val url  = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
            }
            val code = conn.responseCode
            if (code == 200) {
                val json  = conn.inputStream.bufferedReader().readText()
                val b64   = JSONObject(json).getString("audioContent")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                Log.d(TAG, "googleTts OK: ${bytes.size} bytes for lang=$languageCode")
                onSuccess(bytes)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.w(TAG, "googleTts error: $err")
                onError("Google TTS HTTP $code: $err")
            }
            conn.disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "googleTts IO error: ${e.message}")
            onError("Google TTS network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "googleTts error: ${e.message}")
            onError("Google TTS error: ${e.message}")
        }
    }

    private fun buildGoogleVoice(languageCode: String, voiceName: String): JSONObject =
        JSONObject().apply {
            put("languageCode", languageCode)
            // If no specific voice name given, pick the best Neural2 voice for the language
            val name = voiceName.ifBlank {
                when {
                    languageCode.startsWith("hi") -> "hi-IN-Neural2-A"
                    languageCode.startsWith("en") -> "en-IN-Neural2-A"
                    languageCode.startsWith("ta") -> "ta-IN-Neural2-A"
                    languageCode.startsWith("te") -> "te-IN-Standard-A"
                    languageCode.startsWith("kn") -> "kn-IN-Wavenet-A"
                    languageCode.startsWith("ml") -> "ml-IN-Wavenet-A"
                    languageCode.startsWith("mr") -> "mr-IN-Wavenet-A"
                    else -> ""
                }
            }
            if (name.isNotBlank()) put("name", name)
            put("ssmlGender", "NEUTRAL")
        }

    // ── ElevenLabs TTS ─────────────────────────────────────────────────────────
    // Docs  : https://docs.elevenlabs.io/api-reference/text-to-speech
    // Voices: https://api.elevenlabs.io/v1/voices  (GET, no auth needed for list)
    //
    // Free tier: 10 000 chars / month  |  Starter: 30 000 chars / month ($5)
    fun elevenLabsTts(
        text: String,
        voiceId: String = "21m00Tcm4TlvDq8ikWAM",  // "Rachel" — good Indian-English voice
        modelId: String = "eleven_multilingual_v2",
        stability: Double          = 0.5,
        similarityBoost: Double    = 0.75,
        apiKey: String             = "",
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (apiKey.isBlank()) { onError("ElevenLabs API key not configured"); return }
        try {
            val body = JSONObject().apply {
                put("text",     text)
                put("model_id", modelId)
                put("voice_settings", JSONObject().apply {
                    put("stability",        stability)
                    put("similarity_boost", similarityBoost)
                })
            }.toString()

            val url  = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("xi-api-key",    apiKey)
                setRequestProperty("Content-Type",   "application/json")
                setRequestProperty("Accept",         "audio/mpeg")
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
            }
            val code = conn.responseCode
            if (code == 200) {
                val bytes = conn.inputStream.readBytes()
                Log.d(TAG, "elevenLabsTts OK: ${bytes.size} bytes")
                onSuccess(bytes)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.w(TAG, "elevenLabsTts error: $err")
                onError("ElevenLabs HTTP $code: $err")
            }
            conn.disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "elevenLabsTts IO error: ${e.message}")
            onError("ElevenLabs network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "elevenLabsTts error: ${e.message}")
            onError("ElevenLabs error: ${e.message}")
        }
    }

    // ── OpenAI TTS ─────────────────────────────────────────────────────────────
    // Docs : https://platform.openai.com/docs/api-reference/audio/createSpeech
    // Voices: alloy | echo | fable | onyx | nova | shimmer
    //
    // Pricing: tts-1 $15/1M chars  |  tts-1-hd $30/1M chars
    fun openAiTts(
        text: String,
        voice: String  = "nova",    // nova sounds great for educational content
        model: String  = "tts-1",   // tts-1 (fast, low latency) | tts-1-hd (higher quality)
        speed: Double  = 1.0,        // 0.25 – 4.0
        apiKey: String = "",
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (apiKey.isBlank()) { onError("OpenAI TTS API key not configured"); return }
        try {
            val body = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("speed", speed)
                put("response_format", "mp3")
            }.toString()

            val url  = URL("https://api.openai.com/v1/audio/speech")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
            }
            val code = conn.responseCode
            if (code == 200) {
                val bytes = conn.inputStream.readBytes()
                Log.d(TAG, "openAiTts OK: ${bytes.size} bytes, voice=$voice")
                onSuccess(bytes)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.w(TAG, "openAiTts error: $err")
                onError("OpenAI TTS HTTP $code: $err")
            }
            conn.disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "openAiTts IO error: ${e.message}")
            onError("OpenAI TTS network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "openAiTts error: ${e.message}")
            onError("OpenAI TTS error: ${e.message}")
        }
    }

    // ── Self-hosted TTS (GPU server / Coqui / F5-TTS / StyleTTS2) ─────────────
    // POST <serverUrl>/generate-tts
    // Body:   { "text": "...", "lang": "en-US" }
    // Response: { "audio_bytes_b64": "<base64 MP3>" }   OR  raw MP3 bytes
    //
    // Run the FastAPI server in server/app/main.py and add a /generate-tts route.
    fun selfHostedTts(
        text: String,
        languageCode: String = "en-US",
        serverUrl: String    = "",          // e.g. "https://your-server.com" or "http://10.0.2.2:5050"
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (serverUrl.isBlank()) { onError("Self-hosted TTS server URL not configured"); return }
        try {
            val body = JSONObject().apply {
                put("text", text)
                put("lang", languageCode)
            }.toString()

            val url  = URL("$serverUrl/generate-tts")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
            }
            val code = conn.responseCode
            if (code == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                // Try JSON response with audio_bytes_b64 field first
                try {
                    val json  = JSONObject(responseText)
                    val b64   = json.optString("audio_bytes_b64")
                    if (b64.isNotBlank()) {
                        onSuccess(Base64.decode(b64, Base64.DEFAULT))
                        conn.disconnect(); return
                    }
                } catch (_: Exception) { /* not JSON — treat as raw bytes */ }
                // Fallback: raw body was the MP3 bytes (re-fetch as bytes)
                conn.disconnect()
                val urlStream = URL("$serverUrl/generate-tts").openConnection() as HttpURLConnection
                urlStream.requestMethod = "POST"
                urlStream.connectTimeout = TIMEOUT_MS
                urlStream.readTimeout    = TIMEOUT_MS
                urlStream.setRequestProperty("Content-Type", "application/json")
                urlStream.doOutput = true
                urlStream.outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
                if (urlStream.responseCode == 200) {
                    onSuccess(urlStream.inputStream.readBytes())
                } else {
                    onError("Self-hosted TTS HTTP ${urlStream.responseCode}")
                }
                urlStream.disconnect()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.w(TAG, "selfHostedTts error: $err")
                onError("Self-hosted TTS HTTP $code: $err")
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.e(TAG, "selfHostedTts IO error: ${e.message}")
            onError("Self-hosted TTS network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "selfHostedTts error: ${e.message}")
            onError("Self-hosted TTS error: ${e.message}")
        }
    }

    // ── Server TTS (aiguru FastAPI server — keys never leave server) ───────────
    // POST <serverUrl>/api/tts/synthesize
    // Body:     { "text": "...", "language_code": "hi-IN" }
    // Headers:  Authorization: Bearer <firebase-id-token>
    // Response: raw MP3 bytes (audio/mpeg)
    //
    // The server tries Google → ElevenLabs → OpenAI in order.
    // Returns 503 if all providers fail; app falls back to Android TTS.
    fun serverTts(
        text: String,
        languageCode: String = "en-US",
        serverUrl: String    = "",      // e.g. "http://108.181.187.227:8003"
        authToken: String    = "",      // Firebase ID token (without "Bearer " prefix)
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (serverUrl.isBlank()) { onError("Server URL not configured"); return }
        if (authToken.isBlank()) { onError("Firebase auth token not available"); return }
        try {
            val body = JSONObject().apply {
                put("text", text)
                put("language_code", languageCode)
            }.toString()

            val url  = URL("$serverUrl/api/tts/synthesize")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $authToken")
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charset.forName("UTF-8"))) }
            }
            val code = conn.responseCode
            if (code == 200) {
                val bytes = conn.inputStream.readBytes()
                Log.d(TAG, "serverTts OK: ${bytes.size} bytes lang=$languageCode")
                onSuccess(bytes)
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                Log.w(TAG, "serverTts error: $err")
                onError("Server TTS HTTP $code: $err")
            }
            conn.disconnect()
        } catch (e: IOException) {
            Log.e(TAG, "serverTts IO error: ${e.message}")
            onError("Server TTS network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "serverTts error: ${e.message}")
            onError("Server TTS error: ${e.message}")
        }
    }
}
