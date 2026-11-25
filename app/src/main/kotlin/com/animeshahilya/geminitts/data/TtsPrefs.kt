package com.animeshahilya.geminitts.data

import android.content.Context
import java.util.Locale

/**
 * Settings shared by the app and the TTS service. Kept in device-protected storage so the service
 * can read them before the phone is first unlocked.
 */
class TtsPrefs(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext().let { dp ->
        dp.moveSharedPreferencesFrom(context, NAME)
        dp.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** The chosen voice ("star02"), used in every language; null means each language's first voice. */
    var voiceId: String?
        // Older versions stored a full name ("en-us-x-star02"); voiceId() reduces either form.
        get() = prefs.getString(KEY_VOICE, null)?.let(::voiceId)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE, true)
        set(value) = prefs.edit().putBoolean(KEY_CACHE, value).apply()

    var styleId: String
        get() = prefs.getString(KEY_STYLE, null) ?: ReadingStyle.STANDARD
        set(value) = prefs.edit().putString(KEY_STYLE, value).apply()

    /** The values behind the Custom style; start as Audiobook's so there is something to adjust. */
    var customStyle: ReadingStyle
        get() {
            val base = ReadingStyle.PRESETS.first { it.id == "audiobook" }
            return ReadingStyle.custom(
                prefs.getFloat(KEY_CUSTOM_SPEED, base.speed),
                prefs.getFloat(KEY_CUSTOM_PITCH, base.pitch),
                prefs.getInt(KEY_CUSTOM_SENTENCE, base.sentencePauseMs),
                prefs.getInt(KEY_CUSTOM_PARAGRAPH, base.paragraphPauseMs)
            )
        }
        set(value) = prefs.edit()
            .putFloat(KEY_CUSTOM_SPEED, value.speed)
            .putFloat(KEY_CUSTOM_PITCH, value.pitch)
            .putInt(KEY_CUSTOM_SENTENCE, value.sentencePauseMs)
            .putInt(KEY_CUSTOM_PARAGRAPH, value.paragraphPauseMs)
            .apply()

    /** The style in use, for every app, voice and language. */
    fun style(): ReadingStyle = styleId.let { id ->
        if (id == ReadingStyle.CUSTOM) customStyle else ReadingStyle.PRESETS.firstOrNull { it.id == id } ?: ReadingStyle.PRESETS.first()
    }

    /** Every language's voices from the last ML Kit listing, so the service can answer before ML Kit does. */
    var voices: List<VoiceEntry>
        get() {
            val raw = prefs.getString(KEY_VOICES, null).orEmpty()
            parsedVoices?.let { (key, list) -> if (key == raw) return list }
            return parseVoices(raw).also { parsedVoices = raw to it }
        }
        set(value) = prefs.edit()
            .putString(KEY_VOICES, value.joinToString("\n") { "${it.name}|${it.locale.toLanguageTag()}|${it.gender}" })
            .apply()

    /** The voice for a caller's language (ISO3 codes, as in a SynthesisRequest). */
    fun voiceFor(lang: String?, country: String?): VoiceEntry? = pickVoice(voices, voiceId, lang, country)

    fun voiceFor(locale: Locale): VoiceEntry? = voiceFor(locale.iso3Language(), locale.iso3Country())

    /** All voices of a language, the chosen one first. */
    fun voicesFor(lang: String?, country: String?): List<VoiceEntry> = voicesFor(voices, voiceId, lang, country)

    fun voicesFor(locale: Locale): List<VoiceEntry> = voicesFor(locale.iso3Language(), locale.iso3Country())

    /** Every voice: the chosen one first (by id, so star02 when none is chosen), the phone's language first. */
    fun voicesAnyLanguage(): List<VoiceEntry> {
        val id = voiceId
        val system = Locale.getDefault().language
        return voices.sortedWith(compareBy({ id != null && it.id != id }, { it.id }, { it.locale.language != system }))
    }

    var rules: List<DictionaryRule>
        get() = parseRules(prefs.getString(KEY_RULES, null).orEmpty())
        set(value) = prefs.edit().putString(KEY_RULES, value.joinToString("\n") {
            "${it.pattern}$SEP${it.replacement}$SEP${it.isRegex}"
        }).apply()

    /** Compiled dictionary, rebuilt only when the rules change. */
    val dictionary: Dictionary
        get() {
            val raw = prefs.getString(KEY_RULES, null).orEmpty()
            compiled?.let { (key, dict) -> if (key == raw) return dict }
            return Dictionary(parseRules(raw)).also { compiled = raw to it }
        }

    private fun parseVoices(raw: String): List<VoiceEntry> = raw.lines().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size >= 2) VoiceEntry(parts[0], Locale.forLanguageTag(parts[1]), parts.getOrElse(2) { "" }) else null
    }

    private fun parseRules(raw: String): List<DictionaryRule> = raw.lines().mapNotNull { line ->
        val parts = line.split(SEP)
        if (parts.size >= 2 && parts[0].isNotEmpty()) {
            DictionaryRule(parts[0], parts[1], parts.getOrNull(2).toBoolean())
        } else null
    }

    companion object {
        private const val NAME = "tts_prefs"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_CACHE = "cache_enabled"
        private const val KEY_VOICES = "genai_voices"
        private const val KEY_RULES = "dictionary_rules"
        private const val KEY_STYLE = "reading_style"
        private const val KEY_CUSTOM_SPEED = "custom_speed"
        private const val KEY_CUSTOM_PITCH = "custom_pitch"
        private const val KEY_CUSTOM_SENTENCE = "custom_sentence_pause"
        private const val KEY_CUSTOM_PARAGRAPH = "custom_paragraph_pause"
        private const val SEP = "=>"

        // Parsed once per change: both are read on every utterance.
        @Volatile private var compiled: Pair<String, Dictionary>? = null
        @Volatile private var parsedVoices: Pair<String, List<VoiceEntry>>? = null
    }
}
