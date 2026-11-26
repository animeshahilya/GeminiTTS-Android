package com.animeshahilya.geminitts

import com.animeshahilya.geminitts.data.BackupRestore
import com.animeshahilya.geminitts.data.Dictionary
import com.animeshahilya.geminitts.data.DictionaryRule
import com.animeshahilya.geminitts.data.JsonCodec
import com.animeshahilya.geminitts.data.RecentSpeech
import com.animeshahilya.geminitts.data.SafeRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryAndBackupTest {

    @Test fun safeRegexRejectsEmptyMatchAndCatastrophicBacktracking() {
        assertNotNull(SafeRegex.validate(".*"))
        assertNotNull(SafeRegex.validate("a*"))
        assertNotNull(SafeRegex.validate("^"))
        assertNotNull(SafeRegex.validate("(a+)+b"))
        assertNotNull(SafeRegex.validate("\\w*\\w*\\w*!"))
        assertNull(SafeRegex.validate("(\\d+)k"))
        assertNull(SafeRegex.validate("colou?r"))
    }

    @Test fun singleCharacterAndLanguageScopedRules() {
        val d = Dictionary(
            listOf(
                DictionaryRule("s", "ess", isCharacter = true),
                DictionaryRule("क्", "क हलन्त", isCharacter = true),
                DictionaryRule("resume", "rezoomay", language = "en"),
                DictionaryRule("Cat", "feline", caseSensitive = true)
            )
        )
        assertEquals("ess", d.apply("s", "en-US").toString())
        assertEquals("say", d.apply("say", "en-US").toString())
        assertEquals("क हलन्त", d.apply("क्", "hi-IN").toString())
        assertEquals("rezoomay", d.apply("resume", "en-US").toString())
        assertEquals("resume", d.apply("resume", "fr-FR").toString())
        assertEquals("feline and cat", d.apply("Cat and cat", "en-US").toString())
    }

    @Test fun jsonCodecAndNvdaDicRoundTrip() {
        val raw = JsonCodec.stringify(mapOf("a" to 1, "b" to listOf("x\"y", true, null)))
        val parsed = JsonCodec.parseObject(raw)
        assertNotNull(parsed)
        assertEquals(1L, parsed!!["a"])

        val dic = """
            # comment
            GIF	jif	0	2
            (\d+)k	${'$'}1 thousand	0	1
            (\d+)m	\1 million	0	1
            foo	bar	1	0
        """.trimIndent()
        val rules = BackupRestore.parseNvdaDic(dic)
        assertEquals(4, rules.size)
        assertTrue(rules[0].wholeWord && !rules[0].caseSensitive)
        assertTrue(rules[1].isRegex)
        assertTrue(rules[2].isRegex)
        assertTrue(rules[3].caseSensitive && !rules[3].wholeWord)

        val dict = Dictionary(rules)
        assertEquals("5 million", dict.apply("5m", "en-US").toString())

        // TtsSpan ranges are protected from dictionary rewrites (on JVM only unedited Spanned avoids Android stub).
        val protectedOnly = FakeSpanned.withTtsSpan("5m", 0, 2)
        assertEquals("5m", dict.apply(protectedOnly, "en-US").toString())

        val mixed = FakeSpanned.withTtsSpan("GIF 5m", 4, 6)
        val ed = com.animeshahilya.geminitts.data.SpanEditor(mixed)
        org.junit.Assert.assertFalse(ed.hasTtsSpanOverlap(0, 3))
        assertTrue(ed.hasTtsSpanOverlap(4, 6))

        val exported = BackupRestore.formatNvdaDic(rules)
        val reimported = BackupRestore.parseNvdaDic(exported)
        assertEquals(rules, reimported)
    }

    @Test fun recentSpeechBeforeCutoffAndClearedCleanly() {
        RecentSpeech.clear()
        assertTrue(RecentSpeech.items(nowMs = 2000L, beforeMs = 1500L).isEmpty())
    }
}
