package com.animeshahilya.geminitts.audio

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import java.io.InterruptedIOException
import kotlin.math.abs

/**
 * Feeds 16-bit PCM into a [SynthesisCallback], applying speech rate and pitch with Sonic,
 * optional leading-silence trimming, and [AudioOptimizer] volume/clarity/limiting/de-clicking.
 * Throws [InterruptedIOException] as soon as the utterance is cancelled.
 */
class PcmProcessor(
    private val callback: SynthesisCallback,
    private val isCancelled: () -> Boolean,
    private val speed: Float = 1f,
    private val pitch: Float = 1f,
    private val volume: Float = 1f,
    private val clarity: ClarityPreset = ClarityPreset.OFF,
    private val leveler: Boolean = false,
    private val trimLeadingSilence: Boolean = false,
    private val isSingleChar: Boolean = false
) {
    private var sonic: Sonic? = null
    private var optimizer: AudioOptimizer? = null
    private var leadTrimmer: LeadingSilenceTrimmer? = null
    private var sampleRateHz = 24_000
    private var channelCount = 1
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
        sampleRateHz = sampleRate
        channelCount = channels
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
        val needsOpt = sonic != null || clarity != ClarityPreset.OFF || leveler || abs(volume - 1f) > 0.001f
        if (needsOpt) {
            optimizer = AudioOptimizer(
                sampleRateHz = sampleRate,
                channels = channels,
                volumeScale = volume.coerceIn(0f, 2f),
                preset = clarity,
                levelerEnabled = leveler,
                declickEnabled = true,
                isSingleChar = isSingleChar
            )
        }
        if (trimLeadingSilence) leadTrimmer = LeadingSilenceTrimmer(sampleRate, channels)
        started = true
    }

    /** Re-arms leading-silence trimming between multi-chunk synthesis pieces. */
    fun resetLeadingTrim() {
        if (!trimLeadingSilence || !started) return
        leadTrimmer?.flushIfUnreleased()?.let { leftover ->
            if (leftover.isNotEmpty()) feedPipeline(leftover, leftover.size)
        }
        leadTrimmer = LeadingSilenceTrimmer(sampleRateHz, channelCount)
    }

    private fun checkCancelled() {
        if (isCancelled()) throw InterruptedIOException("Utterance stopped")
    }

    fun pcm(bytes: ByteArray) {
        check(started) { "start() first" }
        checkCancelled()
        val input = leadTrimmer?.let { trimmer ->
            val filtered = trimmer.filter(bytes, bytes.size)
            if (trimmer.released) leadTrimmer = null
            filtered
        } ?: bytes
        val length = (input.size / frameBytes) * frameBytes
        if (length <= 0) return
        feedPipeline(input, length)
    }

    private fun feedPipeline(bytes: ByteArray, length: Int) {
        var offset = 0
        var remaining = length
        val activeSonic = sonic
        if (activeSonic == null) {
            emit(bytes, 0, remaining)
            return
        }
        reserve?.let { held ->
            val take = minOf(held.size - reserveBytes, remaining)
            System.arraycopy(bytes, 0, held, reserveBytes, take)
            reserveBytes += take
            offset = take
            remaining -= take
            if (reserveBytes < held.size) return
            flushReserve(activeSonic)
        }
        if (remaining > 0) {
            activeSonic.writeBytesToStream(
                if (offset == 0) bytes else bytes.copyOfRange(offset, offset + remaining),
                remaining
            )
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
        val opt = optimizer
        while (sent < length) {
            checkCancelled()
            val chunk = minOf(buffer.size, length - sent)
            if (opt != null) {
                // Copy into buffer so cached PCM arrays passed to pcm() are never mutated in place.
                System.arraycopy(bytes, offset + sent, buffer, 0, chunk)
                opt.process(buffer, 0, chunk)
                callback.audioAvailable(buffer, 0, chunk)
            } else {
                callback.audioAvailable(bytes, offset + sent, chunk)
            }
            sent += chunk
        }
    }

    private fun drain(activeSonic: Sonic) {
        val opt = optimizer
        while (activeSonic.samplesAvailable() > 0) {
            checkCancelled()
            val read = activeSonic.readBytesFromStream(buffer, buffer.size)
            if (read <= 0) break
            opt?.process(buffer, 0, read)
            callback.audioAvailable(buffer, 0, read)
        }
    }

    /** Plays out any held reserve/pre-roll, applies trailing fade, and ends the utterance. */
    fun finish(defaultSampleRate: Int = 24_000) {
        if (finished) return
        finished = true
        if (!started) start(defaultSampleRate, 1)
        checkCancelled()
        leadTrimmer?.let { trimmer ->
            val tailPreRoll = trimmer.flushIfUnreleased()
            leadTrimmer = null
            if (tailPreRoll.isNotEmpty()) feedPipeline(tailPreRoll, tailPreRoll.size)
        }
        sonic?.let {
            flushReserve(it)
            it.flushStream()
            drain(it)
        }
        optimizer?.generateTrailingFade()?.let { fade ->
            var offset = 0
            while (offset < fade.size) {
                checkCancelled()
                val chunk = minOf(buffer.size, fade.size - offset)
                callback.audioAvailable(fade, offset, chunk)
                offset += chunk
            }
        }
        callback.done()
    }
}
