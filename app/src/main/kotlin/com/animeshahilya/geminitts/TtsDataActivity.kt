package com.animeshahilya.geminitts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.SampleTexts
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.ttsCode
import java.util.Locale

/** Answers the system's "which languages?" and "sample text" questions. */
class TtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val prefs = TtsPrefs(this)
                val allLocales = prefs.voices.map { it.locale }.distinct()
                val enabled = allLocales.filter { prefs.isLocaleEnabled(it) }.ifEmpty { allLocales }
                val locales = enabled.ifEmpty { listOf(GenAiVoices.DEFAULT_LOCALE) }
                val available = locales.map { it.ttsCode() }.distinct()
                setResult(
                    TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
                    Intent()
                        .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
                        .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
                )
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                // Settings passes the language as a 2-letter code ("hi"); the table uses ISO3 ("hin").
                val sample = SampleTexts.forLanguageTag(intent.getStringExtra("language").orEmpty())
                // Settings only uses the text when the result code is LANG_AVAILABLE, not RESULT_OK.
                setResult(
                    TextToSpeech.LANG_AVAILABLE,
                    Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
                )
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
