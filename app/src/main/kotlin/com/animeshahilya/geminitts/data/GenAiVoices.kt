package com.animeshahilya.geminitts.data

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.VoiceInfo
import com.google.mlkit.genai.texttospeech.speechSynthesizerOptions
import java.util.Locale

/**
 * ML Kit is only used to find out which on-device GenAI voices exist and to download them.
 * Speaking goes through [com.animeshahilya.geminitts.engine.PlatformSynth].
 */
object GenAiVoices {

    /** Used until the voices are known. */
    val DEFAULT_LOCALE: Locale = Locale.US

    /** The languages Google documents for the GenAI voices. */
    val LOCALES: List<Locale> = (
        "ar-XA da-DK de-DE en-GB en-US es-ES es-US fr-CA fr-FR hi-IN id-ID it-IT ja-JP ko-KR " +
            "nb-NO nl-NL pl-PL pt-BR ru-RU sv-SE tr-TR"
        ).split(' ').map(Locale::forLanguageTag)

    fun client(): SpeechSynthesizer = SpeechSynthesis.getClient(speechSynthesizerOptions(DEFAULT_LOCALE) {})

    /** Lists the voices of every language and stores them in [TtsPrefs] for the service. */
    suspend fun fetch(context: Context, client: SpeechSynthesizer): List<VoiceInfo> {
        val voices = LOCALES.flatMap { locale ->
            try {
                client.getSupportedVoices(locale)
            } catch (e: Exception) {
                Log.w(TAG, "No voices for ${locale.toLanguageTag()}", e)
                emptyList()
            }
        }.distinctBy { it.name to it.locale }
        if (voices.isNotEmpty()) {
            TtsPrefs(context).voices = voices.map { VoiceEntry(it.name, it.locale, it.gender.name.lowercase()) }
        }
        return voices
    }

    /** Refreshes the stored list with a short-lived client. */
    suspend fun refresh(context: Context) {
        try {
            client().use { fetch(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list GenAI voices", e)
        }
    }

    private const val TAG = "GenAiVoices"
}
