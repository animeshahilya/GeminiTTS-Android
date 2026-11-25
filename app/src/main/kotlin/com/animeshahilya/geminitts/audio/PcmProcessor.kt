package com.animeshahilya.geminitts.audio

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import java.io.InterruptedIOException
import kotlin.math.abs

/**
 * Feeds 16-bit PCM into a [SynthesisCallback], applying the requested speech rate and pitch with
 * Sonic. Throws [InterruptedIOException] as soon as the utterance is cancelled.
 */
class PcmProcessor(
    private val callback: SynthesisCallback,
    private val isCancelled: () -> Boolean,
    private val speed: Float = 1f,
    private val pitch: Float = 1f
) {
    private var sonic: Sonic? = null
    private var frameBytes = 2
    private var buffer = ByteArray(4096)

    // Sped-up audio drains faster than it arrives; hold the first 250 ms so playback doesn't stutter.
    private var reserve: ByteArray? = null
    private var reserveBytes = 0

    var started = false
        private set
    private var finished = false

    /** Starts the callback once; later calls are ignored. */
    fun start(sampleRate: Int, channels: Int) {
        if (started) return
        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels)
        frameBytes = channels * 2
        val max = runCatching { callback.maxBufferSize }.getOrDefault(4096)
        buffer = ByteArray(((max / frameBytes) * frameBytes).takeIf { it > 0 } ?: 4096)
        if (abs(speed - 1f) > 0.001f || abs(pitch - 1f) > 0.001f) {
            sonic = Sonic(sampleRate, channels).also {
                it.speed = speed
                it.pitch = pitch
            }
            if (speed > 1f) reserve = ByteArray(sampleRate / 4 * frameBytes)
        }
        started = true
    }

    private fun checkCancelled() {
        if (isCancelled()) throw InterruptedIOException("Utterance stopped")
    }

    fun pcm(bytes: ByteArray) {
        check(started) { "start() first" }
        checkCancelled()
        var offset = 0
        var length = (bytes.size / frameBytes) * frameBytes
        if (length <= 0) return
        val activeSonic = sonic
        if (activeSonic == null) {
            emit(bytes, 0, length)
            return
        }
        reserve?.let { held ->
            val take = minOf(held.size - reserveBytes, length)
            System.arraycopy(bytes, 0, held, reserveBytes, take)
            reserveBytes += take
            offset = take
            length -= take
            if (reserveBytes < held.size) return
            flushReserve(activeSonic)
        }
        if (length > 0) {
            activeSonic.writeBytesToStream(if (offset == 0) bytes else bytes.copyOfRange(offset, offset + length), length)
        }
        drain(activeSonic)
    }

    private fun flushReserve(activeSonic: Sonic) {
        reserve?.let { if (reserveBytes > 0) activeSonic.writeBytesToStream(it, reserveBytes) }
        reserve = null
        reserveBytes = 0
    }

    private fun emit(bytes: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            checkCancelled()
            val chunk = minOf(buffer.size, length - sent)
            callback.audioAvailable(bytes, offset + sent, chunk)
            sent += chunk
        }
    }

    private fun drain(activeSonic: Sonic) {
        while (activeSonic.samplesAvailable() > 0) {
            checkCancelled()
            val read = activeSonic.readBytesFromStream(buffer, buffer.size)
            if (read <= 0) break
            callback.audioAvailable(buffer, 0, read)
        }
    }

    /** Plays out what is held back (short words like "OK" never fill the reserve) and ends the utterance. */
    fun finish(defaultSampleRate: Int = 24_000) {
        if (finished) return
        finished = true
        if (!started) start(defaultSampleRate, 1)
        checkCancelled()
        sonic?.let {
            flushReserve(it)
            it.flushStream()
            drain(it)
        }
        callback.done()
    }
}
