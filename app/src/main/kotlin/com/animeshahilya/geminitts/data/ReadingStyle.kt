package com.animeshahilya.geminitts.data

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

/**
 * Cuts [text] where [style] adds a pause, returning each piece with the silence to play after it.
 * Spans survive (pieces are subSequences). Abbreviations such as "Dr." or "e.g." don't count as ends.
 */
fun splitForPauses(text: CharSequence, style: ReadingStyle): List<Pair<CharSequence, Int>> {
    if (!style.addsPauses) return listOf(text to 0)
    val pieces = mutableListOf<Pair<CharSequence, Int>>()
    var start = 0
    for (match in BREAK.findAll(text)) {
        val paragraph = '\n' in match.value
        val piece = text.subSequence(start, match.range.first)
        if (!paragraph && isAbbreviation(piece)) continue
        val pause = if (paragraph) style.paragraphPauseMs else style.sentencePauseMs
        if (piece.isBlank()) {
            // Several breaks in a row: keep only the longest pause.
            pieces.lastOrNull()?.let { (prev, p) -> pieces[pieces.lastIndex] = prev to maxOf(p, pause) }
        } else {
            pieces += piece to pause
        }
        start = match.range.last + 1
    }
    val rest = text.subSequence(start, text.length)
    if (rest.isNotBlank()) pieces += rest to 0
    else pieces.lastOrNull()?.let { (prev, _) -> pieces[pieces.lastIndex] = prev to 0 }
    return pieces.ifEmpty { listOf(text to 0) }
}

private fun isAbbreviation(piece: CharSequence): Boolean {
    val word = piece.trimEnd().split(' ', '\t').lastOrNull()?.removeSuffix(".") ?: return false
    if (!piece.trimEnd().endsWith('.')) return false
    // "Dr", "Mrs", "St" (short and capitalised) or dotted ones like "e.g", "U.S".
    return (word.length in 1..3 && word.first().isUpperCase()) || '.' in word
}
