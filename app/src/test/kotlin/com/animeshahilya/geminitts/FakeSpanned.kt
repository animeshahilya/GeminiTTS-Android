package com.animeshahilya.geminitts

import android.text.Spanned
import android.text.style.TtsSpan

/** Lightweight JVM test [Spanned] implementation since android.jar stubs SpannableString on host JVM. */
class FakeSpanned(
    private val text: String,
    private val spans: List<SpanEntry>
) : Spanned, CharSequence by text {

    data class SpanEntry(val span: Any, val start: Int, val end: Int, val flags: Int = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    override fun toString(): String = text

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        val sub = text.substring(startIndex, endIndex)
        val clipped = spans.mapNotNull { e ->
            val s = maxOf(e.start, startIndex) - startIndex
            val end = minOf(e.end, endIndex) - startIndex
            if (s < end) SpanEntry(e.span, s, end, e.flags) else null
        }
        return FakeSpanned(sub, clipped)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>?): Array<T> {
        val matched = spans.filter { e ->
            e.start < end && e.end > start && (type == null || type.isInstance(e.span))
        }.map { it.span as T }
        return if (type != null) {
            val arr = java.lang.reflect.Array.newInstance(type, matched.size) as Array<T>
            matched.forEachIndexed { i, v -> arr[i] = v }
            arr
        } else {
            val arr = java.lang.reflect.Array.newInstance(Any::class.java, matched.size) as Array<T>
            matched.forEachIndexed { i, v -> arr[i] = v }
            arr
        }
    }

    override fun getSpanStart(tag: Any?): Int = spans.firstOrNull { it.span === tag }?.start ?: -1
    override fun getSpanEnd(tag: Any?): Int = spans.firstOrNull { it.span === tag }?.end ?: -1
    override fun getSpanFlags(tag: Any?): Int = spans.firstOrNull { it.span === tag }?.flags ?: 0
    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int = limit

    companion object {
        fun withTtsSpan(text: String, start: Int, end: Int): FakeSpanned =
            FakeSpanned(text, listOf(SpanEntry(TtsSpan("android.type.telephone", null), start, end)))
    }
}
