package com.animeshahilya.geminitts.data

/**
 * Guards user dictionary regexes against catastrophic backtracking and empty-string matches,
 * ensuring a single bad pattern can never freeze TalkBack on a blind user's phone.
 */
object SafeRegex {

    const val MAX_PATTERN = 200

    /** Returns an error explanation if [pattern] is unsafe or invalid, or null if safe. */
    fun validate(pattern: String): String? {
        if (pattern.isEmpty()) return "Enter a pattern."
        if (pattern.length > MAX_PATTERN) return "Pattern is too long (max $MAX_PATTERN characters)."
        if (!backtracksSafely(pattern)) {
            return "Pattern has nested or multiple wildcards that could freeze speech."
        }
        val compiled = runCatching {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        }.getOrElse {
            return "That pattern isn't a valid regular expression."
        }
        if (compiled.find("") != null) {
            return "Pattern cannot match empty text (like .* or ^)."
        }
        return null
    }

    fun compile(pattern: String, caseSensitive: Boolean): Regex? {
        if (validate(pattern) != null) return null
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(pattern, options) }.getOrNull()
    }

    /**
     * Ported from eSpeak NG's SafeRegex: allows at most one variable repeat (*, +, {n,m}),
     * never attached to a group (...), and at most three ? quantifiers.
     */
    fun backtracksSafely(p: String): Boolean {
        var repeats = 0
        var optionals = 0
        var afterGroup = false
        var afterQuantifier = false
        var inClass = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\\') {
                if (i + 1 < p.length && p[i + 1] == 'Q') {
                    val end = p.indexOf("\\E", i + 2)
                    i = if (end < 0) p.length else end + 2
                } else {
                    i += 2
                }
                afterGroup = false
                afterQuantifier = false
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i++
                continue
            }
            if (afterQuantifier && (c == '?' || c == '+')) {
                afterQuantifier = false
                i++
                continue
            }
            if (c == '*' || c == '+') {
                if (afterGroup || ++repeats > 1) return false
                afterGroup = false
                afterQuantifier = true
                i++
                continue
            }
            if (c == '{') {
                val repEnd = repeatEnd(p, i)
                if (repEnd > 0) {
                    if (afterGroup || ++repeats > 1) return false
                    i = repEnd + 1
                    afterGroup = false
                    afterQuantifier = true
                    continue
                }
                val exEnd = exactEnd(p, i)
                if (exEnd > 0) {
                    i = exEnd + 1
                    afterGroup = false
                    afterQuantifier = true
                    continue
                }
            }
            if (c == '?' && !(i > 0 && p[i - 1] == '(')) {
                if (++optionals > 3) return false
                afterGroup = false
                afterQuantifier = true
                i++
                continue
            }
            afterQuantifier = false
            afterGroup = c == ')'
            if (c == '[') {
                inClass = true
                if (i + 1 < p.length && p[i + 1] == '^') i++
                if (i + 1 < p.length && p[i + 1] == ']') i++
            }
            i++
        }
        return true
    }

    private fun repeatEnd(p: String, start: Int): Int {
        var i = start + 1
        while (i < p.length && p[i] in '0'..'9') i++
        if (i == start + 1 || i >= p.length || p[i] != ',') return -1
        i++
        while (i < p.length && p[i] in '0'..'9') i++
        return if (i < p.length && p[i] == '}') i else -1
    }

    private fun exactEnd(p: String, start: Int): Int {
        var i = start + 1
        while (i < p.length && p[i] in '0'..'9') i++
        return if (i > start + 1 && i < p.length && p[i] == '}') i else -1
    }
}
