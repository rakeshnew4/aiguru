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
        }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "Speech completed")
            callback?.onComplete()
        }

        override fun onError(utteranceId: String?) {
            Log.e(TAG, "Speech error")
            callback?.onError("Speech error occurred")
        }
    }
}
