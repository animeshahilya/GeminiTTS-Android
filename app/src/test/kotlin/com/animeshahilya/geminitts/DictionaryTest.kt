package com.animeshahilya.geminitts

import com.animeshahilya.geminitts.data.Dictionary
import com.animeshahilya.geminitts.data.DictionaryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DictionaryTest {

    @Test fun wholeWordsOnly() {
        val d = Dictionary(listOf(DictionaryRule("GIF", "jif")))
        assertEquals("a jif, GIFs stay", d.apply("a GIF, GIFs stay"))
    }

    @Test fun symbolRulesDoNotMatchInsideWords() {
        val d = Dictionary(listOf(DictionaryRule("w/", "with"), DictionaryRule("w/o", "without")))
        assertEquals("how/why", d.apply("how/why"))
        assertEquals("tea without sugar, with milk", d.apply("tea w/o sugar, w/ milk"))
    }

    @Test fun plainReplacementIsLiteral() {
        val d = Dictionary(listOf(DictionaryRule("price", "\$1")))
        assertEquals("the \$1", d.apply("the price"))
    }

    @Test fun regexGroups() {
        val d = Dictionary(listOf(DictionaryRule("(\\d+)k", "\$1 thousand", isRegex = true)))
        assertEquals("5 thousand steps", d.apply("5k steps"))
    }

    @Test fun badRegexIsSkipped() {
        val d = Dictionary(listOf(DictionaryRule("(", "x", isRegex = true), DictionaryRule("a", "b")))
        assertEquals("b", d.apply("a"))
    }

    @Test fun unchangedTextIsReturnedAsIs() {
        val text = "nothing here"
        assertSame(text, Dictionary(listOf(DictionaryRule("zzz", "y"))).apply(text))
    }
}
