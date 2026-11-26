package com.animeshahilya.geminitts.data

import com.animeshahilya.geminitts.audio.ClarityPreset

enum class DigitGroupingMode(val id: String, val label: String, val summary: String) {
    OFF("off", "Whole numbers", "Read numbers normally."),
    SINGLE("single", "Digit by digit", "Read each digit separately (1234 -> 1 2 3 4)."),
    PAIRS("pairs", "Pairs", "Group digits in twos (123456 -> 12 34 56)."),
    PAIRS_PAUSE("pairs_pause", "Pairs with pause", "Group in twos with a brief pause."),
    TRIPLETS("triplets", "Triplets", "Group digits in threes (123456 -> 123 456)."),
    TRIPLETS_PAUSE("triplets_pause", "Triplets with pause", "Group in threes with a brief pause.");

    companion object {
        fun fromId(id: String?): DigitGroupingMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

enum class TextPreset(val id: String, val label: String, val summary: String) {
    INHERIT("inherit", "Use global text settings", "Follow the main text and symbol options."),
    CLEAN_CHAT("clean_chat", "Clean chat & Markdown", "Clean Markdown, silence symbols, condense repeats."),
    DEVELOPER("developer", "Developer / Code", "Split CamelCase, tech terms, and clean Markdown off."),
    CODES_OTP("codes_otp", "OTP & verification codes", "Read OTPs, PINs, and leading zeros digit by digit.");

    companion object {
        fun fromId(id: String?): TextPreset = entries.firstOrNull { it.id == id } ?: INHERIT
    }
}

data class TextOptions(
    val normalizeFancyUnicode: Boolean = true,
    val collapseRepeats: Boolean = true,
    val cleanMarkdown: Boolean = false,
    val silenceSymbols: Boolean = false,
    val silencedSymbols: String = DEFAULT_SILENCED_SYMBOLS,
    val digitGrouping: DigitGroupingMode = DigitGroupingMode.OFF,
    val digitGroupMinLen: Int = 5,
    val preserveLeadingZeros: Boolean = false,
    val readCodesDigits: Boolean = false,
    val indianNumbering: Boolean = false,
    val splitCamelCase: Boolean = false,
    val shortenUrls: Boolean = false,
    val normalizeTechWords: Boolean = false
) {
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(
            b(normalizeFancyUnicode), b(collapseRepeats), b(cleanMarkdown),
            b(silenceSymbols), if (silenceSymbols) silencedSymbols else "",
            digitGrouping.id, digitGroupMinLen, b(preserveLeadingZeros),
            b(readCodesDigits), b(indianNumbering), b(splitCamelCase),
            b(shortenUrls), b(normalizeTechWords)
        ).joinToString("|")
    }

    fun applyAppPreset(preset: TextPreset): TextOptions = when (preset) {
        TextPreset.INHERIT -> this
        TextPreset.CLEAN_CHAT -> copy(cleanMarkdown = true, silenceSymbols = true, collapseRepeats = true)
        TextPreset.DEVELOPER -> copy(splitCamelCase = true, normalizeTechWords = true, cleanMarkdown = false, silenceSymbols = false)
        TextPreset.CODES_OTP -> copy(readCodesDigits = true, preserveLeadingZeros = true)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "normalizeFancyUnicode" to normalizeFancyUnicode,
        "collapseRepeats" to collapseRepeats,
        "cleanMarkdown" to cleanMarkdown,
        "silenceSymbols" to silenceSymbols,
        "silencedSymbols" to silencedSymbols,
        "digitGrouping" to digitGrouping.id,
        "digitGroupMinLen" to digitGroupMinLen,
        "preserveLeadingZeros" to preserveLeadingZeros,
        "readCodesDigits" to readCodesDigits,
        "indianNumbering" to indianNumbering,
        "splitCamelCase" to splitCamelCase,
        "shortenUrls" to shortenUrls,
        "normalizeTechWords" to normalizeTechWords
    )

    companion object {
        const val DEFAULT_SILENCED_SYMBOLS = "*~_#|^\\<>"
        const val MARKDOWN_SYMBOLS = "*_~"
        const val CODE_SYMBOLS = "#|^\\<>"
        const val BULLET_SYMBOLS = "•◦▪▸"

        private fun b(v: Boolean): String = if (v) "1" else "0"

        fun fromMap(map: Map<String, Any?>?): TextOptions {
            if (map == null) return TextOptions()
            return TextOptions(
                normalizeFancyUnicode = map.bool("normalizeFancyUnicode", true),
                collapseRepeats = map.bool("collapseRepeats", true),
                cleanMarkdown = map.bool("cleanMarkdown", false),
                silenceSymbols = map.bool("silenceSymbols", false),
                silencedSymbols = map.str("silencedSymbols", DEFAULT_SILENCED_SYMBOLS),
                digitGrouping = DigitGroupingMode.fromId(map.str("digitGrouping", DigitGroupingMode.OFF.id)),
                digitGroupMinLen = map.int("digitGroupMinLen", 5).coerceIn(2, 10),
                preserveLeadingZeros = map.bool("preserveLeadingZeros", false),
                readCodesDigits = map.bool("readCodesDigits", false),
                indianNumbering = map.bool("indianNumbering", false),
                splitCamelCase = map.bool("splitCamelCase", false),
                shortenUrls = map.bool("shortenUrls", false),
                normalizeTechWords = map.bool("normalizeTechWords", false)
            )
        }
    }
}

data class LanguageOverride(
    val localeTag: String,
    val voiceId: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val enabled: Boolean = true
) {
    val isDefault: Boolean
        get() = voiceId == null && speed == 1f && pitch == 1f && volume == 1f && enabled

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "localeTag" to localeTag,
        "voiceId" to voiceId,
        "speed" to speed,
        "pitch" to pitch,
        "volume" to volume,
        "enabled" to enabled
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): LanguageOverride? {
            val tag = map.str("localeTag", "").trim()
            if (tag.isEmpty()) return null
            return LanguageOverride(
                localeTag = tag,
                voiceId = map["voiceId"]?.toString()?.takeIf { it.isNotBlank() },
                speed = map.float("speed", 1f).coerceIn(0.5f, 2.5f),
                pitch = map.float("pitch", 1f).coerceIn(0.5f, 2f),
                volume = map.float("volume", 1f).coerceIn(0.25f, 2f),
                enabled = map.bool("enabled", true)
            )
        }
    }
}

data class AppProfile(
    val packageName: String,
    val voiceId: String? = null,
    val styleId: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val textPreset: TextPreset = TextPreset.INHERIT
) {
    val isDefault: Boolean
        get() = voiceId == null && styleId == null && speed == 1f && pitch == 1f && volume == 1f && textPreset == TextPreset.INHERIT

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "packageName" to packageName,
        "voiceId" to voiceId,
        "styleId" to styleId,
        "speed" to speed,
        "pitch" to pitch,
        "volume" to volume,
        "textPreset" to textPreset.id
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): AppProfile? {
            val pkg = map.str("packageName", "").trim()
            if (pkg.isEmpty()) return null
            return AppProfile(
                packageName = pkg,
                voiceId = map["voiceId"]?.toString()?.takeIf { it.isNotBlank() },
                styleId = map["styleId"]?.toString()?.takeIf { it.isNotBlank() },
                speed = map.float("speed", 1f).coerceIn(0.5f, 2.5f),
                pitch = map.float("pitch", 1f).coerceIn(0.5f, 2f),
                volume = map.float("volume", 1f).coerceIn(0.25f, 2f),
                textPreset = TextPreset.fromId(map.str("textPreset", TextPreset.INHERIT.id))
            )
        }
    }
}

data class VoicePreset(
    val name: String,
    val voiceId: String?,
    val styleId: String,
    val customStyle: ReadingStyle,
    val baseSpeed: Float,
    val basePitch: Float,
    val volumePercent: Int,
    val lockSpeed: Boolean,
    val lockPitch: Boolean,
    val lockVolume: Boolean,
    val clarity: ClarityPreset,
    val leveler: Boolean,
    val textOptions: TextOptions
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "voiceId" to voiceId,
        "styleId" to styleId,
        "customSpeed" to customStyle.speed,
        "customPitch" to customStyle.pitch,
        "customSentenceMs" to customStyle.sentencePauseMs,
        "customParagraphMs" to customStyle.paragraphPauseMs,
        "baseSpeed" to baseSpeed,
        "basePitch" to basePitch,
        "volumePercent" to volumePercent,
        "lockSpeed" to lockSpeed,
        "lockPitch" to lockPitch,
        "lockVolume" to lockVolume,
        "clarity" to clarity.id,
        "leveler" to leveler,
        "textOptions" to textOptions.toMap()
    )

    companion object {
        const val MAX_NAME = 40

        fun fromMap(map: Map<String, Any?>): VoicePreset? {
            val name = map.str("name", "").trim().take(MAX_NAME)
            if (name.isEmpty()) return null
            val base = ReadingStyle.PRESETS.first { it.id == "audiobook" }
            @Suppress("UNCHECKED_CAST")
            val textMap = map["textOptions"] as? Map<String, Any?>
            return VoicePreset(
                name = name,
                voiceId = map["voiceId"]?.toString()?.takeIf { it.isNotBlank() },
                styleId = map.str("styleId", ReadingStyle.STANDARD),
                customStyle = ReadingStyle.custom(
                    map.float("customSpeed", base.speed).coerceIn(0.5f, 3f),
                    map.float("customPitch", base.pitch).coerceIn(0.5f, 2f),
                    map.int("customSentenceMs", base.sentencePauseMs).coerceIn(0, 1500),
                    map.int("customParagraphMs", base.paragraphPauseMs).coerceIn(0, 3000)
                ),
                baseSpeed = map.float("baseSpeed", 1f).coerceIn(0.5f, 3.5f),
                basePitch = map.float("basePitch", 1f).coerceIn(0.5f, 2f),
                volumePercent = map.int("volumePercent", 100).coerceIn(25, 200),
                lockSpeed = map.bool("lockSpeed", false),
                lockPitch = map.bool("lockPitch", false),
                lockVolume = map.bool("lockVolume", false),
                clarity = ClarityPreset.fromId(map.str("clarity", ClarityPreset.OFF.id)),
                leveler = map.bool("leveler", false),
                textOptions = TextOptions.fromMap(textMap)
            )
        }
    }
}

internal fun Map<String, Any?>.str(key: String, default: String): String =
    (this[key] as? String) ?: this[key]?.toString() ?: default

internal fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull() ?: default
    else -> default
}

internal fun Map<String, Any?>.int(key: String, default: Int): Int = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: default
    else -> default
}

internal fun Map<String, Any?>.float(key: String, default: Float): Float = when (val v = this[key]) {
    is Number -> v.toFloat()
    is String -> v.toFloatOrNull() ?: default
    else -> default
}
