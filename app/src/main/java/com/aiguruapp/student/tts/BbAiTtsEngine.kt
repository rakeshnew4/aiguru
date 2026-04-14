package com.aiguruapp.student.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.utils.TTSCallback
import com.aiguruapp.student.utils.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * AI TTS buffer engine for Blackboard.
 *
 * Design:
 *  - [preload] fires background jobs to generate & cache audio for upcoming frames
 *  - [play]    serves from cache instantly; falls back to Android TTS if not yet ready
 *  - Cache key  = MD5(normalised text) → file in app's cache dir
 *  - No re-generation for already-cached keys (hash-based dedup)
 *  - [androidTts] is used both for fallback and for languages without AI voice support
 *
 * Usage in BlackboardActivity:
 *  1. Create once:  aiTtsEngine = BbAiTtsEngine(context, tts)
 *  2. When steps are ready: preload first 3 speech texts
 *  3. In speakFrame():  if (useAiTts) aiTtsEngine.play(...) else tts.speak(...)
 *  4. In advanceFrame(): aiTtsEngine.preload(nextFrame.speech)
 *  5. In onDestroy():   aiTtsEngine.destroy()
 */
class BbAiTtsEngine(
    private val context:    Context,
    val androidTts: TextToSpeechManager
) {
    private val TAG   = "BbAiTtsEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Cache ──────────────────────────────────────────────────────────────────
    /** Text hash → absolute path of cached MP3 */
    private val audioCache  = ConcurrentHashMap<String, String>()
    /** Keys currently being generated (prevents duplicate jobs) */
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()!!
    private val cacheDir = File(context.cacheDir, "bb_tts_cache").also { it.mkdirs() }

    // ── MediaPlayer ────────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null

    // ── Server config (all TTS routes through the FastAPI server) ───────────────
    /** Base URL of the FastAPI server, e.g. "http://108.181.187.227:8003" */
    var selfHostedUrl: String = ""

    /** BCP-47 language code passed to the provider (e.g. "hi-IN", "en-IN") */
    var languageCode: String = "en-US"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start generating audio for [text] in the background and cache the result.
     * Safe to call multiple times for the same text — duplicate generation is suppressed.
     */
    fun preload(text: String) {
        if (text.isBlank()) return
        val key  = textKey(text)
        val file = cachedFile(key)
        if (file.exists()) { audioCache[key] = file.absolutePath; return }
        if (pendingKeys.contains(key)) return
        pendingKeys.add(key)
        scope.launch { generateAndCache(text, key) { pendingKeys.remove(key) } }
    }

    /**
     * Play [text] using AI TTS if the audio is already cached;
     * otherwise falls back to [androidTts] immediately and schedules preloading for next time.
     *
     * [langTag]   BCP-47 locale for the Android TTS fallback (e.g. "hi-IN")
     * [onUsedAi]  called with `true` if the AI cached audio was actually played
     *             (so the caller can charge credits)
     */
    fun play(
        text: String,
        langTag: String,
        callback: TTSCallback,
        onUsedAi: (Boolean) -> Unit = {}
    ) {
        if (text.isBlank()) { callback.onComplete(); return }
        stop()
        val key        = textKey(text)
        val cachedPath = resolveCache(key)
        if (cachedPath != null) {
            Log.d(TAG, "Playing cached AI audio: key=$key")
            onUsedAi(true)
            playFile(cachedPath, langTag, text, callback)
        } else {
            // Not ready — immediately use Android TTS so the student isn't kept waiting
            Log.d(TAG, "AI audio not ready for key=$key; using Android TTS fallback")
            onUsedAi(false)
            preload(text)   // cache it for next session
            androidTts.setLocale(java.util.Locale.forLanguageTag(langTag))
            androidTts.speak(text, callback)
        }
    }

    /** Stop any currently playing audio (AI or Android TTS). */
    fun stop() {
        try {
            mediaPlayer?.run { if (isPlaying) stop(); reset() }
        } catch (_: Exception) {}
        androidTts.stop()
    }

    /** Call from Activity.onDestroy() */
    fun destroy() {
        scope.cancel()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        // Note: androidTts.destroy() is called by BlackboardActivity directly
    }

    /** Clear the disk cache (e.g. on logout or cache size exceeded) */
    fun clearCache() {
        audioCache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "AI TTS cache cleared")
    }

    /** How many files are in the disk cache right now. */
    fun cacheSize(): Int = cacheDir.listFiles()?.size ?: 0

    // ── Private ────────────────────────────────────────────────────────────────

    private fun resolveCache(key: String): String? {
        audioCache[key]?.let { return it }
        val file = cachedFile(key)
        if (file.exists()) { audioCache[key] = file.absolutePath; return file.absolutePath }
        return null
    }

    private fun cachedFile(key: String) = File(cacheDir, "$key.mp3")

    private fun playFile(path: String, langTag: String, text: String, callback: TTSCallback) {
        try {
            mediaPlayer?.run { if (isPlaying) stop(); reset() }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { callback.onComplete() }
                setOnErrorListener { _, what, _ ->
                    Log.e(TAG, "MediaPlayer error: $what — falling back to Android TTS")
                    androidTts.setLocale(java.util.Locale.forLanguageTag(langTag))
                    androidTts.speak(text, callback)
                    true
                }
                start()
            }
            callback.onStart()
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed: ${e.message} — falling back to Android TTS")
            androidTts.setLocale(java.util.Locale.forLanguageTag(langTag))
            androidTts.speak(text, callback)
        }
    }

    /** Blocking — runs on [Dispatchers.IO] coroutine. */
    private fun generateAndCache(text: String, key: String, onDone: () -> Unit) {
        val targetFile = cachedFile(key)

        val onSuccess: (ByteArray) -> Unit = { bytes ->
            try {
                FileOutputStream(targetFile).use { it.write(bytes) }
                audioCache[key] = targetFile.absolutePath
                Log.d(TAG, "AI TTS cached: key=$key size=${bytes.size}B url=$selfHostedUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Cache write failed: ${e.message}")
            }
            onDone()
        }
        val onError: (String) -> Unit = { msg ->
            Log.w(TAG, "AI TTS generation failed: $msg")
            onDone()
        }

        // All TTS goes through the server — API keys never leave the server
        val token = TokenManager.getToken() ?: ""
        AiTtsProvider.serverTts(
            text, languageCode, serverUrl = selfHostedUrl, authToken = token,
            onSuccess = onSuccess, onError = onError
        )
    }

    private fun textKey(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
}
