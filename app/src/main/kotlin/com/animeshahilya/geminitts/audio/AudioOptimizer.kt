package com.animeshahilya.geminitts.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

enum class ClarityPreset(
    val id: String,
    val label: String,
    val summary: String,
    val presenceBlend: Float,
    val maxLevelerGain: Float
) {
    OFF("off", "Off", "Original voice sound, with clip protection only.", 0f, 1.0f),
    GENTLE("gentle", "Gentle clarity", "Slight consonant lift for fast listening.", 0.05f, 1.2f),
    CLEAR("clear", "Voice clarity", "Crisper consonants at high speed and on phone speakers.", 0.10f, 1.4f),
    OUTDOOR("outdoor", "Outdoor boost", "Stronger presence and leveling for noisy places.", 0.16f, 1.65f);

    companion object {
        fun fromId(id: String?): ClarityPreset = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * Per-utterance post-DSP processor for 16-bit PCM: volume scaling, dynamic loudness leveling,
 * 2.5-5 kHz vocal presence clarity, true-peak clip limiting, and raised-cosine de-clicking.
 *
 * Adapted from the neural stages of eSpeak NG's AudioOptimizer (glottal anti-buzz tilt and
 * low-end warmth are omitted because GenAI voices are already natural neural audio).
 */
class AudioOptimizer(
    sampleRateHz: Int,
    private val channels: Int,
    private val volumeScale: Float = 1f,
    private val preset: ClarityPreset = ClarityPreset.OFF,
    levelerEnabled: Boolean = false,
    private val declickEnabled: Boolean = true,
    private val isSingleChar: Boolean = false
) {
    private val usePresence = preset != ClarityPreset.OFF
    private val useLeveler = levelerEnabled && !isSingleChar && preset.maxLevelerGain > 1f
    private val useVolume = abs(volumeScale - 1f) > 0.001f
    private val useLimiter = volumeScale > 1.001f || usePresence || useLeveler

    /** True when any sample modification (beyond no-op) is active. */
    val isActive: Boolean
        get() = useVolume || usePresence || useLeveler || useLimiter || declickEnabled

    private val presenceHighpassAlpha = onePoleHighpassPole(minOf(PRESENCE_LOW_HZ, sampleRateHz * 0.35), sampleRateHz)
    private val presenceLowpassAlpha = onePoleAlpha(minOf(PRESENCE_HIGH_HZ, sampleRateHz * 0.45), sampleRateHz)
    private val levelEnvelopeAlpha = scaledAlpha(LEVEL_ENV_ALPHA_REF, sampleRateHz)
    private val gainSmoothAlpha = scaledAlpha(GAIN_SMOOTH_ALPHA_REF, sampleRateHz)
    private val presenceScaledBlend = 32768f * preset.presenceBlend
    private val presenceDriveFactor = PRESENCE_DRIVE / 32768f

    // Per-channel IIR filter states (allocated once per utterance).
    private val prevInput = FloatArray(channels)
    private val prevHighPass = FloatArray(channels)
    private val presenceBand = FloatArray(channels)
    private val prevPresenceBand = FloatArray(channels)
    private val prevOutput = FloatArray(channels)

    private var levelEnvelope = 0f
    private var levelerSmoothedGain = 1f
    private var limiterSmoothedGain = 1f
    private var framesProcessed = 0

    /** Processes [length] PCM16 little-endian bytes in [data] starting at [offset] in place. */
    fun process(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (!isActive || length <= 0) return
        val frameBytes = channels * 2
        val validEnd = minOf(data.size, offset + (length / frameBytes) * frameBytes)
        var pos = offset
        while (pos + frameBytes <= validEnd) {
            val fadeFactor = if (declickEnabled && framesProcessed < DECLICK_FADE_FRAMES) {
                FADE_IN_RAMP[framesProcessed++]
            } else {
                1f
            }

            // Compute shared envelope/limiter across channels in this frame so stereo stays balanced.
            var framePeak = 0f
            for (ch in 0 until channels) {
                val s = getSample16(data, pos + ch * 2).toFloat()
                val a = abs(s)
                if (a > framePeak) framePeak = a
            }
            if (useLeveler) {
                levelEnvelope += levelEnvelopeAlpha * (framePeak - levelEnvelope)
                val target = (LEVELER_TARGET_LEVEL / maxOf(1f, levelEnvelope))
                    .coerceIn(LEVELER_MIN_GAIN, preset.maxLevelerGain)
                levelerSmoothedGain += gainSmoothAlpha * (target - levelerSmoothedGain)
            }

            val totalScale = fadeFactor * volumeScale * (if (useLeveler) levelerSmoothedGain else 1f)
            var postPeak = 0f

            for (ch in 0 until channels) {
                val bytePos = pos + ch * 2
                var sample = getSample16(data, bytePos).toFloat() * totalScale

                if (usePresence) {
                    val hp = presenceHighpassAlpha * (prevHighPass[ch] + sample - prevInput[ch])
                    prevInput[ch] = sample
                    prevHighPass[ch] = hp
                    val band = presenceBand[ch] + presenceLowpassAlpha * (hp - presenceBand[ch])
                    presenceBand[ch] = band
                    val midpoint = (prevPresenceBand[ch] + band) * 0.5f
                    val saturated = (fastTanh(midpoint * presenceDriveFactor) + fastTanh(band * presenceDriveFactor)) * 0.5f
                    prevPresenceBand[ch] = band
                    sample += saturated * presenceScaledBlend
                }

                val mag = abs(sample)
                if (mag > postPeak) postPeak = mag
                tempFrame[ch] = sample
            }

            val limGain = if (useLimiter) {
                val target = if (postPeak > CLIP_GUARD_THRESHOLD) CLIP_GUARD_THRESHOLD / postPeak else 1f
                limiterSmoothedGain = if (target < limiterSmoothedGain) {
                    target
                } else {
                    limiterSmoothedGain + CLIP_GUARD_RELEASE_ALPHA * (target - limiterSmoothedGain)
                }
                limiterSmoothedGain
            } else {
                1f
            }

            for (ch in 0 until channels) {
                var sample = tempFrame[ch] * limGain
                if (declickEnabled) {
                    val delta = sample - prevOutput[ch]
                    if (delta > MAX_SLEW_DELTA) sample = prevOutput[ch] + MAX_SLEW_DELTA
                    else if (delta < -MAX_SLEW_DELTA) sample = prevOutput[ch] - MAX_SLEW_DELTA
                }
                prevOutput[ch] = sample
                putSample16(data, pos + ch * 2, sample.roundToInt())
            }
            pos += frameBytes
        }
    }

    private val tempFrame = FloatArray(channels)

    /** Smooth trailing decay from the final sample to zero so playback ends click-free. */
    fun generateTrailingFade(): ByteArray? {
        if (!declickEnabled) return null
        val active = prevOutput.any { abs(it) >= 16f }
        if (!active) return null
        val frameBytes = channels * 2
        val tail = ByteArray(DECLICK_FADE_FRAMES * frameBytes)
        for (f in 0 until DECLICK_FADE_FRAMES) {
            val factor = FADE_OUT_RAMP[f]
            for (ch in 0 until channels) {
                putSample16(tail, f * frameBytes + ch * 2, (prevOutput[ch] * factor).roundToInt())
            }
        }
        prevOutput.fill(0f)
        return tail
    }

    companion object {
        private const val PRESENCE_LOW_HZ = 2500.0
        private const val PRESENCE_HIGH_HZ = 5000.0
        private const val PRESENCE_DRIVE = 5f
        private const val LEVEL_REF_RATE_HZ = 44100
        private const val LEVEL_ENV_ALPHA_REF = 0.0005f
        private const val GAIN_SMOOTH_ALPHA_REF = 0.0003f
        private const val LEVELER_TARGET_LEVEL = 9000f
        private const val LEVELER_MIN_GAIN = 0.85f
        private const val CLIP_GUARD_THRESHOLD = 30000f
        private const val CLIP_GUARD_RELEASE_ALPHA = 0.01f
        private const val MAX_SLEW_DELTA = 28000f
        const val DECLICK_FADE_FRAMES = 64

        private val FADE_IN_RAMP = FloatArray(DECLICK_FADE_FRAMES) { i ->
            0.5f * (1f - cos(PI * i / (DECLICK_FADE_FRAMES - 1)).toFloat())
        }
        private val FADE_OUT_RAMP = FloatArray(DECLICK_FADE_FRAMES) { i ->
            1f - FADE_IN_RAMP[i]
        }

        private fun onePoleAlpha(cornerHz: Double, sampleRateHz: Int): Float =
            (1.0 - exp(-2.0 * PI * cornerHz / sampleRateHz)).toFloat()

        private fun onePoleHighpassPole(cornerHz: Double, sampleRateHz: Int): Float =
            exp(-2.0 * PI * cornerHz / sampleRateHz).toFloat()

        private fun scaledAlpha(refAlpha: Float, sampleRateHz: Int): Float =
            (refAlpha * LEVEL_REF_RATE_HZ / sampleRateHz).coerceIn(0f, 1f)

        fun fastTanh(x: Float): Float {
            if (x <= -6f) return -1f
            if (x >= 6f) return 1f
            val x2 = x * x
            val x4 = x2 * x2
            val x6 = x4 * x2
            return (x * (135135f + 17325f * x2 + 378f * x4 + x6)) /
                (135135f + 62370f * x2 + 3150f * x4 + 28f * x6)
        }

        fun getSample16(data: ByteArray, offset: Int): Short =
            (((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)).toShort()

        fun putSample16(data: ByteArray, offset: Int, sample: Int) {
            val clamped = sample.coerceIn(-32768, 32767)
            data[offset] = (clamped and 0xFF).toByte()
            data[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
    }
}
