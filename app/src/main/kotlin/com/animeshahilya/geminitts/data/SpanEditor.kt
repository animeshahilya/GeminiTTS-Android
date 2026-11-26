package com.animeshahilya.geminitts.data

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TtsSpan

/**
 * Performs regex and index-range edits over a [CharSequence] while preserving [Spanned] markup
 * (TtsSpans, LocaleSpans). Returns the original [CharSequence] reference untouched if no edits occur.
 */
class SpanEditor(private val original: CharSequence) {

    private var ssb: SpannableStringBuilder? = null
    private var sb: StringBuilder? = null
    private val isSpanned = original is Spanned

    val current: CharSequence
        get() = ssb ?: sb ?: original

    val changed: Boolean
        get() = ssb != null || sb != null

    fun hasTtsSpanOverlap(start: Int, end: Int): Boolean {
        val sp = (ssb ?: original as? Spanned) ?: return false
        return sp.getSpans(start, end, TtsSpan::class.java).any {
            val s = sp.getSpanStart(it)
            val e = sp.getSpanEnd(it)
            s < end && e > start
        }
    }

    fun replaceRange(start: Int, end: Int, replacement: CharSequence, protectTtsSpans: Boolean = true) {
        if (start >= end && replacement.isEmpty()) return
        if (protectTtsSpans && hasTtsSpanOverlap(start, end)) return
        val cur = current
        if (end - start == replacement.length && cur.subSequence(start, end).toString() == replacement.toString()) {
            return
        }
        val targetSsb = ensureBuilder()
        if (targetSsb != null) {
            targetSsb.replace(start, end, replacement)
        } else {
            sb!!.replace(start, end, replacement.toString())
        }
    }

    inline fun replaceMatches(
        regex: Regex,
        protectTtsSpans: Boolean = true,
        transform: (MatchResult) -> CharSequence?
    ) {
        val matches = regex.findAll(current).toList()
        if (matches.isEmpty()) return
        for (i in matches.lastIndex downTo 0) {
            val m = matches[i]
            val start = m.range.first
            val end = m.range.last + 1
            if (protectTtsSpans && hasTtsSpanOverlap(start, end)) continue
            val repl = transform(m) ?: continue
            replaceRange(start, end, repl, protectTtsSpans = false)
        }
    }

    fun result(): CharSequence = when {
        ssb != null -> ssb!!
        sb != null -> sb!!.toString()
        else -> original
    }

    private fun ensureBuilder(): SpannableStringBuilder? {
        if (isSpanned) {
            if (ssb == null) ssb = SpannableStringBuilder(original)
            return ssb
        }
        if (sb == null) sb = StringBuilder(original)
        return null
    }
}
