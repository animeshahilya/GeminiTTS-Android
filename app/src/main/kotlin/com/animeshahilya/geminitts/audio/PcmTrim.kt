package com.animeshahilya.geminitts.audio

import kotlin.math.abs

/**
 * Strips neural model dead air without adding live latency:
 * - [LeadingSilenceTrimmer] drops leading silence frames up to [MAX_LEAD_TRIM_MS], preserving a
 *   short [PRE_ROLL_MS] ring buffer so soft consonant onsets are never clipped.
 * - [trimTrailingSilence] trims trailing dead air from buffered clips (cache entries or
 *   high-speed reserve) with a [POST_PAD_MS] tail cushion.
 */
class LeadingSilenceTrimmer(
    sampleRateHz: Int,
    private val channels: Int,
    private val threshold: Int = SILENCE_THRESHOLD
) {
    private val frameBytes = channels * 2
    private val maxTrimBytes = (sampleRateHz * MAX_LEAD_TRIM_MS / 1000) * frameBytes
    private val preRollCapacity = ((sampleRateHz * PRE_ROLL_MS / 1000) * frameBytes).coerceAtLeast(frameBytes)
    private val preRoll = ByteArray(preRollCapacity)

    private var preRollHead = 0
    private var preRollLen = 0
    private var trimmedBytes = 0
    var released = false
        private set

    /**
     * Filters [bytes] while still in leading silence. Once speech onset is found (or [maxTrimBytes]
     * is reached), emits the held pre-roll followed by the remaining audio.
     */
    fun filter(bytes: ByteArray, length: Int): ByteArray {
        val validLen = (length / frameBytes) * frameBytes
        if (validLen <= 0) return EMPTY
        if (released) return if (validLen == bytes.size) bytes else bytes.copyOf(validLen)

        var pos = 0
        while (pos + frameBytes <= validLen) {
            var loud = false
            for (ch in 0 until channels) {
                if (abs(AudioOptimizer.getSample16(bytes, pos + ch * 2).toInt()) > threshold) {
                    loud = true
                    break
                }
            }
            if (loud || trimmedBytes >= maxTrimBytes) {
                released = true
                val remaining = validLen - pos
                val out = ByteArray(preRollLen + remaining)
                copyPreRollTo(out)
                System.arraycopy(bytes, pos, out, preRollLen, remaining)
                preRollLen = 0
                return out
            }
            appendPreRoll(bytes, pos)
            trimmedBytes += frameBytes
            pos += frameBytes
        }
        return EMPTY
    }

    /** Flushes any held pre-roll if the entire utterance was quieter than [threshold]. */
    fun flushIfUnreleased(): ByteArray {
        if (released || preRollLen == 0) return EMPTY
        released = true
        val out = ByteArray(preRollLen)
        copyPreRollTo(out)
        preRollLen = 0
        return out
    }

    private fun appendPreRoll(src: ByteArray, pos: Int) {
        for (i in 0 until frameBytes) {
            val writeAt = (preRollHead + preRollLen) % preRollCapacity
            preRoll[writeAt] = src[pos + i]
            if (preRollLen < preRollCapacity) {
                preRollLen++
            } else {
                preRollHead = (preRollHead + 1) % preRollCapacity
            }
        }
    }

    private fun copyPreRollTo(dest: ByteArray) {
        if (preRollLen == 0) return
        val first = minOf(preRollLen, preRollCapacity - preRollHead)
        System.arraycopy(preRoll, preRollHead, dest, 0, first)
        if (first < preRollLen) {
            System.arraycopy(preRoll, 0, dest, first, preRollLen - first)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        const val SILENCE_THRESHOLD = 120
        const val MAX_LEAD_TRIM_MS = 300
        const val PRE_ROLL_MS = 8
        const val POST_PAD_MS = 12

        /** Trims both leading and trailing silence from a complete buffered clip. */
        fun trimClip(
            pcm: ByteArray,
            sampleRateHz: Int,
            channels: Int,
            trimLead: Boolean = true,
            trimTail: Boolean = true,
            threshold: Int = SILENCE_THRESHOLD
        ): ByteArray {
            val frameBytes = channels * 2
            val validLen = (pcm.size / frameBytes) * frameBytes
            if (validLen <= frameBytes) return pcm

            var start = 0
            if (trimLead) {
                val maxLead = minOf(validLen, (sampleRateHz * MAX_LEAD_TRIM_MS / 1000) * frameBytes)
                var pos = 0
                while (pos < maxLead && isQuietFrame(pcm, pos, channels, threshold)) {
                    pos += frameBytes
                }
                val preRoll = (sampleRateHz * PRE_ROLL_MS / 1000) * frameBytes
                start = (pos - preRoll).coerceAtLeast(0)
                start = (start / frameBytes) * frameBytes
            }

            var end = validLen
            if (trimTail && end > start) {
                var pos = validLen - frameBytes
                while (pos >= start && isQuietFrame(pcm, pos, channels, threshold)) {
                    pos -= frameBytes
                }
                val pad = (sampleRateHz * POST_PAD_MS / 1000) * frameBytes
                end = minOf(validLen, pos + frameBytes + pad)
                end = (end / frameBytes) * frameBytes
            }

            return if (start == 0 && end == pcm.size) pcm else if (end <= start) EMPTY else pcm.copyOfRange(start, end)
        }

        private fun isQuietFrame(pcm: ByteArray, offset: Int, channels: Int, threshold: Int): Boolean {
            for (ch in 0 until channels) {
                if (abs(AudioOptimizer.getSample16(pcm, offset + ch * 2).toInt()) > threshold) return false
            }
            return true
        }
    }
}
