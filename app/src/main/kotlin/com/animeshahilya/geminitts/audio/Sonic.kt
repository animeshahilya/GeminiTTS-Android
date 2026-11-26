package com.animeshahilya.geminitts.audio

import kotlin.math.abs
import kotlin.math.min

/**
 * Kotlin port of Bill Cox's Sonic: pitch-preserving speed change and pitch shift for 16-bit PCM.
 * The GenAI voices only speak at 1x, and screen reader users often listen at 1.5x to 3x.
 */
class Sonic(
    val inputSampleRateHz: Int,
    val numChannels: Int
) {
    companion object {
        private const val SONIC_MIN_PITCH = 65
        private const val SONIC_MAX_PITCH = 400
        private const val SONIC_AMDF_FREQ = 4000
    }

    private val minPeriod: Int = inputSampleRateHz / SONIC_MAX_PITCH
    private val maxPeriod: Int = inputSampleRateHz / SONIC_MIN_PITCH
    private val maxRequired: Int = 2 * maxPeriod

    private var inputBuffer: ShortArray = ShortArray(maxRequired * numChannels)
    private var outputBuffer: ShortArray = ShortArray(maxRequired * numChannels)
    private var pitchBuffer: ShortArray = ShortArray(maxRequired * numChannels)
    private var downSampleBuffer: ShortArray = ShortArray(maxRequired)

    var speed: Float = 1.0f
    var pitch: Float = 1.0f

    private var numInputSamples: Int = 0
    private var numOutputSamples: Int = 0
    private var numPitchSamples: Int = 0

    private var minDiff: Int = 0
    private var maxDiff: Int = 0
    private var prevPeriod: Int = 0
    private var prevMinDiff: Int = 0
    private var remainingInputToCopy: Int = 0

    fun samplesAvailable(): Int = numOutputSamples

    /**
     * Enlarge input buffer if needed to accommodate [numSamples] more samples per channel.
     */
    private fun enlargeInputBufferIfNeeded(numSamples: Int) {
        val currentCapacity = inputBuffer.size / numChannels
        if (numInputSamples + numSamples > currentCapacity) {
            val newCapacity = currentCapacity + (currentCapacity shr 1) + numSamples
            val newBuffer = ShortArray(newCapacity * numChannels)
            System.arraycopy(inputBuffer, 0, newBuffer, 0, numInputSamples * numChannels)
            inputBuffer = newBuffer
        }
    }

    /**
     * Enlarge output buffer if needed to accommodate [numSamples] more samples per channel.
     */
    private fun enlargeOutputBufferIfNeeded(numSamples: Int) {
        val currentCapacity = outputBuffer.size / numChannels
        if (numOutputSamples + numSamples > currentCapacity) {
            val newCapacity = currentCapacity + (currentCapacity shr 1) + numSamples
            val newBuffer = ShortArray(newCapacity * numChannels)
            System.arraycopy(outputBuffer, 0, newBuffer, 0, numOutputSamples * numChannels)
            outputBuffer = newBuffer
        }
    }

    /**
     * Remove [samples] samples that have been processed from the front of the input buffer.
     */
    private fun removeProcessedInputSamples(samples: Int) {
        val remaining = numInputSamples - samples
        if (remaining > 0) {
            System.arraycopy(inputBuffer, samples * numChannels, inputBuffer, 0, remaining * numChannels)
        }
        numInputSamples = remaining
    }

    /**
     * Copy [numSamples] from [samples] starting at [position] into the output buffer.
     */
    private fun copyToOutput(samples: ShortArray, position: Int, numSamples: Int) {
        enlargeOutputBufferIfNeeded(numSamples)
        System.arraycopy(
            samples,
            position * numChannels,
            outputBuffer,
            numOutputSamples * numChannels,
            numSamples * numChannels
        )
        numOutputSamples += numSamples
    }

    /**
     * Copy input samples directly to output when remainingInputToCopy > 0.
     */
    private fun copyInputToOutput(position: Int): Int {
        val samplesToCopy = if (remainingInputToCopy > maxRequired) maxRequired else remainingInputToCopy
        copyToOutput(inputBuffer, position, samplesToCopy)
        remainingInputToCopy -= samplesToCopy
        return samplesToCopy
    }

    /**
     * Downsample multi-channel or single-channel audio into the downsample buffer for fast pitch estimation.
     */
    private fun downSampleInput(samples: ShortArray, position: Int, skip: Int) {
        val samplesPerValue = skip * numChannels
        val count = maxRequired / skip
        val startSample = position * numChannels

        for (i in 0 until count) {
            var value = 0
            for (j in 0 until samplesPerValue) {
                value += samples[startSample + i * samplesPerValue + j].toInt()
            }
            value /= samplesPerValue
            downSampleBuffer[i] = value.toShort()
        }
    }

    /**
     * Search within [minPeriod] and [maxPeriod] to find the optimal pitch period.
     */
    private fun findPitchPeriodInRange(samples: ShortArray, position: Int, minPeriod: Int, maxPeriod: Int): Int {
        val startSample = position * numChannels
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiffVal = 1
        var maxDiffVal = 0

        for (period in minPeriod..maxPeriod) {
            // Never read past the valid input: the longest candidate reads
            // [startSample, startSample + 2 * period).
            if (startSample + period * 2 > samples.size) break
            var diff = 0
            for (i in 0 until period) {
                val s1 = samples[startSample + i].toInt()
                val s2 = samples[startSample + period + i].toInt()
                diff += if (s1 < s2) (s2 - s1) else (s1 - s2)
            }

            val diffLong = diff.toLong()
            val periodLong = period.toLong()

            // Compare: diff / period < minDiff / bestPeriod => diff * bestPeriod < minDiff * period
            if (bestPeriod.toLong() * diffLong < minDiffVal.toLong() * periodLong) {
                minDiffVal = diff
                bestPeriod = period
            }
            // Compare: diff / period > maxDiff / worstPeriod => diff * worstPeriod > maxDiff * period
            if (worstPeriod.toLong() * diffLong > maxDiffVal.toLong() * periodLong) {
                maxDiffVal = diff
                worstPeriod = period
            }
        }

        minDiff = if (bestPeriod != 0) minDiffVal / bestPeriod else 0
        maxDiff = if (worstPeriod != 0) maxDiffVal / worstPeriod else 0
        return bestPeriod
    }

    private fun prevPeriodBetter(minDiff: Int, maxDiff: Int, preferNew: Boolean): Boolean {
        if (minDiff == 0 || prevPeriod == 0) return false
        if (preferNew) {
            if (maxDiff > minDiff * 3) return false
            if (minDiff * 2 <= prevMinDiff * 3) return false
        } else {
            if (minDiff <= prevMinDiff) return false
        }
        return true
    }

    /**
     * Find the pitch period using hierarchical downsampling for speed.
     */
    private fun findPitchPeriod(samples: ShortArray, position: Int, preferNew: Boolean): Int {
        var skip = if (inputSampleRateHz > SONIC_AMDF_FREQ) inputSampleRateHz / SONIC_AMDF_FREQ else 1
        val period: Int

        if (numChannels == 1 && skip == 1) {
            period = findPitchPeriodInRange(samples, position, minPeriod, maxPeriod)
        } else {
            downSampleInput(samples, position, skip)
            var p = findPitchPeriodInRange(downSampleBuffer, 0, minPeriod / skip, maxPeriod / skip)
            if (skip != 1) {
                p *= skip
                var minP = p - (skip shl 2)
                var maxP = p + (skip shl 2)
                if (minP < minPeriod) minP = minPeriod
                if (maxP > maxPeriod) maxP = maxPeriod

                if (numChannels == 1) {
                    period = findPitchPeriodInRange(samples, position, minP, maxP)
                } else {
                    downSampleInput(samples, position, 1)
                    period = findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP)
                }
            } else {
                period = p
            }
        }

        val bestPeriod = if (prevPeriodBetter(minDiff, maxDiff, preferNew)) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return bestPeriod
    }

    /**
     * Linearly interpolates two overlapping pitch periods to blend them smoothly without clicks.
     */
    private fun overlapAdd(
        numSamples: Int,
        numChannels: Int,
        out: ShortArray,
        outPos: Int,
        rampDown: ShortArray,
        rampDownPos: Int,
        rampUp: ShortArray,
        rampUpPos: Int
    ) {
        if (numSamples <= 0) return
        for (ch in 0 until numChannels) {
            var o = outPos * numChannels + ch
            var u = rampUpPos * numChannels + ch
            var d = rampDownPos * numChannels + ch

            for (t in 0 until numSamples) {
                val downWeight = rampDown[d].toInt() * (numSamples - t)
                val upWeight = rampUp[u].toInt() * t
                out[o] = ((downWeight + upWeight) / numSamples).toShort()
                o += numChannels
                d += numChannels
                u += numChannels
            }
        }
    }

    /**
     * Skip pitch period when speed > 1.0f (accelerating speech).
     */
    private fun skipPitchPeriod(samples: ShortArray, position: Int, speed: Float, period: Int): Int {
        // As in Bill Cox's sonic.c: at 2x and above, drop part of a period; below 2x, blend one period
        // and copy the rest straight through. (The branches were swapped, which skipped past the input.)
        val newSamples: Int
        if (speed >= 2.0f) {
            newSamples = (period / (speed - 1.0f)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = ((period * (2.0f - speed)) / (speed - 1.0f)).toInt()
        }
        enlargeOutputBufferIfNeeded(newSamples)
        overlapAdd(
            newSamples,
            numChannels,
            outputBuffer,
            numOutputSamples,
            samples,
            position,
            samples,
            position + period
        )
        numOutputSamples += newSamples
        return newSamples
    }

    /**
     * Insert pitch period when speed < 1.0f (slowing down speech).
     */
    private fun insertPitchPeriod(samples: ShortArray, position: Int, speed: Float, period: Int): Int {
        val newSamples: Int
        if (speed < 0.5f) {
            newSamples = ((period * speed) / (1.0f - speed)).toInt()
        } else {
            remainingInputToCopy = maxOf(0, ((period * (2.0f * speed - 1.0f)) / (1.0f - speed)).toInt())
            newSamples = period
        }
        enlargeOutputBufferIfNeeded(period + newSamples)
        System.arraycopy(
            samples,
            position * numChannels,
            outputBuffer,
            numOutputSamples * numChannels,
            period * numChannels
        )
        overlapAdd(
            newSamples,
            numChannels,
            outputBuffer,
            numOutputSamples + period,
            samples,
            position + period,
            samples,
            position
        )
        numOutputSamples += period + newSamples
        return newSamples
    }

    /**
     * Modifies the playback speed of the audio stream using PICOLA / AMDF period matching.
     */
    private fun changeSpeed(speed: Float) {
        if (numInputSamples < maxRequired) return

        var position = 0
        do {
            if (remainingInputToCopy > 0) {
                position += copyInputToOutput(position)
            } else {
                val period = findPitchPeriod(inputBuffer, position, true)
                if (period <= 0) {
                    // No usable pitch period: emit one sample to guarantee progress.
                    copyToOutput(inputBuffer, position, 1)
                    position += 1
                } else {
                    position += if (speed > 1.0f) {
                        period + skipPitchPeriod(inputBuffer, position, speed, period)
                    } else {
                        insertPitchPeriod(inputBuffer, position, speed, period)
                    }
                }
            }
        } while (position + maxRequired <= numInputSamples)

        removeProcessedInputSamples(position)
    }

    private fun processStream() {
        val firstNew = numOutputSamples
        val s = speed / pitch
        if (abs(s - 1.0f) > 0.0001f) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, numInputSamples)
            numInputSamples = 0
        }
        // Pitch: the stretch above used speed/pitch, so resampling by pitch restores the length
        // while raising or lowering the voice (sonic.c's adjustRate step).
        if (abs(pitch - 1.0f) > 0.0001f) resample(firstNew, pitch)
    }

    private var resamplePos = 0.0

    /** Streaming linear-interpolation resample of output samples from [firstNew] on, by [factor]. */
    private fun resample(firstNew: Int, factor: Float) {
        val added = numOutputSamples - firstNew
        if (added > 0) {
            if ((numPitchSamples + added) * numChannels > pitchBuffer.size) {
                pitchBuffer = pitchBuffer.copyOf((numPitchSamples + added) * numChannels * 2)
            }
            System.arraycopy(outputBuffer, firstNew * numChannels, pitchBuffer, numPitchSamples * numChannels, added * numChannels)
            numPitchSamples += added
            numOutputSamples = firstNew
        }
        val produce = ((numPitchSamples - 1 - resamplePos) / factor).toInt().coerceAtLeast(0) + 1
        enlargeOutputBufferIfNeeded(produce)
        while (resamplePos + 1 < numPitchSamples) {
            val i = resamplePos.toInt()
            val frac = resamplePos - i
            for (ch in 0 until numChannels) {
                val a = pitchBuffer[i * numChannels + ch]
                val b = pitchBuffer[(i + 1) * numChannels + ch]
                outputBuffer[numOutputSamples * numChannels + ch] = (a + (b - a) * frac).toInt().toShort()
            }
            numOutputSamples++
            resamplePos += factor
        }
        val consumed = minOf(resamplePos.toInt(), numPitchSamples)
        if (consumed > 0) {
            System.arraycopy(pitchBuffer, consumed * numChannels, pitchBuffer, 0, (numPitchSamples - consumed) * numChannels)
            numPitchSamples -= consumed
            resamplePos -= consumed
        }
    }

    /**
     * Flushes any remaining samples through the stream at the end of utterance.
     */
    fun flushStream() {
        val remaining = numInputSamples
        // Unread input plays at 1/speed; audio waiting in the resampler at 1/pitch.
        val expectedOutput = numOutputSamples +
            ((remaining.toFloat() / speed) + ((numPitchSamples - resamplePos) / pitch).toFloat() + 0.5f).toInt()
        val extraRequired = maxRequired * 2

        enlargeInputBufferIfNeeded(remaining + extraRequired)
        // Pad extra required samples with 0 (silence)
        for (i in 0 until extraRequired * numChannels) {
            inputBuffer[remaining * numChannels + i] = 0
        }
        numInputSamples += extraRequired
        processStream()

        if (numOutputSamples > expectedOutput) {
            numOutputSamples = expectedOutput
        }
        numInputSamples = 0
        remainingInputToCopy = 0
    }

    /**
     * Write raw byte array (16-bit PCM little endian) into the processing stream starting at [offset].
     */
    fun writeBytesToStream(bytes: ByteArray, length: Int, offset: Int = 0) {
        if (length <= 0) return
        val bytesPerSample = numChannels * 2
        val samplesToAdd = length / bytesPerSample
        if (samplesToAdd <= 0) return
        val validLength = samplesToAdd * bytesPerSample
        enlargeInputBufferIfNeeded(samplesToAdd)

        var sampleIndex = numInputSamples * numChannels
        var byteIndex = offset
        val limit = offset + validLength
        while (byteIndex < limit) {
            val low = bytes[byteIndex].toInt() and 0xFF
            val high = bytes[byteIndex + 1].toInt() shl 8
            inputBuffer[sampleIndex] = (low or high).toShort()
            sampleIndex++
            byteIndex += 2
        }
        numInputSamples += samplesToAdd
        processStream()
    }

    /**
     * Read processed byte array (16-bit PCM little endian) out of the stream.
     * Returns the number of bytes written to [output].
     */
    fun readBytesFromStream(output: ByteArray, maxBytes: Int): Int {
        if (numOutputSamples == 0 || maxBytes <= 0) return 0
        val bytesPerSample = numChannels * 2
        val maxSamplesToRead = maxBytes / bytesPerSample
        val samplesToRead = min(numOutputSamples, maxSamplesToRead)
        val totalValues = samplesToRead * numChannels

        for (i in 0 until totalValues) {
            val sample = outputBuffer[i].toInt()
            val byteOffset = i * 2
            output[byteOffset] = (sample and 0xFF).toByte()
            output[byteOffset + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val remainingSamples = numOutputSamples - samplesToRead
        if (remainingSamples > 0) {
            System.arraycopy(
                outputBuffer,
                samplesToRead * numChannels,
                outputBuffer,
                0,
                remainingSamples * numChannels
            )
        }
        numOutputSamples = remainingSamples
        return samplesToRead * bytesPerSample
    }
}
