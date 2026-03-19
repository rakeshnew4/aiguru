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
    fun onBeginningOfSpeech() {}
    /** Fired every ~100ms with current mic amplitude (0–~10 dB). Use for visual feedback. */
    fun onSoundLevel(rms: Float) {}
}

class VoiceManager(private val context: Context) {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var callback: VoiceRecognitionCallback? = null
    private val TAG = "VoiceManager"

    // ── Interrupt recognizer (runs while TTS is speaking) ──────────────────
    private var interruptRecognizer: SpeechRecognizer? = null
    private var interruptCallback: VoiceRecognitionCallback? = null
    private var interruptPeakRms: Float = 0f

    // ── Noise-floor calibration ───────────────────────────────────────
    // Ambient noise floor is estimated from RMS values collected before the user
    // starts speaking (onReadyForSpeech → onBeginningOfSpeech window).
    private var noiseFloor = 0f                   // main recognizer
    private var interruptNoiseFloor = 0f          // interrupt recognizer
    private var preRmsSum = 0f
    private var preRmsCount = 0
    private var interruptPreRmsSum = 0f
    private var interruptPreRmsCount = 0
    private var mainSpeechStarted = false
    private var interruptSpeechStarted = false

    /** Ambient noise floor estimated by the main recognizer (0 = not yet calibrated). */
    fun getNoiseFloor() = noiseFloor

    /** Peak RMS (loudness) recorded during the last interrupt-listening session. */
    fun getInterruptPeakRms() = interruptPeakRms

    /** Ambient noise floor estimated by the interrupt recognizer. */
    fun getInterruptNoiseFloor() = interruptNoiseFloor

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
        mainSpeechStarted = false
        preRmsSum = 0f
        preRmsCount = 0
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Faster end-of-speech detection — snappier conversational feel
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                // Prefer on-device recognition when available (faster, works offline)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        interruptPeakRms = 0f
        interruptSpeechStarted = false
        interruptPreRmsSum = 0f
        interruptPreRmsCount = 0
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
                // Require at least 550ms of continuous speech before accepting
                // — filters coughs, chair scrapes, brief noise pops
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 550L)
                // Quick silence detection so decision happens fast
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                // Prefer offline for low latency during barge-in
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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

    fun destroy() {
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
            mainSpeechStarted = true
            // Finalize noise-floor estimate from pre-speech RMS samples
            if (preRmsCount > 0) noiseFloor = preRmsSum / preRmsCount
            callback?.onBeginningOfSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!mainSpeechStarted) {
                // Still in pre-speech window — accumulate ambient noise
                preRmsSum += rmsdB
                preRmsCount++
            }
            callback?.onSoundLevel(rmsdB)
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
        override fun onEndOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            if (!interruptSpeechStarted) {
                interruptPreRmsSum += rmsdB
                interruptPreRmsCount++
            }
            if (rmsdB > interruptPeakRms) interruptPeakRms = rmsdB
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Interrupt: beginning of speech detected")
            interruptSpeechStarted = true
            if (interruptPreRmsCount > 0) interruptNoiseFloor = interruptPreRmsSum / interruptPreRmsCount
            interruptCallback?.onBeginningOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { interruptCallback?.onPartialResults(it) }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val scores  = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val text = matches?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
            val confidence = scores?.firstOrNull() ?: 0.5f
            Log.d(TAG, "Interrupt result: \"$text\" confidence=$confidence")
            // Low-confidence result (< 0.35) is likely noise — discard silently
            if (confidence < 0.35f) {
                Log.d(TAG, "Interrupt discarded: confidence too low ($confidence)")
                interruptCallback?.onError("low_confidence")
                return
            }
            interruptCallback?.onResults(text)
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Interrupt recognizer error: $error (expected)")
            interruptCallback?.onError(error.toString())
        }
    }
}
