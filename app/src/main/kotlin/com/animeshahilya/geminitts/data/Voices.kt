package com.animeshahilya.geminitts.data

import java.util.Locale
import java.util.MissingResourceException

/** A GenAI voice in one language, as ML Kit reports it. */
data class VoiceEntry(val name: String, val locale: Locale, val gender: String = "") {
    /** The voice itself, the same across languages: "en-us-x-star02" -> "star02". */
    val id: String get() = voiceId(name)

    /** True for [lang] (ISO 639-2, e.g. "eng") and, when given, [country] (ISO 3166 alpha-3 or alpha-2). */
    fun matches(lang: String?, country: String? = null): Boolean {
        if (lang.isNullOrEmpty()) return false
        val targetLang = canonicalLang(lang)
        val localeIso3 = locale.iso3Language()
        val langMatch = targetLang.equals(localeIso3, true) || targetLang.equals(locale.language, true)
        if (!langMatch) return false
        return country.isNullOrEmpty() || country.equals(locale.iso3Country(), true) || country.equals(locale.country, true)
    }

    companion object {
        internal fun canonicalLang(lang: String): String = when (lang.lowercase()) {
            "in" -> "ind"
            "iw" -> "heb"
            "ji" -> "yid"
            "no", "nor" -> "nob"
            "fre" -> "fra"
            "ger" -> "deu"
            "dut" -> "nld"
            else -> lang
        }
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

/** Sample preview sentences shared between system sample requests and in-app voice previews. */
object SampleTexts {
    private val BY_ISO3 = mapOf(
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

    fun forLocale(locale: Locale): String =
        BY_ISO3[VoiceEntry.canonicalLang(locale.iso3Language())] ?: BY_ISO3.getValue("eng")

    fun forLanguageTag(code: String): String {
        val clean = code.trim().replace('_', '-')
        if (clean.isEmpty()) return BY_ISO3.getValue("eng")
        val primary = VoiceEntry.canonicalLang(clean.substringBefore('-').lowercase())
        BY_ISO3[primary]?.let { return it }
        return forLocale(Locale.forLanguageTag(clean))
    }
}

/** Resolved language, fast preferred voice list, lazy full candidates, and desired target voice. */
class VoicePlan(
    val language: Locale,
    val preferred: List<VoiceEntry>,
    private val allVoices: List<VoiceEntry>,
    private val chosenVoiceId: String?,
    val wanted: VoiceEntry?
) {
    val candidates: List<VoiceEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (preferred.isEmpty()) sortedFallback() else (preferred + sortedFallback()).distinct()
    }

    /** Picks the first installed voice in [preferred], sorting [allVoices] only if all preferred voices miss. */
    inline fun <T : Any> firstInstalled(lookup: (VoiceEntry) -> T?): T? {
        for (entry in preferred) {
            lookup(entry)?.let { return it }
        }
        return findInstalledFallback(lookup)
    }

    @PublishedApi
    internal inline fun <T : Any> findInstalledFallback(lookup: (VoiceEntry) -> T?): T? {
        for (entry in sortedFallback()) {
            lookup(entry)?.let { return it }
        }
        return null
    }

    @PublishedApi
    internal fun sortedFallback(): List<VoiceEntry> {
        val systemLang = Locale.getDefault().language
        return allVoices.sortedWith(
            compareBy({ chosenVoiceId != null && it.id != chosenVoiceId }, { it.id }, { it.locale.language != systemLang })
        )
    }
}

/**
 * Computes voice & locale precedence:
 * 1. Locale from whole-utterance `LocaleSpan` if present, else request locale/requested voice locale.
 * 2. Voice priority: internal caller preview -> per-app profile -> per-language override ->
 *    caller-requested voice -> global default.
 */
fun resolveVoicePlan(
    voices: List<VoiceEntry>,
    globalVoiceId: String?,
    appVoiceId: String?,
    langOverrideLookup: (Locale) -> String?,
    wholeLocale: Locale?,
    reqLang: String?,
    reqCountry: String?,
    requestedVoiceName: String?,
    defaultLocale: Locale,
    isInternalCaller: Boolean
): VoicePlan {
    val requested = if (requestedVoiceName.isNullOrEmpty()) null else voices.firstOrNull { it.name == requestedVoiceName }
    val targetLang = wholeLocale?.iso3Language() ?: reqLang
    val targetCountry = wholeLocale?.iso3Country() ?: reqCountry

    val language = when {
        wholeLocale != null -> voicesFor(voices, null, targetLang, targetCountry).firstOrNull()?.locale ?: wholeLocale
        !targetLang.isNullOrEmpty() -> voicesFor(voices, null, targetLang, targetCountry).firstOrNull()?.locale
            ?: requested?.locale
            ?: defaultLocale
        requested != null -> requested.locale
        else -> defaultLocale
    }

    val langVoiceId = langOverrideLookup(language)
    val chosenVoiceId = when {
        isInternalCaller && requested != null -> requested.id
        appVoiceId != null -> appVoiceId
        langVoiceId != null -> langVoiceId
        requested != null && requested.matches(targetLang ?: language.iso3Language()) -> requested.id
        else -> globalVoiceId
    }

    val langPool = voicesFor(voices, chosenVoiceId, language.iso3Language(), language.iso3Country())
    val includeRequested = requested != null && chosenVoiceId == null && requested.matches(language.iso3Language())
    val orderedSameLang = if (includeRequested) (listOf(requested) + langPool).distinct() else langPool
    val wanted = pickVoice(voices, chosenVoiceId ?: globalVoiceId, language.iso3Language(), language.iso3Country())
    return VoicePlan(language, orderedSameLang, voices, chosenVoiceId, wanted)
}
