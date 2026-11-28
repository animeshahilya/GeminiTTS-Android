package com.animeshahilya.geminitts

import android.text.Spanned
import android.text.style.TtsSpan
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.splitForPauses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStyleTest {

    private val style = ReadingStyle.custom(1f, 1f, sentencePauseMs = 300, paragraphPauseMs = 800)

    private fun split(text: String) = splitForPauses(text, style).map { (t, p) -> t.toString() to p }

    @Test fun noPausesMeansOnePiece() {
        val text = "One. Two."
        val pieces = splitForPauses(text, ReadingStyle.PRESETS.first())
        assertEquals(1, pieces.size)
        assertSame(text, pieces[0].first)
    }

    @Test fun sentencesAndParagraphs() {
        assertEquals(
            listOf("It rained." to 300, "We stayed in!" to 800, "Next day was sunny." to 0),
            split("It rained. We stayed in!\n\nNext day was sunny.")
        )
    }

    @Test fun abbreviationsAreNotSentenceEnds() {
        assertEquals(
            listOf("Dr. Rao met Mrs. Lee, e.g. at noon." to 300, "Then they left." to 0),
            split("Dr. Rao met Mrs. Lee, e.g. at noon. Then they left.")
        )
    }

    @Test fun numbersAndOtherScripts() {
        assertEquals(listOf("Version 2.5 is out." to 300, "Try it." to 0), split("Version 2.5 is out. Try it."))
        assertEquals(listOf("यह पहला है।" to 300, "यह दूसरा है।" to 0), split("यह पहला है। यह दूसरा है।"))
    }

    @Test fun blankLinesKeepTheLongestPause() {
        assertEquals(listOf("Title" to 800, "Body." to 0), split("Title\n\n\nBody.  \n"))
    }

    @Test fun longTextChunksInStandardModeWithoutSplittingTtsSpans() {
        val sentence = "This is a moderately long sentence with enough words to force chunking. "
        val prefix = sentence.repeat(18) // ~1296 chars
        val num = "5551234567"
        val full = FakeSpanned.withTtsSpan(prefix + num + ". " + sentence.repeat(10), prefix.length, prefix.length + num.length)

        val chunks = splitForPauses(full, ReadingStyle.PRESETS.first(), maxChunkChars = 1400)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.first.length <= 1400 })
        val chunkWithPhone = chunks.firstOrNull { num in it.first.toString() }
        assertTrue(chunkWithPhone != null)
        val spannedChunk = chunkWithPhone!!.first as Spanned
        assertEquals(1, spannedChunk.getSpans(0, spannedChunk.length, TtsSpan::class.java).size)
    }

    @Test fun pauseSplitterNeverBreaksInsideTtsSpan() {
        val text = "Call 123. 456\n789 now. Next."
        val spanned = FakeSpanned.withTtsSpan(text, 5, 17)
        val pieces = splitForPauses(spanned, style)
        assertEquals(listOf("Call 123. 456\n789 now." to 300, "Next." to 0), pieces.map { it.first.toString() to it.second })
    }

    @Test fun lowercaseSingleLetterIsNotAbbreviation() {
        assertEquals(listOf("Option a." to 300, "Next." to 0), split("Option a. Next."))
    }
}
