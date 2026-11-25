package com.animeshahilya.geminitts.data

import android.text.SpannableStringBuilder
import android.text.Spanned

data class DictionaryRule(
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false
)

/**
 * The user's pronunciation overrides, compiled once.
 *
 * Plain rules match whole words only, even when they contain symbols ("w/" never matches inside
 * "how/why"), and their replacement is literal. Regex rules may use $1..$9 for groups.
 * Spanned input keeps its TtsSpans, so the engine still reads phone numbers, dates and so on.
 */
class Dictionary(rules: List<DictionaryRule>) {

    private class Compiled(val regex: Regex, val replacement: String, val isRegex: Boolean)

    // Longest patterns first, so "w/o" is tried before "w/".
    private val compiled = rules
        .sortedByDescending { it.pattern.length }
        .mapNotNull { rule ->
            runCatching {
                val regex = if (rule.isRegex) {
                    Regex(rule.pattern, RegexOption.IGNORE_CASE)
                } else {
                    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(rule.pattern) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                }
                Compiled(regex, rule.replacement, rule.isRegex)
            }.getOrNull()
        }

    fun apply(text: CharSequence): CharSequence {
        if (compiled.isEmpty()) return text
        val spanned = text is Spanned
        val out: CharSequence = if (spanned) SpannableStringBuilder(text) else StringBuilder(text)
        var changed = false
        for (rule in compiled) {
            // Replace back to front so earlier match positions stay valid.
            for (match in rule.regex.findAll(out).toList().asReversed()) {
                val replacement = if (rule.isRegex) expand(rule.replacement, match) else rule.replacement
                val start = match.range.first
                val end = match.range.last + 1
                if (spanned) (out as SpannableStringBuilder).replace(start, end, replacement)
                else (out as StringBuilder).replace(start, end, replacement)
                changed = true
            }
        }
        return if (!changed) text else if (spanned) out else out.toString()
    }

    private fun expand(template: String, match: MatchResult): String =
        GROUP_REF.replace(template) { ref -> match.groupValues.getOrElse(ref.groupValues[1].toInt()) { "" } }

    companion object {
        private val GROUP_REF = Regex("\\$(\\d)")
    }
}
