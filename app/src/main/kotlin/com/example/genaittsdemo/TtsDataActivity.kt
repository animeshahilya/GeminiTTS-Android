package com.example.genaittsdemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TtsDataActivity provides standard Android TTS framework integration for:
 * 1. ACTION_CHECK_TTS_DATA: Returns available on-device voice datasets and status (CHECK_VOICE_DATA_PASS).
 * 2. ACTION_GET_SAMPLE_TEXT: Supplies localized preview text for Android System Settings ("Listen to an example").
 */
class TtsDataActivity : Activity() {

    companion object {
        private const val TAG = "TtsDataActivity"

        val AVAILABLE_VOICES = arrayListOf(
            "eng-USA",
            "eng-IND",
            "eng-GBR"
        )

        private val SAMPLE_TEXTS = mapOf(
            "en" to "This is an example of speech synthesis using Google Gemini on-device neural models.",
            "eng" to "This is an example of speech synthesis using Google Gemini on-device neural models.",
            "es" to "Este es un ejemplo de síntesis de voz con modelos neuronales integrados.",
            "spa" to "Este es un ejemplo de síntesis de voz con modelos neuronales integrados.",
            "fr" to "Ceci est un exemple de synthèse vocale avec des modèles neuronaux intégrés.",
            "fra" to "Ceci est un exemple de synthèse vocale avec des modèles neuronaux intégrés.",
            "de" to "Dies ist ein Beispiel für Sprachausgabe mit integrierten neuronalen Modellen.",
            "deu" to "Dies ist ein Beispiel für Sprachausgabe mit integrierten neuronalen Modellen.",
            "it" to "Questo è un esempio di sintesi vocale con modelli neurali integrati.",
            "ita" to "Questo è un esempio di sintesi vocale con modelli neurali integrati.",
            "ja" to "これはオンデバイスのニューラルモデルを使用した音声合成の例です。",
            "jpn" to "これはオンデバイスのニューラルモデルを使用した音声合成の例です。",
            "ko" to "이것은 온디바이스 신경망 모델을 사용한 음성 합성의 예입니다.",
            "kor" to "이것은 온디바이스 신경망 모델을 사용한 음성 합성의 예입니다.",
            "zh" to "这是使用端侧神经网络模型的语音合成示例。",
            "zho" to "这是使用端侧神经网络模型的语音合成示例。",
            "hi" to "यह ऑन-डिवाइस न्यूरल मॉडल का उपयोग करके वाक् संश्लेषण का एक उदाहरण है।",
            "hin" to "यह ऑन-डिवाइस न्यूरल मॉडल का उपयोग करके वाक् संश्लेषण का एक उदाहरण है।"
        )

        fun getSampleTextForLocale(language: String?): String {
            if (language == null) return SAMPLE_TEXTS["en"]!!
            val key = language.lowercase(Locale.ROOT).trim()
            return SAMPLE_TEXTS[key]
                ?: SAMPLE_TEXTS[key.take(2)]
                ?: SAMPLE_TEXTS["en"]!!
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        Log.i(TAG, "TtsDataActivity invoked with action: $action")

        when (action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val returnData = Intent().apply {
                    putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, AVAILABLE_VOICES)
                    putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
                    putStringArrayListExtra(TextToSpeech.Engine.EXTRA_VOICE_DATA_FILES, arrayListOf("gemini_voice_en_us.bin"))
                    putStringArrayListExtra(TextToSpeech.Engine.EXTRA_VOICE_DATA_FILES_INFO, arrayListOf("Google Gemini On-Device Neural Voice"))
                }
                setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
            }

            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                val language = intent.getStringExtra("language")
                    ?: intent.getStringExtra("locale")
                    ?: Locale.getDefault().language

                val sampleText = getSampleTextForLocale(language)
                val returnData = Intent().apply {
                    putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)
                }
                setResult(RESULT_OK, returnData)
            }

            else -> {
                Log.w(TAG, "Unhandled action: $action")
                setResult(RESULT_CANCELED)
            }
        }

        finish()
    }
}
