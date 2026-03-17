package com.example.aiguru.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

interface VoiceRecognitionCallback {
    fun onResults(text: String)
    fun onError(error: String)
    fun onPartialResults(text: String)
    fun onListeningStarted()
    fun onListeningFinished()
}

class VoiceManager(private val context: Context) {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var callback: VoiceRecognitionCallback? = null
    private val TAG = "VoiceManager"

    init {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer.setRecognitionListener(RecognitionListenerImpl())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechRecognizer", e)
        }
    }

    fun startListening(callback: VoiceRecognitionCallback) {
        this.callback = callback
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer.startListening(intent)
            callback.onListeningStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening", e)
            callback.onError("Unable to start voice recognition")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer.stopListening()
            callback?.onListeningFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening", e)
        }
    }

    fun destroy() {
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        }
    }

    private inner class RecognitionListenerImpl : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Unknown error"
            }
            Log.e(TAG, "Recognition error: $errorMessage")
            callback?.onError(errorMessage)\n        }\n\n        override fun onResults(results: Bundle?) {\n            results?.let {\n                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)\n                if (matches != null && matches.isNotEmpty()) {\n                    val recognizedText = matches[0]\n                    Log.d(TAG, \"Recognized: $recognizedText\")\n                    callback?.onResults(recognizedText)\n                }\n            }\n        }\n\n        override fun onPartialResults(partialResults: Bundle?) {\n            partialResults?.let {\n                val matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)\n                if (matches != null && matches.isNotEmpty()) {\n                    val partialText = matches[0]\n                    Log.d(TAG, \"Partial result: $partialText\")\n                    callback?.onPartialResults(partialText)\n                }\n            }\n        }\n    }\n}\n