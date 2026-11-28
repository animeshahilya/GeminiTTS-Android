package com.animeshahilya.geminitts.data

/**
 * TtsSpan-safe number verbalizers adapted from eSpeak NG:
 * - Context-aware OTP / PIN / account codes (`readCodes`)
 * - Leading-zero preservation (`preserveLeadingZeros`: `007` -> `0 0 7`)
 * - Indian lakh/crore numbering (`applyIndianNumbering`)
 * - Configurable digit grouping (`formatDigitGrouping`: single/pairs/triplets with optional pauses)
 */
object NumberReading {

    private val CODE_KEYWORD = Regex(
        """(?iu)(?<![\p{L}\p{M}])(otp|one[- ]time|pin|passcode|password|code|cvv|cvv2|pnr|token|txn|transaction|ref|reference|a/c|acct|account|card|id|serial|auth|2fa|iban|swift|verification|verify|код|кода|коду|кодом|пин|пароль|пароля|карта|карты|ओटीपी|कोड|पिन|पासवर्ड|खाता)(?![\p{L}\p{M}])"""
    )
    private val CODE_RUN = Regex(
        """(?<![\p{L}\d.,:/$€£¥₹+\-])([Xx*•]{2,}[ -]?)?(\d{2,}(?:[ -]\d{2,})*)(?![\d.,:%/]?\d)(?![\p{L}%])"""
    )
    private val DATE_HYPHEN = Regex("""(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}-\d{1,2}-\d{4})""")
    private val MONEY_BEFORE = Regex("""(?iu)(?:[$€£¥₹]|(?<![\p{L}\p{M}])(?:rs\.?|re\.?|रु\.?|inr|usd|eur|gbp|jpy))\s*$""")
    private val MONEY_AFTER = Regex("""(?iu)^\s*(?:[€¥₹$£]|(?:rs|rupees?|dollars?|euros?|pounds?|yen|usd|inr|eur|gbp|jpy|paise|cents?|रुपये|रुपया|पैसे|पैसा)(?![\p{L}\p{M}]))""")

    private val LEADING_ZERO = Regex("""(?<![\p{L}\d.,:/+\-])(0\d+)(?![\d.,:/\-])""")

    private val INDIAN_COMMAS = Regex("""\b(\d{1,2}(?:,\d{2})+),(\d{3})\b""")
    private val INDIAN_CURRENCY = Regex("""(?i)(?:₹\s*|\b(?:Rs\.?|Re\.?|INR)\s*)([0-9]+(?:,[0-9]+)*(?:\.[0-9]+)?)(?:\s*(k|l|lac|lakhs?|cr|crores?))?\b(?:\s*(?:/-|/=|--))?""")
    private val SHORTHAND_LAKH = Regex("""\b(\d+(?:\.\d+)?)\s*(?:L|(?i:lac|lakhs?))\b""")
    private val SHORTHAND_CRORE = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*(?:cr|crore|crores)\b""")

    private val DIGIT_RUN = Regex("""(?<![\p{L}\d.,:/+$€£¥₹\-])\d+(?![\d.,:/\-]|(?:st|nd|rd|th)\b)""", RegexOption.IGNORE_CASE)
    private val PRECEDING_ENDING = Regex("""(?i)\b(?:ending|last)\s+$""")

    /** Converts OTPs/PINs/masked card suffixes near keywords to spaced digits. */
    fun readCodes(editor: SpanEditor, englishLabel: Boolean = true, protectedRanges: List<IntRange> = emptyList()) {
        val snapshot = editor.current.toString()
        if (!hasAsciiDigit(snapshot) || !CODE_KEYWORD.containsMatchIn(snapshot)) return
        editor.replaceMatches(CODE_RUN, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            val start = m.range.first
            val end = m.range.last + 1
            val run = m.groupValues[2]
            val digits = run.count { it in '0'..'9' }
            if (digits !in 4..12 || DATE_HYPHEN.matches(run) || isMoney(snapshot, start, end) || !keywordNear(snapshot, start, end)) {
                return@replaceMatches null
            }
            val alreadyEnding = start > 0 && PRECEDING_ENDING.containsMatchIn(snapshot.subSequence((start - 16).coerceAtLeast(0), start))
            buildString {
                if (englishLabel && m.groupValues[1].isNotEmpty() && !alreadyEnding) append("ending ")
                for (ch in run) {
                    if (ch == ' ' || ch == '-') append(',')
                    else {
                        if (isNotEmpty() && last() != ' ') append(' ')
                        append(ch)
                    }
                }
            }
        }
    }

    /** Preserves identifiers with leading zeros (`007` -> `0 0 7`), leaving clock times/dates/decimals alone. */
    fun preserveLeadingZeros(editor: SpanEditor, protectedRanges: List<IntRange> = emptyList()) {
        if (!hasAsciiDigit(editor.current)) return
        editor.replaceMatches(LEADING_ZERO, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            val digits = m.groupValues[1]
            digits.asIterable().joinToString(" ")
        }
    }

    /** Expands Indian comma grouping (`12,34,567`), `₹`/`Rs` shorthands (`₹5L`, `2cr`). */
    fun applyIndianNumbering(editor: SpanEditor, devanagari: Boolean = false, protectedRanges: List<IntRange> = emptyList()) {
        val cur = editor.current
        if (!hasAsciiDigit(cur) || !mightHaveIndianNumber(cur)) return
        editor.replaceMatches(INDIAN_CURRENCY, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            val rawNum = m.groupValues[1].replace(",", "")
            val scale = m.groupValues[2].lowercase()
            val scaleWord = when (scale) {
                "k" -> if (devanagari) "हज़ार" else "thousand"
                "l", "lac", "lakh", "lakhs" -> if (devanagari) "लाख" else "lakh"
                "cr", "crore", "crores" -> if (devanagari) "करोड़" else "crore"
                else -> ""
            }
            val unit = if (devanagari) "रुपये" else if (rawNum == "1") "rupee" else "rupees"
            val spoken = if (scaleWord.isEmpty() && ',' in m.groupValues[1] && INDIAN_COMMAS.matches(m.groupValues[1])) {
                indianCommaWords(m.groupValues[1], devanagari)
            } else {
                rawNum
            }
            listOf(spoken, scaleWord, unit).filter { it.isNotEmpty() }.joinToString(" ")
        }
        editor.replaceMatches(SHORTHAND_LAKH, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            "${m.groupValues[1]} ${if (devanagari) "लाख" else "lakh"}"
        }
        editor.replaceMatches(SHORTHAND_CRORE, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            "${m.groupValues[1]} ${if (devanagari) "करोड़" else "crore"}"
        }
        editor.replaceMatches(INDIAN_COMMAS, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            indianCommaWords(m.value, devanagari)
        }
    }

    /** Formats digit runs of length >= [minLength] in single digits, pairs, or triplets (skipping money). */
    fun formatDigitGrouping(
        editor: SpanEditor,
        mode: DigitGroupingMode,
        minLength: Int,
        protectedRanges: List<IntRange> = emptyList()
    ) {
        if (mode == DigitGroupingMode.OFF) return
        val cur = editor.current
        if (!hasAsciiDigit(cur)) return
        val snapshot = cur.toString()
        val groupSize = when (mode) {
            DigitGroupingMode.SINGLE -> 1
            DigitGroupingMode.PAIRS, DigitGroupingMode.PAIRS_PAUSE -> 2
            DigitGroupingMode.TRIPLETS, DigitGroupingMode.TRIPLETS_PAUSE -> 3
            DigitGroupingMode.OFF -> return
        }
        val sep = when (mode) {
            DigitGroupingMode.PAIRS_PAUSE, DigitGroupingMode.TRIPLETS_PAUSE -> ", "
            else -> " "
        }
        editor.replaceMatches(DIGIT_RUN, protectTtsSpans = true, excludedRanges = protectedRanges) { m ->
            val raw = m.value
            if (raw.length < minLength || isMoney(snapshot, m.range.first, m.range.last + 1)) {
                return@replaceMatches null
            }
            if (groupSize == 1) {
                raw.asIterable().joinToString(" ")
            } else {
                raw.chunked(groupSize).joinToString(sep)
            }
        }
    }

    private fun indianCommaWords(grouped: String, devanagari: Boolean): String {
        val parts = grouped.split(',')
        val kharabWord = if (devanagari) "खरब" else "kharab"
        val arabWord = if (devanagari) "अरब" else "arab"
        val croreWord = if (devanagari) "करोड़" else "crore"
        val lakhWord = if (devanagari) "लाख" else "lakh"
        val thousandWord = if (devanagari) "हज़ार" else "thousand"
        val out = ArrayList<String>()
        val count = parts.size
        for ((idx, part) in parts.withIndex()) {
            val n = part.toIntOrNull() ?: continue
            if (n == 0) continue
            val posFromRight = count - 1 - idx
            val label = when (posFromRight) {
                0 -> ""
                1 -> thousandWord
                2 -> lakhWord
                3 -> croreWord
                4 -> arabWord
                5 -> kharabWord
                else -> "padma"
            }
            out += if (label.isEmpty()) n.toString() else "$n $label"
        }
        return if (out.isEmpty()) "0" else out.joinToString(" ")
    }

    private fun isMoney(text: CharSequence, start: Int, end: Int): Boolean {
        val before = text.subSequence((start - 8).coerceAtLeast(0), start)
        val after = text.subSequence(end, (end + 10).coerceAtMost(text.length))
        return MONEY_BEFORE.containsMatchIn(before) || MONEY_AFTER.containsMatchIn(after)
    }

    private fun keywordNear(text: CharSequence, start: Int, end: Int): Boolean {
        var from = (start - 72).coerceAtLeast(0)
        for (i in start - 1 downTo from) {
            if (text[i] in ".!?\n।") {
                from = i + 1
                break
            }
        }
        var to = (end + 30).coerceAtMost(text.length)
        for (i in end until to) {
            if (text[i] in ".!?\n।") {
                to = i
                break
            }
        }
        return CODE_KEYWORD.containsMatchIn(text.subSequence(from, to))
    }

    private fun hasAsciiDigit(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            if (text[i] in '0'..'9') return true
        }
        return false
    }

    private fun mightHaveIndianNumber(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            when (text[i]) {
                ',', '₹', 'r', 'R', 'l', 'L', 'c', 'C', 'i', 'I' -> return true
            }
        }
        return false
    }
}
