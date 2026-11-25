package com.animeshahilya.geminitts.data

import java.util.Locale
import java.util.MissingResourceException

/** A GenAI voice in one language, as ML Kit reports it. */
data class VoiceEntry(val name: String, val locale: Locale, val gender: String = "") {
    /** The voice itself, the same across languages: "en-us-x-star02" -> "star02". */
    val id: String get() = voiceId(name)

    /** True for [lang] (ISO 639-2, e.g. "eng") and, when given, [country] (ISO 3166 alpha-3 or alpha-2). */
    fun matches(lang: String?, country: String? = null): Boolean {
        if (lang.isNullOrEmpty() || !lang.equals(locale.iso3Language(), true) && !lang.equals(locale.language, true)) return false
        return country.isNullOrEmpty() || country.equals(locale.iso3Country(), true) || country.equals(locale.country, true)
    }
}

fun voiceId(name: String): String = name.substringAfterLast("-x-").removeSuffix("-local")

// Android still reports some legacy codes ("in" for Indonesian); ISO3 codes are stable.
fun Locale.iso3Language(): String = try { isO3Language } catch (_: MissingResourceException) { language }

// Pseudo-regions like ar-XA have no ISO3 code.
fun Locale.iso3Country(): String = try { isO3Country } catch (_: MissingResourceException) { country }

private val Locale.hasRealCountry: Boolean
    get() = country.isNotEmpty() && try { isO3Country.isNotEmpty() } catch (_: MissingResourceException) { false }

/**
 * The "lang-COUNTRY" code Android's TTS settings understand. A pseudo-region (ar-XA) is reported as the
 * language alone: Settings turns an unparseable code into a null entry and crashes on it.
 */
fun Locale.ttsCode(): String = if (hasRealCountry) "${iso3Language()}-$isO3Country" else iso3Language()

/** "Hindi (India)", but plain "Arabic" for a pseudo-region instead of "Arabic (Pseudo-Accents)". */
fun Locale.label(): String = if (hasRealCountry) displayName else displayLanguage

/**
 * The voice to read [lang]/[country] with: the chosen voice [id] in that language if Google has it, else that
 * language's first voice. Exact country first, then any country (an en-IN request gets en-US or en-GB).
 */
fun pickVoice(voices: List<VoiceEntry>, id: String?, lang: String?, country: String?): VoiceEntry? =
    voicesFor(voices, id, lang, country).firstOrNull()

/**
 * Every voice of [lang]/[country] in the order to try them: the chosen [id] first, then the rest by id,
 * so "no choice yet" always means the same voice (star02) in the app and in the engine.
 */
fun voicesFor(voices: List<VoiceEntry>, id: String?, lang: String?, country: String?): List<VoiceEntry> {
    val pool = voices.filter { it.matches(lang, country) }.ifEmpty { voices.filter { it.matches(lang) } }
    return pool.sortedWith(compareBy({ it.id != id }, { it.id }))
}
