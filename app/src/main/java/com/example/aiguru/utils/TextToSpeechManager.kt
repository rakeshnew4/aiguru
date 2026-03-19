package com.example.aiguru.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

interface TTSCallback {
    fun onStart()
    fun onComplete()
    fun onError(error: String)
}

class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var callback: TTSCallback? = null
    private val TAG = "TextToSpeechManager"

    /** Updated by onRangeStart — char offset of the word currently being spoken. */
    var currentSpeakingChar: Int = 0
        private set

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
                isReady = false
            }
            tts?.setOnUtteranceProgressListener(UtteranceProgressListenerImpl())
        } else {
            Log.e(TAG, "Initialization failed")
            isReady = false
        }
    }

    fun speak(text: String, callback: TTSCallback) {
        this.callback = callback
        if (!isReady) {
            callback.onError("Text-to-Speech not initialized")
            return
        }

        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_id")
            callback.onStart()
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking", e)
            callback.onError("Unable to speak: ${e.message}")
        }
    }

    fun stop() {
        try {
            if (isReady && tts != null) {
                tts!!.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Resume speaking from a saved char offset, jumping back to the nearest sentence
     * boundary so the speech sounds natural (not starting mid-word).
     */
    fun speakFrom(fullText: String, charOffset: Int, callback: TTSCallback) {
        val safeOffset = charOffset.coerceIn(0, fullText.length)
        val sentenceStart = if (safeOffset < 2) 0 else {
            val idx = fullText.lastIndexOfAny(charArrayOf('.', '?', '!', '\n'), safeOffset - 1)
            if (idx < 0) 0 else minOf(idx + 2, fullText.length)
        }
        speak(fullText.substring(sentenceStart).trimStart(), callback)
    }

    fun setPitch(pitch: Float) {
        try {
            if (isReady) {
                tts?.setPitch(pitch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pitch", e)
        }
    }

    fun setSpeechRate(rate: Float) {
        try {
            if (isReady) {
                tts?.setSpeechRate(rate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speech rate", e)
        }
    }

    fun setLocale(locale: Locale) {
        if (!isReady) return
        try {
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language not available: ${locale.language}, falling back to US English")
                tts?.setLanguage(Locale.US)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting locale", e)
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying TTS", e)
        }
    }

    private inner class UtteranceProgressListenerImpl : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "Speech started")
            currentSpeakingChar = 0
        }

        // Tracks the char position of each word being spoken (API 26+)
        override fun onRangeStart(
            utteranceId: String?,
            start: Int,
            end: Int,
            frame: Int
        ) {
            currentSpeakingChar = start
        }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "Speech completed")
            currentSpeakingChar = 0
            callback?.onComplete()
        }

        // Override onStop so that calling tts.stop() does NOT fire onError.
        // Default UtteranceProgressListener.onStop() calls onError() which would
        // incorrectly trigger error handlers when we manually stop for a barge-in.
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Log.d(TAG, "Speech stopped manually (interrupted=$interrupted)")
            currentSpeakingChar = 0
            // Intentionally NOT forwarding to callback — manual stop is not an error.
        }

        override fun onError(utteranceId: String?) {
            Log.e(TAG, "Speech error")
            currentSpeakingChar = 0
            callback?.onError("Speech error occurred")
        }
    }
}
