package com.animeshahilya.geminitts

import com.animeshahilya.geminitts.data.VoiceEntry
import com.animeshahilya.geminitts.data.pickVoice
import com.animeshahilya.geminitts.data.voiceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VoicesTest {

    private fun v(name: String, tag: String) = VoiceEntry(name, Locale.forLanguageTag(tag))

    private val voices = listOf(
        v("en-us-x-star02", "en-US"), v("en-us-x-star04", "en-US"),
        v("en-gb-x-star04", "en-GB"),
        v("hi-in-x-star05", "hi-IN"), v("hi-in-x-star02", "hi-IN"),
        v("id-id-x-star02", "id-ID"),
        v("ar-xa-x-star02", "ar-XA")
    )

    @Test fun idIsTheSameAcrossLanguages() {
        assertEquals("star02", voiceId("en-us-x-star02"))
        assertEquals("star02", voiceId("en-us-x-star02-local"))
        assertEquals("star02", voiceId("star02"))
    }

    @Test fun chosenVoiceInRequestedLanguage() {
        assertEquals("hi-in-x-star02", pickVoice(voices, "star02", "hin", "IND")?.name)
        assertEquals("en-us-x-star04", pickVoice(voices, "star04", "eng", "USA")?.name)
    }

    @Test fun exactCountryBeatsSameLanguage() {
        assertEquals("en-gb-x-star04", pickVoice(voices, "star04", "eng", "GBR")?.name)
    }

    @Test fun otherCountryFallsBackToSameLanguage() {
        // No en-IN voices: an Indian English request still gets English, the chosen voice first.
        assertEquals("en-us-x-star02", pickVoice(voices, "star02", "eng", "IND")?.name)
    }

    @Test fun missingChosenVoiceUsesLanguagesFirst() {
        assertEquals("en-gb-x-star04", pickVoice(voices, "star02", "eng", "GBR")?.name)
    }

    @Test fun twoLetterAndLegacyCodesMatch() {
        assertEquals("id-id-x-star02", pickVoice(voices, "star02", "ind", "IDN")?.name)
        assertEquals("hi-in-x-star02", pickVoice(voices, "star02", "hi", "IN")?.name)
        assertEquals("ar-xa-x-star02", pickVoice(voices, "star02", "ara", "")?.name)
    }

    @Test fun noChoiceMeansStar02WhateverTheListOrder() {
        // ML Kit may list star16 first; the app shows star02 as the default, so the engine must agree.
        val reversed = listOf(v("en-us-x-star16", "en-US"), v("en-us-x-star04", "en-US"), v("en-us-x-star02", "en-US"))
        assertEquals("en-us-x-star02", pickVoice(reversed, null, "eng", "USA")?.name)
    }

    @Test fun unknownLanguageGivesNothing() {
        assertNull(pickVoice(voices, "star02", "ben", "IND"))
        assertNull(pickVoice(voices, "star02", null, null))
    }
}
