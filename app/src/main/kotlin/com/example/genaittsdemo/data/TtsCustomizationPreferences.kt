package com.example.genaittsdemo.data

import android.content.Context
import android.content.SharedPreferences

enum class PunctuationMode {
    SOME,
    ALL,
    NONE
}

data class DictionaryRule(
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val description: String = ""
)

class TtsCustomizationPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("genai_tts_custom_prefs", Context.MODE_PRIVATE)

    var punctuationMode: PunctuationMode
        get() {
            val name = prefs.getString("punctuation_mode", PunctuationMode.SOME.name)
            return try { PunctuationMode.valueOf(name!!) } catch (_: Exception) { PunctuationMode.SOME }
        }
        set(value) = prefs.edit().putString("punctuation_mode", value.name).apply()

    var speedMultiplier: Float
        get() = prefs.getFloat("speed_multiplier", 1.0f)
        set(value) = prefs.edit().putFloat("speed_multiplier", value).apply()

    var pitchMultiplier: Float
        get() = prefs.getFloat("pitch_multiplier", 1.0f)
        set(value) = prefs.edit().putFloat("pitch_multiplier", value).apply()

    var preferredVoiceName: String
        get() = prefs.getString("preferred_voice_name", "Aoede") ?: "Aoede"
        set(value) {
            prefs.edit().putString("preferred_voice_name", value).apply()
            val profile = GeminiVoiceCatalog.findByName(value)
            if (profile != null) {
                preferredGender = profile.gender
            }
        }

    var preferredGender: String
        get() = prefs.getString("preferred_gender", "FEMALE") ?: "FEMALE"
        set(value) = prefs.edit().putString("preferred_gender", value).apply()

    var isCacheEnabled: Boolean
        get() = prefs.getBoolean("cache_enabled", true)
        set(value) = prefs.edit().putBoolean("cache_enabled", value).apply()

    var isSmartChunkingEnabled: Boolean
        get() = prefs.getBoolean("smart_chunking_enabled", true)
        set(value) = prefs.edit().putBoolean("smart_chunking_enabled", value).apply()

    var isNumberExpansionEnabled: Boolean
        get() = prefs.getBoolean("number_expansion_enabled", true)
        set(value) = prefs.edit().putBoolean("number_expansion_enabled", value).apply()

    /**
     * Default built-in accessibility dictionary for modern hardware, AI terms, and common abbreviations.
     */
    fun getDefaultRules(): List<DictionaryRule> {
        return listOf(
            DictionaryRule("S25 Ultra", "S twenty-five Ultra", false, "Flagship device name"),
            DictionaryRule("S25", "S twenty-five", false, "Galaxy model"),
            DictionaryRule("S24", "S twenty-four", false, "Galaxy model"),
            DictionaryRule("NPU", "N P U", false, "Neural Processing Unit"),
            DictionaryRule("TPU", "T P U", false, "Tensor Processing Unit"),
            DictionaryRule("GPU", "G P U", false, "Graphics Processing Unit"),
            DictionaryRule("CPU", "C P U", false, "Processor"),
            DictionaryRule("AI", "A I", false, "Artificial Intelligence"),
            DictionaryRule("TTS", "T T S", false, "Text To Speech"),
            DictionaryRule("LLM", "L L M", false, "Large Language Model"),
            DictionaryRule("API", "A P I", false, "Application Interface"),
            DictionaryRule("APK", "A P K", false, "Android Package"),
            DictionaryRule("APKs", "A P K's", false, "Android Packages"),
            DictionaryRule("UI", "U I", false, "User Interface"),
            DictionaryRule("UX", "U X", false, "User Experience"),
            DictionaryRule("OS", "O S", false, "Operating System"),
            DictionaryRule("RAM", "ram", false, "Memory"),
            DictionaryRule("ROM", "rom", false, "Storage"),
            DictionaryRule("AMOLED", "amo-led", false, "Display type"),
            DictionaryRule("OLED", "oh-led", false, "Display type"),
            DictionaryRule("USB-C", "U S B C", false, "Cable connector"),
            DictionaryRule("5G", "5 G", false, "Network generation"),
            DictionaryRule("4G", "4 G", false, "Network generation"),
            DictionaryRule("Wi-Fi", "why-fye", false, "Wireless network"),
            DictionaryRule("Bluetooth", "bluetooth", false, "Wireless connection"),
            DictionaryRule("mAh", "milliamp hours", false, "Battery capacity"),
            DictionaryRule("GHz", "gigahertz", false, "Frequency"),
            DictionaryRule("MHz", "megahertz", false, "Frequency"),
            DictionaryRule("GB", "gigabytes", false, "Data storage"),
            DictionaryRule("MB", "megabytes", false, "Data storage"),
            DictionaryRule("KB", "kilobytes", false, "Data storage"),
            DictionaryRule("TB", "terabytes", false, "Data storage"),
            DictionaryRule("e.g.", "for example", false, "Latin abbreviation"),
            DictionaryRule("i.e.", "that is", false, "Latin abbreviation"),
            DictionaryRule("etc.", "etcetera", false, "Latin abbreviation"),
            DictionaryRule("vs", "versus", false, "Comparison"),
            DictionaryRule("w/", "with", false, "Shorthand"),
            DictionaryRule("w/o", "without", false, "Shorthand")
        )
    }

    fun getDictionaryRules(): List<DictionaryRule> {
        val raw = prefs.getString("dictionary_rules_v2", null)
        if (raw == null) {
            val defaults = getDefaultRules()
            saveDictionaryRules(defaults)
            return defaults
        }
        if (raw.isBlank()) return emptyList()

        return raw.lines().mapNotNull { line ->
            val parts = line.split("=>")
            if (parts.size >= 2) {
                val pattern = parts[0].trim()
                val replacement = parts[1].trim()
                val isRegex = if (parts.size >= 3) parts[2].trim().toBoolean() else false
                val desc = if (parts.size >= 4) parts[3].trim() else ""
                DictionaryRule(pattern, replacement, isRegex, desc)
            } else null
        }
    }

    fun saveDictionaryRules(rules: List<DictionaryRule>) {
        val raw = rules.joinToString("\n") {
            "${it.pattern}=>${it.replacement}=>${it.isRegex}=>${it.description}"
        }
        prefs.edit().putString("dictionary_rules_v2", raw).apply()
    }

    fun resetToDefaultRules(): List<DictionaryRule> {
        val defaults = getDefaultRules()
        saveDictionaryRules(defaults)
        return defaults
    }

    /**
     * Applies complete text normalization pipeline:
     * 1. URL and email accessibility transforms
     * 2. Currency and unit expands
     * 3. Custom and preset dictionary rules
     * 4. Punctuation verbosity filtering
     * 5. Whitespace and repetition cleanup
     */
    fun applyFilters(input: String): String {
        var text = input

        // 1. URLs and Web links: simplify for accessibility
        text = text.replace(Regex("""https?://(?:www\.)?([a-zA-Z0-9.-]+)(?:/[^\s]*)?""")) { match ->
            "link to ${match.groupValues[1]}"
        }

        // 2. Email addresses
        text = text.replace(Regex("""([a-zA-Z0-9_.+-]+)@([a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+)""")) { match ->
            "${match.groupValues[1]} at ${match.groupValues[2]}"
        }

        // 3. Currencies and units
        if (isNumberExpansionEnabled) {
            text = text.replace(Regex("""\$(\d+)(?:\.(\d{1,2}))?""")) { match ->
                val dollars = match.groupValues[1]
                val cents = match.groupValues.getOrNull(2)
                if (!cents.isNullOrBlank() && cents != "00" && cents != "0") {
                    "$dollars dollars and $cents cents"
                } else {
                    "$dollars dollars"
                }
            }
            text = text.replace(Regex("""€(\d+)(?:\.(\d{1,2}))?""")) { match ->
                val euros = match.groupValues[1]
                val cents = match.groupValues.getOrNull(2)
                if (!cents.isNullOrBlank() && cents != "00" && cents != "0") {
                    "$euros euros and $cents cents"
                } else {
                    "$euros euros"
                }
            }
            text = text.replace(Regex("""£(\d+)(?:\.(\d{1,2}))?""")) { match ->
                val pounds = match.groupValues[1]
                val pence = match.groupValues.getOrNull(2)
                if (!pence.isNullOrBlank() && pence != "00" && pence != "0") {
                    "$pounds pounds and $pence pence"
                } else {
                    "$pounds pounds"
                }
            }
            text = text.replace(Regex("""₹(\d+)(?:\.(\d{1,2}))?""")) { match ->
                val rupees = match.groupValues[1]
                val paise = match.groupValues.getOrNull(2)
                if (!paise.isNullOrBlank() && paise != "00" && paise != "0") {
                    "$rupees rupees and $paise paise"
                } else {
                    "$rupees rupees"
                }
            }
            text = text.replace(Regex("""(\d+(?:\.\d+)?)\s*%""")) { "${it.groupValues[1]} percent" }
            text = text.replace(Regex("""(\d+)\s*°C""")) { "${it.groupValues[1]} degrees Celsius" }
            text = text.replace(Regex("""(\d+)\s*°F""")) { "${it.groupValues[1]} degrees Fahrenheit" }
            text = text.replace(Regex("""(\d+)\s*km/h""")) { "${it.groupValues[1]} kilometers per hour" }
            text = text.replace(Regex("""(\d+)\s*mph""")) { "${it.groupValues[1]} miles per hour" }
        }

        // 4. Repeated punctuation compaction (e.g. "......", "???", "!!!")
        text = text.replace(Regex("""\.{3,}"""), " ellipsis ")
        text = text.replace(Regex("""!{2,}"""), "!")
        text = text.replace(Regex("""\?{2,}"""), "?")
        text = text.replace(Regex("""[-_]{3,}"""), " ")

        // 5. Apply Dictionary Rules
        val rules = getDictionaryRules()
        for (rule in rules) {
            text = if (rule.isRegex) {
                try {
                    text.replace(Regex(rule.pattern, RegexOption.IGNORE_CASE), rule.replacement)
                } catch (_: Exception) { text }
            } else {
                // Word boundary check when applicable to prevent partial substring corruption
                if (rule.pattern.all { it.isLetterOrDigit() }) {
                    text.replace(Regex("""\b${Regex.escape(rule.pattern)}\b""", RegexOption.IGNORE_CASE), rule.replacement)
                } else {
                    text.replace(rule.pattern, rule.replacement, ignoreCase = true)
                }
            }
        }

        // 6. Apply Punctuation Verbosity
        text = when (punctuationMode) {
            PunctuationMode.ALL -> {
                text.replace(".", " dot ")
                    .replace(",", " comma ")
                    .replace("?", " question mark ")
                    .replace("!", " exclamation point ")
                    .replace(":", " colon ")
                    .replace(";", " semicolon ")
                    .replace("/", " slash ")
                    .replace("\\", " backslash ")
                    .replace("-", " dash ")
                    .replace("_", " underscore ")
                    .replace("(", " open paren ")
                    .replace(")", " close paren ")
                    .replace("[", " open bracket ")
                    .replace("]", " close bracket ")
                    .replace("{", " open brace ")
                    .replace("}", " close brace ")
                    .replace("@", " at ")
                    .replace("#", " hash ")
                    .replace("&", " and ")
                    .replace("*", " asterisk ")
                    .replace("+", " plus ")
                    .replace("=", " equals ")
                    .replace("<", " less than ")
                    .replace(">", " greater than ")
                    .replace("\"", " quote ")
                    .replace("'", " apostrophe ")
            }
            PunctuationMode.SOME -> {
                // Preserve commas, periods, question marks for natural speech cadence
                // Expand technical symbols that matter for screen readers
                text.replace("@", " at ")
                    .replace("#", " hash ")
                    .replace("&", " and ")
                    .replace("*", " asterisk ")
                    .replace("+", " plus ")
                    .replace("=", " equals ")
                    .replace("/", " slash ")
                    .replace("\\", " backslash ")
                    .replace("_", " underscore ")
                    .replace("<", " less than ")
                    .replace(">", " greater than ")
            }
            PunctuationMode.NONE -> {
                text.replace(Regex("""[.,?!:;\\/\-()\[\]{}@#&*+=<>"'_~]"""), " ")
            }
        }

        // 7. Cleanup extra spaces
        return text.replace(Regex("""\s+"""), " ").trim()
    }
}
