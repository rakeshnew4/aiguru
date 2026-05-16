package com.aiguruapp.student.utils

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
    fun onBeginningOfSpeech() {}
    fun onRmsChanged(rms: Float) {}
}

class VoiceManager(private val context: Context) {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var callback: VoiceRecognitionCallback? = null
    private val TAG = "VoiceManager"

    // ── Interrupt recognizer (runs while TTS is speaking) ──────────────────
    private var interruptRecognizer: SpeechRecognizer? = null
    private var interruptCallback: VoiceRecognitionCallback? = null

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

    fun startListening(callback: VoiceRecognitionCallback, language: String = "en-US") {
        this.callback = callback
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
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

    fun startInterruptListening(cb: VoiceRecognitionCallback, language: String = "en-US") {
        interruptCallback = cb
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return
            interruptRecognizer?.destroy()
            interruptRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            interruptRecognizer?.setRecognitionListener(InterruptListenerImpl())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            interruptRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting interrupt listener", e)
        }
    }

    fun stopInterruptListening() {
        try {
            interruptRecognizer?.stopListening()
            interruptRecognizer?.destroy()
            interruptRecognizer = null
            interruptCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping interrupt listener", e)
        }
    }

    // ── Wake Word Loop ────────────────────────────────────────────────────────
    // Self-restarting loop using onResults only (reliable across all OEMs).
    // Audio keeps playing during detection. Calls onDetected when matched.

    private val wakeWordHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wakeWordRecognizer: SpeechRecognizer? = null
    private var wakeWordActive = false

    /**
     * Starts a continuous wake-word detection loop.
     * Calls [onDetected] on the main thread with the matched word.
     * Uses [onResults] only — no partial results — for OEM reliability.
     */
    fun startWakeWordLoop(
        wakeWords: List<String>,
        onDetected: (matchedWord: String) -> Unit,
        language: String = "en-US"
    ) {
        stopWakeWordLoop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Wake word loop: SpeechRecognizer not available")
            return
        }
        wakeWordActive = true
        _runWakeWordCycle(wakeWords, onDetected, language, 0L)
    }

    /** Stops the wake word loop and releases its recognizer. Safe to call anytime. */
    fun stopWakeWordLoop() {
        wakeWordActive = false
        wakeWordHandler.removeCallbacksAndMessages(null)
        try { wakeWordRecognizer?.stopListening() } catch (_: Exception) {}
        try { wakeWordRecognizer?.destroy() } catch (_: Exception) {}
        wakeWordRecognizer = null
    }

    private fun _runWakeWordCycle(
        wakeWords: List<String>,
        onDetected: (String) -> Unit,
        language: String,
        delayMs: Long
    ) {
        if (!wakeWordActive) return
        wakeWordHandler.postDelayed({
            if (!wakeWordActive) return@postDelayed
            try {
                wakeWordRecognizer?.destroy()
                wakeWordRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                wakeWordRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rms: Float) {}
                    override fun onBufferReceived(buf: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                    override fun onPartialResults(b: Bundle?) {} // intentionally unused

                    override fun onResults(b: Bundle?) {
                        if (!wakeWordActive) return
                        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.lowercase()?.trim() ?: ""
                        val matched = wakeWords.firstOrNull { word ->
                            text.contains(word.lowercase())
                        }
                        if (matched != null) {
                            wakeWordActive = false
                            onDetected(matched)
                        } else {
                            _runWakeWordCycle(wakeWords, onDetected, language, 200L)
                        }
                    }

                    override fun onError(error: Int) {
                        if (!wakeWordActive) return
                        val delay = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                            SpeechRecognizer.ERROR_AUDIO           -> 800L
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                wakeWordActive = false; return
                            }
                            else -> 300L
                        }
                        _runWakeWordCycle(wakeWords, onDetected, language, delay)
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                }
                wakeWordRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Wake word cycle error: ${e.message}")
                _runWakeWordCycle(wakeWords, onDetected, language, 500L)
            }
        }, delayMs)
    }

    fun destroy() {
        stopWakeWordLoop()
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        }
        stopInterruptListening()
    }

    private inner class RecognitionListenerImpl : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech")
            callback?.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            callback?.onRmsChanged(rmsdB)
        }

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
            callback?.onError(errorMessage)
        }

        override fun onResults(results: Bundle?) {
            results?.let {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val recognizedText = matches[0]
                    Log.d(TAG, "Recognized: $recognizedText")
                    callback?.onResults(recognizedText)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.let {
                val matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val partialText = matches[0]
                    Log.d(TAG, "Partial result: $partialText")
                    callback?.onPartialResults(partialText)
                }
            }
        }
    }

    // ── Interrupt Recognizer Listener ─────────────────────────────────────────
    private inner class InterruptListenerImpl : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onEndOfSpeech() {}

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Interrupt: beginning of speech detected")
            interruptCallback?.onBeginningOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { interruptCallback?.onPartialResults(it) }
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { interruptCallback?.onResults(it) }
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Interrupt recognizer error: $error (expected)")
            interruptCallback?.onError(error.toString())
        }
    }
}
