package com.animeshahilya.geminitts.data

import android.text.Spanned
import android.text.style.TtsSpan

/**
 * How text is read, on top of what the app asks for: a speed and pitch factor (multiplied with the
 * caller's rate and pitch) and extra silence after sentences and paragraphs. One style applies to
 * every app, voice and language.
 */
data class ReadingStyle(
    val id: String,
    val name: String,
    val description: String,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val sentencePauseMs: Int = 0,
    val paragraphPauseMs: Int = 0
) {
    val addsPauses: Boolean get() = sentencePauseMs > 0 || paragraphPauseMs > 0

    companion object {
        const val STANDARD = "standard"
        const val CUSTOM = "custom"
        const val DEFAULT_MAX_CHUNK_CHARS = 1400

        val PRESETS = listOf(
            ReadingStyle(STANDARD, "Standard", "Exactly as the app asks, nothing added."),
            ReadingStyle("audiobook", "Audiobook", "A little slower, with room between sentences and paragraphs.", 0.92f, 0.98f, 300, 800),
            ReadingStyle("calm", "Calm", "Slow and soft, with long pauses.", 0.85f, 0.95f, 450, 1000),
            ReadingStyle("news", "News", "Brisk and clear, with short pauses.", 1.08f, 1f, 120, 400),
            ReadingStyle("quick", "Quick", "Faster, no extra pauses. Good for skimming.", 1.25f, 1f, 0, 0)
        )

        fun custom(speed: Float, pitch: Float, sentencePauseMs: Int, paragraphPauseMs: Int) = ReadingStyle(
            CUSTOM, "Custom", "Your own speed, pitch and pauses.", speed, pitch, sentencePauseMs, paragraphPauseMs
        )
    }
}

// A line break, or spaces after a sentence end (Latin, Devanagari danda, CJK, Arabic question mark).
private val BREAK = Regex("""\n\s*|(?<=[.!?…।。！？؟])[ \t]+""")
private val SENTENCE_END_ONLY = Regex("""(?<=[.!?…।。！？؟])[ \t]+""")

/**
 * Cuts [text] where [style] adds a pause and caps any chunk at [maxChunkChars] (staying safely
 * below Android's 4,000-char TextToSpeech limit in all styles). Spans survive (pieces are
 * subSequences) and TtsSpan ranges are never split down the middle.
 */
fun splitForPauses(
    text: CharSequence,
    style: ReadingStyle,
    maxChunkChars: Int = ReadingStyle.DEFAULT_MAX_CHUNK_CHARS
): List<Pair<CharSequence, Int>> {
    if (!style.addsPauses) {
        if (text.length <= maxChunkChars) return listOf(text to 0)
        return chunkLongText(text, maxChunkChars, 0)
    }
    val pieces = mutableListOf<Pair<CharSequence, Int>>()
    var start = 0
    for (match in BREAK.findAll(text)) {
        if (insideTtsSpan(text, match.range.first)) continue
        val paragraph = '\n' in match.value
        val piece = text.subSequence(start, match.range.first)
        if (!paragraph && isAbbreviation(piece)) continue
        val pause = if (paragraph) style.paragraphPauseMs else style.sentencePauseMs
        if (piece.isBlank()) {
            // Several breaks in a row: keep only the longest pause.
            pieces.lastOrNull()?.let { (prev, p) -> pieces[pieces.lastIndex] = prev to maxOf(p, pause) }
        } else if (piece.length > maxChunkChars) {
            pieces += chunkLongText(piece, maxChunkChars, pause)
        } else {
            pieces += piece to pause
        }
        start = match.range.last + 1
    }
    val rest = text.subSequence(start, text.length)
    if (rest.isNotBlank()) {
        if (rest.length > maxChunkChars) pieces += chunkLongText(rest, maxChunkChars, 0)
        else pieces += rest to 0
    } else {
        pieces.lastOrNull()?.let { (prev, _) -> pieces[pieces.lastIndex] = prev to 0 }
    }
    return pieces.ifEmpty { listOf(text to 0) }
}

private fun chunkLongText(text: CharSequence, maxChunkChars: Int, finalPauseMs: Int): List<Pair<CharSequence, Int>> {
    val out = mutableListOf<Pair<CharSequence, Int>>()
    var start = 0
    val len = text.length
    while (start < len) {
        if (len - start <= maxChunkChars) {
            val tail = text.subSequence(start, len)
            if (tail.isNotBlank()) out += tail to finalPauseMs
            break
        }
        val limit = (start + maxChunkChars).coerceAtMost(len)
        val splitAt = findBestSplit(text, start, limit)
        val chunk = text.subSequence(start, splitAt)
        if (chunk.isNotBlank()) out += chunk to 0
        start = splitAt
        while (start < len && text[start].isWhitespace()) start++
    }
    if (out.isEmpty()) return listOf(text to finalPauseMs)
    if (out.last().second != finalPauseMs) {
        val (lastText, _) = out.last()
        out[out.lastIndex] = lastText to finalPauseMs
    }
    return out
}

private fun findBestSplit(text: CharSequence, start: Int, limit: Int): Int {
    val minUseful = start + (limit - start) / 3
    // 1. Prefer paragraph break.
    for (i in limit downTo minUseful + 1) {
        if (text[i - 1] == '\n' && !insideTtsSpan(text, i)) return i
    }
    // 2. Prefer sentence boundary.
    var bestSentence = -1
    for (m in SENTENCE_END_ONLY.findAll(text.subSequence(start, limit))) {
        val candidate = start + m.range.first
        if (candidate > minUseful && !insideTtsSpan(text, candidate)) {
            val piece = text.subSequence(start, candidate)
            if (!isAbbreviation(piece)) bestSentence = candidate
        }
    }
    if (bestSentence > start) return bestSentence
    // 3. Whitespace boundary.
    for (i in limit downTo minUseful + 1) {
        if (text[i - 1].isWhitespace() && !insideTtsSpan(text, i)) return i
    }
    // 4. Hard cut avoiding splitting inside a TtsSpan or UTF-16 surrogate pair.
    var cut = limit
    if (text is Spanned) {
        val spanning = text.getSpans(cut - 1, cut, TtsSpan::class.java)
            .firstOrNull { text.getSpanStart(it) < cut && text.getSpanEnd(it) > cut }
        if (spanning != null) {
            val spanStart = text.getSpanStart(spanning)
            cut = if (spanStart > start) spanStart else text.getSpanEnd(spanning).coerceAtMost(text.length)
        }
    }
    if (cut > start && cut < text.length && Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) {
        cut--
    }
    return if (cut > start) cut else limit
}

private fun insideTtsSpan(text: CharSequence, index: Int): Boolean {
    if (text !is Spanned || index <= 0 || index >= text.length) return false
    return text.getSpans(index - 1, index, TtsSpan::class.java).any {
        text.getSpanStart(it) < index && text.getSpanEnd(it) > index
    }
}

private fun isAbbreviation(piece: CharSequence): Boolean {
    var end = piece.length
    while (end > 0 && piece[end - 1].isWhitespace()) end--
    if (end == 0 || piece[end - 1] != '.') return false
    val wordEnd = end - 1
    var wordStart = wordEnd
    while (wordStart > 0) {
        val c = piece[wordStart - 1]
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') break
        wordStart--
    }
    val wordLen = wordEnd - wordStart
    if (wordLen <= 0) return false
    // "Dr", "Mrs", "St" (short and capitalised) or dotted ones like "e.g", "U.S".
    if (wordLen in 1..3 && piece[wordStart].isUpperCase()) return true
    for (i in wordStart until wordEnd) {
        if (piece[i] == '.') return true
    }
    return false
}
