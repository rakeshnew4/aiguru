package com.aiguruapp.student.streaming

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures microphone PCM audio at 16 kHz / 16-bit / mono and delivers
 * ~20 ms chunks via [onChunk].  Whisper works best at 16 kHz mono.
 *
 * Usage:
 *   val streamer = AudioStreamer { pcmBytes -> sendToServer(pcmBytes) }
 *   streamer.start()
 *   // …
 *   streamer.stop()
 */
class AudioStreamer(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        private const val TAG          = "AudioStreamer"
        const val SAMPLE_RATE          = 16_000
        private const val CHANNEL      = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
        // 20 ms per chunk → 320 samples × 2 bytes = 640 bytes
        private const val CHUNK_FRAMES = SAMPLE_RATE / 50
        private const val CHUNK_BYTES  = CHUNK_FRAMES * 2   // 16-bit = 2 bytes/sample
    }

    @Volatile private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    /** Start capturing.  Requires RECORD_AUDIO permission. */
    fun start() {
        if (isRunning) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 8)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufSize
        ).also { rec ->
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                rec.release()
                return
            }
            rec.startRecording()
        }

        isRunning = true
        captureThread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (isRunning) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    onChunk(buf.copyOf(read))
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                           read == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord read error: $read")
                    break
                }
            }
        }, "AudioStreamer-capture").also { it.start() }

        Log.i(TAG, "Streaming started ($SAMPLE_RATE Hz, ${CHUNK_BYTES}-byte chunks)")
    }

    /** Stop capturing and release hardware. */
    fun stop() {
        isRunning = false
        captureThread?.interrupt()
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Streaming stopped")
    }
}
