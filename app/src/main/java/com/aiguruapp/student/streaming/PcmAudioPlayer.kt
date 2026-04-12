package com.aiguruapp.student.streaming

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays raw 16-bit PCM audio chunks sent by the Gemini Live API.
 *
 * Gemini Live outputs: 24 000 Hz, mono, PCM_16BIT (little-endian).
 *
 * Architecture — same producer/consumer pattern as [StreamingAudioPlayer]:
 *   • [enqueue] puts chunks on the queue from any thread.
 *   • A dedicated play thread drains the queue and writes to [AudioTrack].
 */
class PcmAudioPlayer {

    companion object {
        private const val TAG         = "PcmAudioPlayer"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
    }

    private val queue   = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    /** Start the playback engine. Safe to call multiple times. */
    fun start() {
        if (running.getAndSet(true)) return

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        playThread = Thread({
            while (running.get()) {
                try {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    audioTrack?.write(chunk, 0, chunk.size)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Playback error: $e")
                }
            }
        }, "PcmAudioPlayer-play").also { it.start() }

        Log.i(TAG, "Player started ($SAMPLE_RATE Hz PCM)")
    }

    /**
     * Enqueue a raw PCM chunk for immediate playback.
     * Starts the player automatically if not yet running.
     */
    fun enqueue(pcm: ByteArray) {
        if (!running.get()) start()
        queue.offer(pcm)
    }

    /** Discard all buffered audio and stop playback mid-stream (barge-in / interruption). */
    fun flush() {
        queue.clear()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    /** Stop completely and release the AudioTrack. */
    fun stop() {
        running.set(false)
        queue.clear()
        playThread?.interrupt()
        playThread = null
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        Log.i(TAG, "Player stopped")
    }

    val isPlaying: Boolean
        get() = running.get() && queue.isNotEmpty()
}
