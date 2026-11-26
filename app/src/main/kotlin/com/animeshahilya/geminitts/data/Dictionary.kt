package com.animeshahilya.geminitts.data

data class DictionaryRule(
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val wholeWord: Boolean = true,
    val caseSensitive: Boolean = false,
    val language: String = "",
    val isCharacter: Boolean = false
) {
    fun isValid(): Boolean {
        if (pattern.isEmpty()) return false
        return if (isRegex) SafeRegex.validate(pattern) == null else true
    }

    fun appliesToLanguage(langTag: String): Boolean {
        val scope = language.trim().replace('_', '-').lowercase()
        if (scope.isEmpty()) return true
        val req = langTag.trim().replace('_', '-').lowercase()
        if (req.isEmpty()) return false
        return req == scope || req.startsWith("$scope-")
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "pattern" to pattern,
        "replacement" to replacement,
        "isRegex" to isRegex,
        "wholeWord" to wholeWord,
        "caseSensitive" to caseSensitive,
        "language" to language,
        "isCharacter" to isCharacter
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): DictionaryRule? {
            val pattern = map["pattern"]?.toString() ?: return null
            if (pattern.isEmpty()) return null
            return DictionaryRule(
                pattern = pattern,
                replacement = map["replacement"]?.toString() ?: "",
                isRegex = map.bool("isRegex", false),
                wholeWord = map.bool("wholeWord", true),
                caseSensitive = map.bool("caseSensitive", false),
                language = map.str("language", ""),
                isCharacter = map.bool("isCharacter", false)
            )
        }
    }
}

/**
 * The user's pronunciation overrides, compiled once.
 *
 * Plain whole-word rules match whole words only, even when they contain symbols ("w/" never matches
 * inside "how/why"), and their replacement is literal. Regex rules pass [SafeRegex] validation and
 * may use $1..$9 for capture groups. Single-character rules ([DictionaryRule.isCharacter]) only
 * apply when the entire utterance is a single character or Indic akshara.
 * Spanned input keeps its TtsSpans intact.
 */
class Dictionary(rules: List<DictionaryRule>) {

    private class Compiled(
        val rule: DictionaryRule,
        val regex: Regex?
    )

    private val charRules: List<Compiled>
    private val textRules: List<Compiled>

    init {
        val sorted = rules.sortedByDescending { it.pattern.length }
        val chars = ArrayList<Compiled>()
        val texts = ArrayList<Compiled>()
        for (rule in sorted) {
            if (rule.pattern.isEmpty()) continue
            if (rule.isCharacter) {
                val regex = if (rule.isRegex) SafeRegex.compile(rule.pattern, rule.caseSensitive) else null
                if (!rule.isRegex || regex != null) chars += Compiled(rule, regex)
            } else {
                val regex = compileTextRule(rule) ?: continue
                texts += Compiled(rule, regex)
            }
        }
        charRules = chars
        textRules = texts
    }

    private fun compileTextRule(rule: DictionaryRule): Regex? {
        if (rule.isRegex) return SafeRegex.compile(rule.pattern, rule.caseSensitive)
        val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val escaped = Regex.escape(rule.pattern)
        val expr = if (rule.wholeWord) "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])" else escaped
        return runCatching { Regex(expr, options) }.getOrNull()
    }

    fun apply(
        text: CharSequence,
        languageTag: String = "",
        isSingleChar: Boolean? = null
    ): CharSequence {
        if (charRules.isEmpty() && textRules.isEmpty()) return text
        val editor = SpanEditor(text)

        val single = if (charRules.isEmpty()) false else (isSingleChar ?: TextPipeline.isSingleCharacter(text))
        if (single) {
            val trimmed = text.toString().trim()
            for (c in charRules) {
                if (!c.rule.appliesToLanguage(languageTag)) continue
                val regexMatch = c.regex?.matchEntire(trimmed)
                val matched = if (c.regex != null) regexMatch != null else trimmed.equals(c.rule.pattern, ignoreCase = !c.rule.caseSensitive)
                if (matched) {
                    val repl = if (regexMatch != null) expand(c.rule.replacement, regexMatch) else c.rule.replacement
                    editor.replaceRange(0, text.length, repl, protectTtsSpans = true)
                    return editor.result()
                }
            }
        }

        for (entry in textRules) {
            if (!entry.rule.appliesToLanguage(languageTag)) continue
            val regex = entry.regex ?: continue
            editor.replaceMatches(regex, protectTtsSpans = true) { match ->
                if (entry.rule.isRegex) expand(entry.rule.replacement, match) else entry.rule.replacement
            }
        }
        return editor.result()
    }

    private fun expand(template: String, match: MatchResult): String =
        GROUP_REF.replace(template) { ref ->
            val groupNum = ref.groupValues[1].ifEmpty { ref.groupValues[2] }.toInt()
            match.groupValues.getOrElse(groupNum) { "" }
        }

    companion object {
        private val GROUP_REF = Regex("""\$(\d)|\\([1-9])""")
    }
}
