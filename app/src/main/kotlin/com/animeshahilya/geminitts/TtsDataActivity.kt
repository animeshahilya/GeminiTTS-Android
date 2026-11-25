package com.animeshahilya.geminitts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.iso3Language
import com.animeshahilya.geminitts.data.ttsCode
import java.util.Locale

/** Answers the system's "which languages?" and "sample text" questions. */
class TtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val locales = TtsPrefs(this).voices.map { it.locale }.ifEmpty { listOf(GenAiVoices.DEFAULT_LOCALE) }
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
                val lang = Locale.forLanguageTag(intent.getStringExtra("language").orEmpty()).iso3Language()
                // Settings only uses the text when the result code is LANG_AVAILABLE, not RESULT_OK.
                setResult(
                    TextToSpeech.LANG_AVAILABLE,
                    Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, SAMPLES[lang] ?: SAMPLES.getValue("eng"))
                )
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }

    private companion object {
        val SAMPLES = mapOf(
            "ara" to "هذا مثال على تحويل النص إلى كلام.",
            "dan" to "Dette er et eksempel på tale fra tekst.",
            "deu" to "Dies ist ein Beispiel für Sprachausgabe.",
            "eng" to "This is an example of speech synthesis.",
            "spa" to "Este es un ejemplo de síntesis de voz.",
            "fra" to "Ceci est un exemple de synthèse vocale.",
            "hin" to "यह वाक् संश्लेषण का एक उदाहरण है।",
            "ind" to "Ini adalah contoh sintesis suara.",
            "ita" to "Questo è un esempio di sintesi vocale.",
            "jpn" to "これは音声合成の例です。",
            "kor" to "음성 합성의 예입니다.",
            "nob" to "Dette er et eksempel på talesyntese.",
            "nor" to "Dette er et eksempel på talesyntese.",
            "nld" to "Dit is een voorbeeld van spraaksynthese.",
            "pol" to "To jest przykład syntezy mowy.",
            "por" to "Este é um exemplo de síntese de voz.",
            "rus" to "Это пример синтеза речи.",
            "swe" to "Det här är ett exempel på talsyntes.",
            "tur" to "Bu bir konuşma sentezi örneğidir."
        )
    }
}
