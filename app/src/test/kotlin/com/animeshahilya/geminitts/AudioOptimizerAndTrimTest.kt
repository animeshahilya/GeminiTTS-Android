package com.animeshahilya.geminitts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import com.animeshahilya.geminitts.audio.AudioOptimizer
import com.animeshahilya.geminitts.audio.ClarityPreset
import com.animeshahilya.geminitts.audio.LeadingSilenceTrimmer
import com.animeshahilya.geminitts.audio.PcmProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioOptimizerAndTrimTest {

    private fun pcmTone(samples: Int, amp: Int = 8000, rate: Int = 24_000): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Non-zero phase at i=0 so the very first sample is above silence threshold.
            val s = (sin(2 * PI * 400 * (i + 1) / rate) * amp).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun volumeBoostWithLimiterNeverWraps() {
        val pcm = pcmTone(2400, amp = 28_000)
        val opt = AudioOptimizer(
            sampleRateHz = 24_000,
            channels = 1,
            volumeScale = 2.0f,
            preset = ClarityPreset.OUTDOOR,
            levelerEnabled = true,
            declickEnabled = true
        )
        opt.process(pcm)
        for (i in 0 until pcm.size step 2) {
            val s = AudioOptimizer.getSample16(pcm, i).toInt()
            assertTrue("sample $s exceeded bounds", s in -32768..32767)
        }
        assertNotNull(opt.generateTrailingFade())
    }

    @Test fun leadingSilenceTrimmerStripsDeadAirAndKeepsPreRoll() {
        val rate = 24_000
        val silenceSamples = rate * 150 / 1000 // 150 ms silence
        val tone = pcmTone(rate * 50 / 1000, amp = 6000, rate = rate)
        val combined = ByteArray(silenceSamples * 2 + tone.size)
        System.arraycopy(tone, 0, combined, silenceSamples * 2, tone.size)

        val trimmer = LeadingSilenceTrimmer(rate, 1)
        val out = trimmer.filter(combined, combined.size)
        assertTrue(trimmer.released)
        val expectedPreRoll = (rate * LeadingSilenceTrimmer.PRE_ROLL_MS / 1000) * 2
        assertEquals(expectedPreRoll + tone.size, out.size)
    }

    @Test fun trimClipStripsBothLeadAndTailSilence() {
        val rate = 24_000
        val lead = ByteArray(rate / 10 * 2) // 100 ms silence
        val mid = pcmTone(rate / 10, amp = 5000, rate = rate)
        val tail = ByteArray(rate / 10 * 2) // 100 ms silence
        val all = lead + mid + tail

        val trimmed = LeadingSilenceTrimmer.trimClip(all, rate, 1)
        assertTrue("Expected trimmed (${trimmed.size}) < original (${all.size})", trimmed.size < all.size)
        assertTrue(trimmed.size >= mid.size)
    }

    @Test fun passthroughWithOptimizerDoesNotMutateCachedInput() {
        val pcm = pcmTone(2400, amp = 6000)
        val snapshot = pcm.copyOf()
        var emittedBytes = 0
        val cb = object : SynthesisCallback {
            override fun getMaxBufferSize() = 4096
            override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int) = 0
            override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
                emittedBytes += length
                return 0
            }
            override fun done() = 0
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted() = true
            override fun hasFinished() = false
            override fun rangeStart(markerFrame: Int, start: Int, end: Int) {}
        }
        val proc = PcmProcessor(
            callback = cb,
            isCancelled = { false },
            volume = 1.5f,
            clarity = ClarityPreset.CLEAR,
            leveler = true,
            trimLeadingSilence = true
        )
        proc.start(24_000, 1)
        proc.pcm(pcm)
        proc.finish()
        assertArrayEquals(snapshot, pcm)
        assertTrue(emittedBytes > 0)
        // Verify fade-out didn't exceed int16 bounds
        assertTrue(abs(AudioOptimizer.getSample16(snapshot, 100).toInt()) <= 6001)
    }

    @Test fun resetLeadingTrimStripsSilenceOnSubsequentPieces() {
        val rate = 24_000
        val leadSilence = ByteArray(rate * 150 / 1000 * 2) // 150 ms
        val piece = leadSilence + pcmTone(rate * 40 / 1000, amp = 6000, rate = rate)
        var emitted = 0
        val cb = object : SynthesisCallback {
            override fun getMaxBufferSize() = 4096
            override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int) = 0
            override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
                emitted += length
                return 0
            }
            override fun done() = 0
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted() = true
            override fun hasFinished() = false
            override fun rangeStart(markerFrame: Int, start: Int, end: Int) {}
        }
        val proc = PcmProcessor(cb, { false }, trimLeadingSilence = true)
        proc.start(rate, 1)
        proc.pcm(piece)
        val afterFirst = emitted
        proc.resetLeadingTrim()
        proc.pcm(piece)
        val secondDelta = emitted - afterFirst
        assertTrue("Second piece ($secondDelta bytes) should also be trimmed (< ${piece.size})", secondDelta < piece.size)
        proc.finish()
    }
}
