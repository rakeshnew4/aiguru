package com.aiguruapp.student.streaming

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays MP3 audio chunks sent by the server (edge-tts output).
 *
 * Architecture:
 *   • A producer thread reads [ByteArray] chunks from [queue].
 *   • Each chunk is decoded from MP3 → PCM via [MediaCodec] + [MediaExtractor].
 *   • Decoded PCM frames are written to an [AudioTrack] in streaming mode.
 *
 * Phase-1 note: if server-side TTS is disabled the queue stays empty
 * and the AudioTrack is never opened — no resource waste.
 */
class StreamingAudioPlayer {

    companion object {
        private const val TAG        = "StreamingAudioPlayer"
        private const val SAMPLE_RATE = 22_050   // edge-tts default
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
    }

    private val queue   = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var playThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    /** Begin playback loop.  Call once before the first [enqueue]. */
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
                    // Block up to 200 ms then re-check running flag
                    val chunk = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    decodeMp3AndPlay(chunk)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Playback error: $e")
                }
            }
        }, "StreamingAudioPlayer-play").also { it.start() }

        Log.i(TAG, "Player started")
    }

    /** Enqueue an MP3 chunk for decoding + playback. */
    fun enqueue(mp3Chunk: ByteArray) {
        if (!running.get()) start()
        queue.offer(mp3Chunk)
    }

    /** Stop immediately, flush queue and release resources. */
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

    // ── MP3 → PCM via MediaCodec ───────────────────────────────────────────

    private fun decodeMp3AndPlay(mp3: ByteArray) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(mp3))
            if (extractor.trackCount == 0) return

            val format = extractor.getTrackFormat(0)
            val mime   = format.getString(MediaFormat.KEY_MIME) ?: return
            extractor.selectTrack(0)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info     = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos && running.get()) {
                // Feed input
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.get(pcm, 0, info.size)
                        audioTrack?.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "MP3 decode error: $e")
        } finally {
            extractor.release()
        }
    }
}

// ── Minimal MediaDataSource backed by a ByteArray ─────────────────────────────

private class ByteArrayMediaDataSource(private val data: ByteArray) :
    android.media.MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val pos = position.toInt()
        if (pos >= data.size) return -1
        val len = minOf(size, data.size - pos)
        data.copyInto(buffer, offset, pos, pos + len)
        return len
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
