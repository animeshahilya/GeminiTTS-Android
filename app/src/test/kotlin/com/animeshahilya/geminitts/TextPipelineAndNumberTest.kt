package com.animeshahilya.geminitts

import com.animeshahilya.geminitts.data.Dictionary
import com.animeshahilya.geminitts.data.DigitGroupingMode
import com.animeshahilya.geminitts.data.SpanEditor
import com.animeshahilya.geminitts.data.TextOptions
import com.animeshahilya.geminitts.data.TextPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPipelineAndNumberTest {

    private val emptyDict = Dictionary(emptyList())

    @Test fun normalizesFancyUnicodeAndControls() {
        val input = "𝐇𝐞𝐥𝐥𝐨\u200B ℍ𝕖𝕝𝕝𝕠 ２０２６"
        val out = TextPipeline.process(input, TextOptions(), emptyDict).toString()
        assertEquals("Hello Hello 2026", out)
    }

    @Test fun cleansMarkdownAndShortensUrls() {
        val opts = TextOptions(cleanMarkdown = true, shortenUrls = true)
        val out = TextPipeline.process(
            "## Title\n- [x] **Done** see [Docs](https://www.example.com/a/b?utm_source=1234567890)",
            opts,
            emptyDict
        ).toString()
        assertEquals("Title\ndone: Done see Docs", out)

        val rawUrl = TextPipeline.process(
            "Visit https://www.example.com/docs/api?token=abcdefghijkl",
            opts,
            emptyDict
        ).toString()
        assertEquals("Visit link example.com slash docs slash api with parameters", rawUrl)
    }

    @Test fun splitsCamelCaseAndTechWords() {
        val opts = TextOptions(splitCamelCase = true, normalizeTechWords = true)
        val out = TextPipeline.process("Use ChatGPT for getUserName and user_id via UPI", opts, emptyDict).toString()
        assertEquals("Use Chat G P T for get User Name and user id via U P I", out)
    }

    @Test fun readsCodesIndianNumbersAndDigitGroups() {
        val opts = TextOptions(
            readCodesDigits = true,
            preserveLeadingZeros = true,
            indianNumbering = true,
            digitGrouping = DigitGroupingMode.PAIRS_PAUSE,
            digitGroupMinLen = 6
        )
        assertEquals("Your OTP is 4 8 2 9 1 0", TextPipeline.process("Your OTP is 482910", opts, emptyDict).toString())
        assertEquals("Debited Rs 5000", TextPipeline.process("Debited Rs 5000", opts.copy(indianNumbering = false), emptyDict).toString())
        assertEquals("Code 0 0 7", TextPipeline.process("Code 007", opts, emptyDict).toString())
        assertEquals("1 lakh rupees", TextPipeline.process("₹1,00,000", opts, emptyDict).toString())
        assertEquals("Order 12, 34, 56, 78", TextPipeline.process("Order 12345678", opts, emptyDict).toString())
    }

    @Test fun collapsesRepeatedEmojisAndSilencesSymbolsSafely() {
        val opts = TextOptions(collapseRepeats = true, silenceSymbols = true)
        assertEquals("Haha 😂, 4", TextPipeline.process("Haha 😂😂😂😂", opts, emptyDict).toString())
        assertTrue(TextPipeline.isSingleCharacter("❤️"))
        assertTrue(TextPipeline.isSingleCharacter("e\u0301"))
        assertEquals("temp -5 range 10-20 hello world mail user@test.com",
            TextPipeline.process("temp -5 range 10-20 **hello***world** mail user@test.com", opts, emptyDict).toString())
    }

    @Test fun preservesTtsSpanRangesWhenFormattingNumbers() {
        val s = FakeSpanned.withTtsSpan("OTP 482910 call 5551234", 16, 23)
        val editor = SpanEditor(s)
        assertFalse(editor.hasTtsSpanOverlap(4, 10))
        assertTrue(editor.hasTtsSpanOverlap(16, 23))
    }

    @Test fun regressionsMoneyDatesMathUrlsAndCards() {
        val full = TextOptions(
            cleanMarkdown = true,
            silenceSymbols = true,
            readCodesDigits = true,
            preserveLeadingZeros = true,
            indianNumbering = true,
            digitGrouping = DigitGroupingMode.SINGLE,
            digitGroupMinLen = 5,
            splitCamelCase = true,
            shortenUrls = true
        )
        // Indian and dollar currency values are not split digit-by-digit by formatDigitGrouping.
        assertEquals("Paid 50000 rupees", TextPipeline.process("Paid ₹50000", full, emptyDict).toString())
        assertEquals("Cost $10000", TextPipeline.process("Cost $10000", full, emptyDict).toString())
        assertEquals("PIN $ 4500", TextPipeline.process("PIN $ 4500", full, emptyDict).toString())

        // Hyphenated dates keep leading zeros intact instead of "0 1-02-2025".
        assertEquals("Date 01-02-2025", TextPipeline.process("Date 01-02-2025", full, emptyDict).toString())

        // Card ending doesn't duplicate "ending", math stays intact, "~-5" keeps "-5".
        assertEquals("Card ending 1 2 3 4", TextPipeline.process("Card ending XXXX1234", full, emptyDict).toString())
        assertEquals("2*3 + 4*5", TextPipeline.process("2*3 + 4*5", full, emptyDict).toString())
        assertEquals("-5", TextPipeline.process("~-5", full, emptyDict).toString())

        // URLs are kept intact through CamelCase & digit grouping before shortening.
        assertEquals(
            "See link bank.in slash userProfile slash 123456",
            TextPipeline.process("See https://bank.in/userProfile/123456", full, emptyDict).toString()
        )

        // Long bank SMS OTP lookback (~60 chars) matches inside sentence, +91 phone prefix stays intact,
        // and cross-sentence codes are not triggered.
        assertEquals(
            "Your OTP for 125000 rupees order placed at Merchant is 8 4 9 2 0 1",
            TextPipeline.process("Your OTP for Rs 125000 order placed at Merchant is 849201", full, emptyDict).toString()
        )
        assertEquals(
            "Use OTP 4 8 2 9 1 0 or call +919876543210",
            TextPipeline.process("Use OTP 482910 or call +919876543210", full.copy(digitGrouping = DigitGroupingMode.OFF), emptyDict).toString()
        )
        // Devanagari word ending in combining matra ("कोडी") must not falsely trigger "कोड".
        assertEquals(
            "कोडी 482910",
            TextPipeline.process("कोडी 482910", full.copy(digitGrouping = DigitGroupingMode.OFF), emptyDict, "hi-IN").toString()
        )

        // Query-only URLs (no '/' before '?'), ports, and surrounding parens stay intact and shorten cleanly.
        assertEquals(
            "Check link example.com with parameters.",
            TextPipeline.process("Check https://example.com?id=1234567890.", full, emptyDict).toString()
        )
        assertEquals(
            "Server link dev.example.com:8080 slash api",
            TextPipeline.process("Server http://dev.example.com:8080/api", full, emptyDict).toString()
        )
        assertEquals(
            "(See link example.com).",
            TextPipeline.process("(See https://example.com).", full, emptyDict).toString()
        )
        assertEquals("", TextPipeline.process("___", full, emptyDict).toString())

        // Hyphenated dates inside bank/transaction sentences aren't split; masked card with hyphen works.
        assertEquals(
            "Transaction of 500 rupees on 28-11-2026 for card ending 1 2 3 4",
            TextPipeline.process("Transaction of Rs 500 on 28-11-2026 for card XXXX-1234", full.copy(digitGrouping = DigitGroupingMode.OFF), emptyDict).toString()
        )

        // Hindi currency output is protected from digit grouping.
        assertEquals(
            "50000 रुपये",
            TextPipeline.process("₹50000", full, emptyDict, "hi-IN").toString()
        )
    }
}
