package com.animeshahilya.geminitts.data

/**
 * TtsSpan-safe text preprocessing pipeline tailored for Google's on-device GenAI voices:
 * - Always-on safe hygiene (invisible hang-control chars, unpaired surrogates, script punctuation spacing, Hindi visarga)
 * - Fancy Unicode math/fullwidth letter normalization
 * - User Dictionary (including single-character/Indic-cluster rules)
 * - Markdown cleaning, URL shortening, CamelCase/snake_case splitting, tech acronyms
 * - Context-aware OTP/PIN codes, leading zeros, Indian numbering, digit grouping
 * - Repeated emoji & divider collapsing, optional silenced formatting symbols
 */
object TextPipeline {

    private val SCRIPT_PUNCT_BOUNDARY = Regex("""([।॥،؛؟۔。、！？，．]+)([^\s\p{Pe}\p{Pf}"'”’।॥،؛؟۔。、！？，．])""")
    private val VISARGA_COLON = Regex(
        """(?<=[\u0900-\u0963]):(?=[\u0915-\u0939\u0958-\u095F])|(?<![\u0900-\u097F])(अत|पुन|प्रात|प्राय|नम|स्वत|क्रमश|मुख्यत|सामान्यत|विशेषत|अंतत|अन्तत|वस्तुत|संभवत|सम्भवत|पूर्णत|मूलत|अंशत|प्रथमत|फलत|तत):"""
    )

    private val MD_CHECK_TODO = Regex("""(?m)^[ \t]*[-*+][ \t]+\[[ \t]*\][ \t]+""")
    private val MD_CHECK_DONE = Regex("""(?m)^[ \t]*[-*+][ \t]+\[[xX]\][ \t]+""")
    private val MD_IMAGE = Regex("""!\[([^\]]*)\]\([^)]+\)""")
    private val MD_LINK = Regex("""\[([^\]]+)\]\([^)]+\)""")
    private val MD_HEADING = Regex("""(?m)^[ \t]*#{1,6}[ \t]+""")
    private val MD_BLOCKQUOTE = Regex("""(?m)^[ \t]*>[ \t]+""")
    private val MD_HR = Regex("""(?m)^[ \t]*[-*_]{3,}[ \t]*$""")
    private val MD_CODE_FENCE = Regex("""(?s)```[a-zA-Z0-9_-]*\r?\n?(.*?)\r?\n?```""")
    private val MD_INLINE_CODE = Regex("""`([^`]+)`""")
    private val MD_BOLD = Regex("""\*\*([^*\n]+)\*\*""")
    private val MD_STRIKE = Regex("""~~([^~\n]+)~~""")
    private val MD_ITALIC = Regex("""(?<![\p{L}\d*])\*([^\s*](?:[^*\n]*[^\s*])?)\*(?![\p{L}\d*])""")

    private val PATTERN_URL = Regex("""\b(?:https?://|www\.)[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
    private val CAMEL_LOWER_UPPER = Regex("""(?<=[\p{Ll}\d])(?=\p{Lu})""")
    private val CAMEL_UPPER_RUN = Regex("""(?<=\p{Lu}\p{Lu})(?=\p{Lu}\p{Ll})""")
    private val SNAKE_CASE = Regex("""(?<=[\p{L}\d])_(?=[\p{L}\d])""")
    private val REPEAT_PUNCT_RUN = Regex("""([!?~\-=━─_*])\1{3,}""")

    private val TECH_REPLACEMENTS = listOf(
        "chat[ -]?gpt" to "Chat G P T",
        "g[ -]?pay" to "G-Pay",
        "g[ -]?mail" to "G-mail",
        "whats[ -]?app" to "Whats-App",
        "you[ -]?tube" to "You-Tube",
        "git[ -]?hub" to "Git-Hub",
        "git[ -]?lab" to "Git-Lab",
        "linked[ -]?in" to "Linked-In",
        "pay[ -]?tm" to "Pay T M",
        "phone[ -]?pe" to "Phone Pay",
        "zomato" to "Zo-mato",
        "swiggy" to "Swig-ee",
        "wi[ -]?fi" to "Wi-Fi",
        "upi" to "U P I",
        "otp" to "O T P",
        "kyc" to "K Y C",
        "pdf" to "P D F",
        "api" to "A P I",
        "url" to "U R L",
        "sms" to "S M S",
        "neft" to "N E F T",
        "rtgs" to "R T G S",
        "json" to "JAY-son",
        "yaml" to "yam-el",
        "sql" to "S Q L",
        "nosql" to "No S Q L",
        "sdk" to "S D K",
        "html" to "H T M L",
        "gps" to "G P S",
        "nfc" to "N F C",
        "vpn" to "V P N",
        "cpu" to "C P U",
        "gpu" to "G P U"
    )
    private val TECH_REGEX = Regex(
        "(?i)\\b(?:" + TECH_REPLACEMENTS.joinToString("|") { "(${it.first})" } + ")\\b"
    )

    private val EMAIL_RANGE = Regex("""(?<![\p{L}\p{N}_@./-])[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE)

    fun process(
        text: CharSequence,
        options: TextOptions,
        dictionary: Dictionary,
        languageTag: String = ""
    ): CharSequence {
        if (text.isEmpty()) return text

        val editor = SpanEditor(text)

        // 1. Safe always-on hygiene (invisible hang chars, unpaired surrogates, stuck punctuation, Hindi visarga).
        stripControlsAndSurrogates(editor)
        if (containsScriptPunctuation(editor.current)) {
            editor.replaceMatches(SCRIPT_PUNCT_BOUNDARY, protectTtsSpans = true) { m ->
                "${m.groupValues[1]} ${m.groupValues[2]}"
            }
        }
        if (editor.current.indexOf(':') >= 0 && (languageTag.startsWith("hi", true) || containsDevanagari(editor.current))) {
            editor.replaceMatches(VISARGA_COLON, protectTtsSpans = true) { m ->
                val word = m.groupValues[1]
                if (word.isEmpty()) "ः" else "$wordः"
            }
        }

        // 2. Normalize fancy Unicode mathematical/fullwidth letters before dictionary so rules match them.
        if (options.normalizeFancyUnicode) {
            normalizeFancyUnicode(editor)
        }

        // 3. User dictionary (with single-character/Indic-cluster handling).
        val afterDict = dictionary.apply(editor.result(), languageTag)
        val singleChar = isSingleCharacter(afterDict)
        if (singleChar) return afterDict

        val work = SpanEditor(afterDict)

        // 4. Chat/Markdown/Tech/Code normalizations (URL shortening runs after numbers & CamelCase so URL tokens don't get altered).
        val englishOrUnset = languageTag.isEmpty() || languageTag.startsWith("en", ignoreCase = true)
        if (options.cleanMarkdown) cleanMarkdown(work, englishLabels = englishOrUnset)
        if (options.normalizeTechWords && (englishOrUnset || languageTag.startsWith("hi", ignoreCase = true))) {
            normalizeTechWords(work)
        }
        if (options.splitCamelCase) splitCamelCase(work)

        // 5. Numbers & Codes (TtsSpan-safe; Indian currency/commas run before leading-zero & grouping).
        if (options.readCodesDigits) NumberReading.readCodes(work, englishLabel = englishOrUnset)
        if (options.indianNumbering && (englishOrUnset || languageTag.startsWith("hi", ignoreCase = true))) {
            NumberReading.applyIndianNumbering(work, devanagari = languageTag.startsWith("hi", ignoreCase = true))
        }
        if (options.preserveLeadingZeros) NumberReading.preserveLeadingZeros(work)
        if (options.digitGrouping != DigitGroupingMode.OFF) {
            NumberReading.formatDigitGrouping(work, options.digitGrouping, options.digitGroupMinLen)
        }
        if (options.shortenUrls) shortenUrls(work, englishLabel = englishOrUnset)

        // 6. Repeated emojis/symbols & optional silenced formatting symbols.
        if (options.collapseRepeats) {
            condenseRepeatedEmojis(work)
            collapseRepeatedSymbols(work)
        }
        if (options.silenceSymbols && options.silencedSymbols.isNotEmpty()) {
            silenceFormattingSymbols(work, options.silencedSymbols)
        }

        return work.result()
    }

    private fun stripControlsAndSurrogates(editor: SpanEditor) {
        val s = editor.current
        var needs = false
        for (i in 0 until s.length) {
            val c = s[i]
            if (isHangOrControl(c) || Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) { needs = true; break }
                } else if (Character.isLowSurrogate(c)) {
                    if (i == 0 || !Character.isHighSurrogate(s[i - 1])) { needs = true; break }
                } else {
                    needs = true; break
                }
            }
        }
        if (!needs) return

        // Walk back to front replacing single bad indices so spans stay shifted accurately.
        var i = s.length - 1
        while (i >= 0) {
            val c = s[i]
            val drop = when {
                isHangOrControl(c) -> true
                Character.isLowSurrogate(c) -> i == 0 || !Character.isHighSurrogate(s[i - 1])
                Character.isHighSurrogate(c) -> i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])
                else -> false
            }
            if (drop) editor.replaceRange(i, i + 1, "", protectTtsSpans = false)
            if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s[i - 1])) i -= 2
            else i--
        }
    }

    private fun isHangOrControl(c: Char): Boolean {
        if (c < ' ') return c != '\t' && c != '\n' && c != '\r'
        return c == '\u007F' || c == '\u200B' || c == '\u200E' || c == '\u200F' ||
            c in '\u202A'..'\u202E' || c in '\u2060'..'\u2064' || c == '\uFEFF'
    }

    private fun normalizeFancyUnicode(editor: SpanEditor) {
        val s = editor.current.toString()
        var hasFancy = false
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp in 0x1D400..0x1D7FF || cp in 0xFF01..0xFF5E || cp == 0x3000 || mapLetterlikeHole(cp) != null) {
                hasFancy = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!hasFancy) return

        val replacements = ArrayList<Triple<Int, Int, String>>()
        i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val count = Character.charCount(cp)
            val mapped = mapFancyCodePoint(cp)
            if (mapped != null) replacements += Triple(i, i + count, mapped)
            i += count
        }
        for (idx in replacements.lastIndex downTo 0) {
            val (start, end, repl) = replacements[idx]
            editor.replaceRange(start, end, repl, protectTtsSpans = true)
        }
    }

    private fun mapLetterlikeHole(cp: Int): String? = when (cp) {
        0x2102 -> "C"; 0x210D -> "H"; 0x2115 -> "N"; 0x2119 -> "P"
        0x211A -> "Q"; 0x211D -> "R"; 0x2124 -> "Z"; 0x210E -> "h"
        0x212C -> "B"; 0x2130 -> "E"; 0x2131 -> "F"; 0x210B -> "H"
        0x2110 -> "I"; 0x2112 -> "L"; 0x2133 -> "M"; 0x211B -> "R"
        0x212F -> "e"; 0x210A -> "g"; 0x2134 -> "o"
        0x212D -> "C"; 0x210C -> "H"; 0x2111 -> "I"; 0x211C -> "R"; 0x2128 -> "Z"
        else -> null
    }

    private fun mapFancyCodePoint(cp: Int): String? {
        mapLetterlikeHole(cp)?.let { return it }
        if (cp == 0x3000) return " "
        if (cp in 0xFF01..0xFF5E) return (cp - 0xFEE0).toChar().toString()
        if (cp in 0x1D7CE..0x1D7FF) {
            // Bold/Double-struck/Sans-serif/Monospace digits (5 blocks of 10 digits)
            val digit = (cp - 0x1D7CE) % 10
            return ('0'.code + digit).toChar().toString()
        }
        if (cp in 0x1D400..0x1D6A3) {
            val offset = cp - 0x1D400
            val posInStyle = offset % 52
            return if (posInStyle < 26) ('A'.code + posInStyle).toChar().toString()
            else ('a'.code + posInStyle - 26).toChar().toString()
        }
        return null
    }

    private fun cleanMarkdown(editor: SpanEditor, englishLabels: Boolean) {
        val cur = editor.current
        if (!cur.any { it in "*`~#>-[" }) return
        if (cur.indexOf('[') >= 0) {
            editor.replaceMatches(MD_CHECK_DONE) { if (englishLabels) "done: " else "" }
            editor.replaceMatches(MD_CHECK_TODO) { if (englishLabels) "todo: " else "" }
            editor.replaceMatches(MD_IMAGE) { it.groupValues[1] }
            editor.replaceMatches(MD_LINK) { it.groupValues[1] }
        }
        if (editor.current.indexOf('#') >= 0) editor.replaceMatches(MD_HEADING) { "" }
        if (editor.current.indexOf('>') >= 0) editor.replaceMatches(MD_BLOCKQUOTE) { if (englishLabels) "quote: " else "" }
        if (editor.current.indexOf('`') >= 0) {
            editor.replaceMatches(MD_CODE_FENCE) { it.groupValues[1] }
            editor.replaceMatches(MD_INLINE_CODE) { it.groupValues[1] }
        }
        if (editor.current.indexOf('*') >= 0) {
            editor.replaceMatches(MD_BOLD) { it.groupValues[1] }
            editor.replaceMatches(MD_ITALIC) { it.groupValues[1] }
        }
        if (editor.current.indexOf('~') >= 0) editor.replaceMatches(MD_STRIKE) { it.groupValues[1] }
        if (editor.current.indexOf('-') >= 0 || editor.current.indexOf('_') >= 0) editor.replaceMatches(MD_HR) { "" }
    }

    private fun shortenUrls(editor: SpanEditor, englishLabel: Boolean) {
        val cur = editor.current
        if (!cur.contains("://") && !cur.contains("www.", ignoreCase = true)) return
        editor.replaceMatches(PATTERN_URL) { m -> formatUrl(m.value, englishLabel) }
    }

    private fun formatUrl(raw: String, englishLabel: Boolean): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in ".,;:!?…\"'>") end--
        val trailing = raw.substring(end)
        var s = raw.substring(0, end)
        s = when {
            s.startsWith("https://", ignoreCase = true) -> s.substring(8)
            s.startsWith("http://", ignoreCase = true) -> s.substring(7)
            else -> s
        }
        if (s.startsWith("www.", ignoreCase = true)) s = s.substring(4)
        val qIdx = s.indexOf('?')
        if (qIdx >= 0) {
            val query = s.substring(qIdx)
            val paramNote = if (englishLabel) " with parameters" else ""
            s = s.substring(0, qIdx) + if (query.length > 10) paramNote else query
        }
        val slashWord = if (englishLabel) " slash " else " "
        s = s.trimEnd('/').replace(Regex("/+"), slashWord).trim()
        val body = if (englishLabel) "link $s" else s
        return if (trailing.isEmpty()) body else "$body $trailing"
    }

    private fun urlRanges(text: CharSequence): List<IntRange> =
        if (text.contains("://") || text.contains("www.", ignoreCase = true)) {
            PATTERN_URL.findAll(text).map { it.range }.toList()
        } else emptyList()

    private fun normalizeTechWords(editor: SpanEditor) {
        val cur = editor.current
        if (!cur.any { it in 'A'..'Z' || it in 'a'..'z' }) return
        val links = urlRanges(cur)
        editor.replaceMatches(TECH_REGEX) { m ->
            if (links.any { m.range.first in it }) return@replaceMatches null
            for (idx in TECH_REPLACEMENTS.indices) {
                if (m.groupValues[idx + 1].isNotEmpty()) return@replaceMatches TECH_REPLACEMENTS[idx].second
            }
            null
        }
    }

    private fun splitCamelCase(editor: SpanEditor) {
        for (regex in listOf(CAMEL_LOWER_UPPER, CAMEL_UPPER_RUN, SNAKE_CASE)) {
            val links = urlRanges(editor.current)
            editor.replaceMatches(regex) { m -> if (links.any { m.range.first in it }) null else " " }
        }
    }

    private fun condenseRepeatedEmojis(editor: SpanEditor) {
        val s = editor.current.toString()
        if (!s.any { it.code >= 0x2300 }) return
        val edits = ArrayList<Triple<Int, Int, String>>()
        var i = 0
        while (i < s.length) {
            val clusterLen = emojiClusterLength(s, i)
            if (clusterLen == 0) {
                i += Character.charCount(Character.codePointAt(s, i))
                continue
            }
            val cluster = s.substring(i, i + clusterLen)
            var count = 1
            var nextPos = i + clusterLen
            while (nextPos < s.length) {
                var peek = nextPos
                while (peek < s.length && (s[peek] == ' ' || s[peek] == ',')) peek++
                val nextLen = emojiClusterLength(s, peek)
                if (nextLen == cluster.length && s.regionMatches(peek, cluster, 0, cluster.length)) {
                    count++
                    nextPos = peek + nextLen
                } else break
            }
            if (count >= 3) {
                val needsSpace = nextPos < s.length && !s[nextPos].isWhitespace() && s[nextPos] !in ".,!?;:"
                val repl = if (needsSpace) "$cluster, $count " else "$cluster, $count"
                edits += Triple(i, nextPos, repl)
            }
            i = nextPos
        }
        for (idx in edits.lastIndex downTo 0) {
            val (start, end, repl) = edits[idx]
            editor.replaceRange(start, end, repl, protectTtsSpans = true)
        }
    }

    private fun collapseRepeatedSymbols(editor: SpanEditor) {
        val cur = editor.current
        if (cur.length < 4) return
        editor.replaceMatches(REPEAT_PUNCT_RUN) { m ->
            val ch = m.groupValues[1]
            if (ch == "!" || ch == "?") ch else " "
        }
    }

    private fun silenceFormattingSymbols(editor: SpanEditor, silencedSet: String) {
        val s = editor.current.toString()
        val emailRanges = if ('@' in s) EMAIL_RANGE.findAll(s).map { it.range }.toList() else emptyList()
        val edits = ArrayList<Triple<Int, Int, String>>()
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val charLen = Character.charCount(cp)
            val isBox = cp in 0x2500..0x257F
            val isTatweel = cp == 0x0640
            val inSet = charLen == 1 && s[i] in silencedSet
            if ((inSet || isBox || isTatweel) &&
                emailRanges.none { i in it } &&
                !isMathOrRangeContext(s, i, charLen)
            ) {
                val start = i
                var end = i + charLen
                while (end < s.length) {
                    val ncp = Character.codePointAt(s, end)
                    val nlen = Character.charCount(ncp)
                    val nextSilenced = (nlen == 1 && s[end] in silencedSet) || ncp in 0x2500..0x257F || ncp == 0x0640
                    if (nextSilenced && emailRanges.none { end in it } && !isMathOrRangeContext(s, end, nlen)) {
                        end += nlen
                    } else break
                }
                val leftWord = start > 0 && s[start - 1].isLetterOrDigit()
                val rightWord = end < s.length && s[end].isLetterOrDigit()
                val repl = if (leftWord && rightWord) " " else ""
                edits += Triple(start, end, repl)
                i = end
            } else {
                i += charLen
            }
        }
        for (idx in edits.lastIndex downTo 0) {
            val (start, end, repl) = edits[idx]
            editor.replaceRange(start, end, repl, protectTtsSpans = true)
        }
    }

    private fun isMathOrRangeContext(s: String, index: Int, len: Int): Boolean {
        if (len != 1) return false
        val ch = s[index]
        val prevDigit = index > 0 && s[index - 1].isDigit()
        val nextDigit = index + 1 < s.length && s[index + 1].isDigit()
        return when (ch) {
            '-' -> nextDigit || (prevDigit && nextDigit)
            '*' -> prevDigit && nextDigit
            else -> false
        }
    }

    /** True when [text] is a single visual character, emoji cluster, or Indic akshara. */
    fun isSingleCharacter(text: CharSequence): Boolean {
        val s = text.toString().trim()
        if (s.isEmpty()) return false
        if (s.codePointCount(0, s.length) == 1) return true
        if (emojiClusterLength(s, 0) == s.length) return true
        return isSingleIndicCluster(s) || isSingleBaseWithMarks(s)
    }

    private fun isSingleBaseWithMarks(s: String): Boolean {
        val first = Character.codePointAt(s, 0)
        var i = Character.charCount(first)
        if (i >= s.length) return false
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val t = Character.getType(cp)
            val isMark = t == Character.NON_SPACING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt() ||
                t == Character.ENCLOSING_MARK.toInt() ||
                cp in 0xFE00..0xFE0F
            if (!isMark) return false
            i += Character.charCount(cp)
        }
        return true
    }

    private fun isSingleIndicCluster(s: String): Boolean {
        var cp = Character.codePointAt(s, 0)
        if (cp !in 0x0900..0x0DFF || !Character.isLetter(cp)) return false
        var afterVirama = false
        var i = Character.charCount(cp)
        while (i < s.length) {
            cp = Character.codePointAt(s, i)
            val type = Character.getType(cp)
            val mark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (cp == 0x200C || cp == 0x200D) {
                i += Character.charCount(cp)
                continue
            }
            if (mark && cp in 0x0900..0x0DFF) {
                afterVirama = cp in intArrayOf(0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D, 0x0DCA)
            } else if (afterVirama && cp in 0x0900..0x0DFF && Character.isLetter(cp)) {
                afterVirama = false
            } else {
                return false
            }
            i += Character.charCount(cp)
        }
        return true
    }

    private fun emojiClusterLength(s: String, start: Int): Int {
        if (start >= s.length) return 0
        val first = Character.codePointAt(s, start)
        if (first in 0x1F1E6..0x1F1FF) {
            val step = Character.charCount(first)
            if (start + step < s.length) {
                val second = Character.codePointAt(s, start + step)
                if (second in 0x1F1E6..0x1F1FF) return step + Character.charCount(second)
            }
            return step
        }
        if (!isEmojiCodePoint(first)) return 0
        var i = start + Character.charCount(first)
        var prevZwj = false
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp in 0x1F1E6..0x1F1FF) break
            if (prevZwj) {
                prevZwj = cp == 0x200D
                i += Character.charCount(cp)
            } else if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp == 0x20E3) {
                prevZwj = cp == 0x200D
                i += Character.charCount(cp)
            } else break
        }
        return i - start
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        (cp in 0x2600..0x27BF) || (cp in 0x1F000..0x1FAFF) || cp == 0x2B50 || cp == 0x231A || cp == 0x231B ||
            (cp in 0x23E9..0x23FA) || (cp in 0x2B05..0x2B07) || cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B55

    private fun containsScriptPunctuation(s: CharSequence): Boolean {
        for (i in 0 until s.length) {
            if (s[i] in "।॥،؛؟۔。、！？，．") return true
        }
        return false
    }

    private fun containsDevanagari(s: CharSequence): Boolean {
        for (i in 0 until s.length) {
            if (s[i] in '\u0900'..'\u097F') return true
        }
        return false
    }
}
