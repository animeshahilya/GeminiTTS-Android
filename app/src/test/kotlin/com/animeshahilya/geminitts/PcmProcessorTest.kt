package com.animeshahilya.geminitts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import com.animeshahilya.geminitts.audio.PcmProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import kotlin.math.PI
import kotlin.math.sin

class PcmProcessorTest {

    private class FakeCallback : SynthesisCallback {
        var bytes = 0
        var started = 0
        var done = 0
        override fun getMaxBufferSize() = 4096
        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, audioFormat)
            started++
            return 0
        }
        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            assertTrue(length <= 4096)
            bytes += length
            return 0
        }
        override fun done(): Int { done++; return 0 }
        override fun error() {}
        override fun error(errorCode: Int) {}
        override fun hasStarted() = started > 0
        override fun hasFinished() = done > 0
        override fun rangeStart(markerFrame: Int, start: Int, end: Int) {}
    }

    /** One second of a 200 Hz tone at 24 kHz, 16-bit mono. */
    private fun tone(): ByteArray {
        val out = ByteArray(48_000)
        for (i in 0 until 24_000) {
            val s = (sin(2 * PI * 200 * i / 24_000) * 8000).toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun run(speed: Float, chunk: Int = 3000, pitch: Float = 1f): FakeCallback {
        val cb = FakeCallback()
        val p = PcmProcessor(cb, { false }, speed = speed, pitch = pitch)
        p.start(24_000, 1)
        tone().toList().chunked(chunk).forEach { p.pcm(it.toByteArray()) }
        p.finish()
        assertEquals(1, cb.started)
        assertEquals(1, cb.done)
        return cb
    }

    @Test fun passthroughKeepsEveryByte() = assertEquals(48_000, run(1f).bytes)

    /** Every speed TalkBack offers, with odd chunk sizes like the engine sends (the 1.53x crash). */
    @Test fun speedsScaleLength() {
        for (speed in listOf(0.5f, 0.75f, 1.25f, 1.53f, 1.75f, 2f, 2.5f, 3f, 4f)) {
            for (chunk in listOf(482, 3000, 7107 * 2, 48_000)) {
                val ratio = run(speed, chunk).bytes / 48_000f
                val expected = 1f / speed
                assertTrue("speed $speed chunk $chunk ratio $ratio", ratio in expected * 0.9f..expected * 1.1f)
            }
        }
    }

    /** Collects the output so its frequency can be measured from zero crossings. */
    private fun frequencyOf(speed: Float, pitch: Float): Float {
        val out = java.io.ByteArrayOutputStream()
        val cb = object : SynthesisCallback by FakeCallback() {
            override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
                out.write(buffer, offset, length); return 0
            }
        }
        val p = PcmProcessor(cb, { false }, speed = speed, pitch = pitch)
        p.start(24_000, 1)
        tone().toList().chunked(1234).forEach { p.pcm(it.toByteArray()) }
        p.finish()
        val bytes = out.toByteArray()
        val samples = (0 until bytes.size / 2).map { (bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8) }
            .map { it.toShort().toInt() }
        // Skip the edges; count rising zero crossings in the middle.
        val mid = samples.subList(samples.size / 4, samples.size * 3 / 4)
        val rises = mid.zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
        return rises / (mid.size / 24_000f)
    }

    @Test fun speedKeepsPitchAndPitchShiftsIt() {
        assertTrue(frequencyOf(2f, 1f).let { it in 180f..220f })
        assertTrue(frequencyOf(1.5f, 1f).let { it in 180f..220f })
        val up = frequencyOf(1f, 1.5f)
        assertTrue("pitch 1.5 gave $up Hz", up in 270f..330f)
        val down = frequencyOf(1f, 0.75f)
        assertTrue("pitch 0.75 gave $down Hz", down in 135f..165f)
    }

    @Test fun pitchKeepsLength() {
        for (pitch in listOf(0.5f, 0.8f, 1.5f, 2f)) {
            val ratio = run(1f, 3000, pitch).bytes / 48_000f
            assertTrue("pitch $pitch ratio $ratio", ratio in 0.9f..1.1f)
        }
    }

    @Test fun shortWordAtHighSpeedIsNotLost() {
        // 50 ms never fills the 250 ms reserve; finish() must still play it.
        val cb = FakeCallback()
        val p = PcmProcessor(cb, { false }, speed = 2f)
        p.start(24_000, 1)
        p.pcm(tone().copyOf(2_400))
        p.finish()
        assertTrue(cb.bytes > 0)
    }

    @Test(expected = InterruptedIOException::class)
    fun cancelStops() {
        var cancelled = false
        val p = PcmProcessor(FakeCallback(), { cancelled }, speed = 1f)
        p.start(24_000, 1)
        p.pcm(tone())
        cancelled = true
        p.pcm(tone())
    }

    @Test fun finishWithoutAudioStillEndsUtterance() {
        val cb = FakeCallback()
        PcmProcessor(cb, { false }).finish()
        assertEquals(1, cb.started)
        assertEquals(1, cb.done)
    }
}
