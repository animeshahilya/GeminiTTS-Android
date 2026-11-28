package com.animeshahilya.geminitts.data

/**
 * In-memory ring buffer of the last utterances spoken by external apps so a blind user can replay
 * slowly, spell character-by-character, or copy an OTP, name, or address.
 *
 * Strictly privacy-guarded: active only when enabled via Quick Settings tile or explicit opt-in,
 * never written to disk or backups, capped at [MAX_ITEMS] for [KEEP_MS], and hidden while locked.
 */
object RecentSpeech {

    data class Item(val text: String, val atMs: Long)

    const val MAX_ITEMS = 15
    const val MAX_CHARS = 2000
    const val KEEP_MS = 5 * 60_000L

    private val itemsDeque = ArrayDeque<Item>()

    fun record(prefs: TtsPrefs, text: CharSequence?, nowMs: Long = System.currentTimeMillis()) {
        if (text == null || !prefs.recentSpeechEnabled) return
        val raw = text.toString().trim()
        if (raw.isEmpty() || raw.startsWith("<speak")) return
        var kept = raw
        if (kept.length > MAX_CHARS) {
            var end = MAX_CHARS
            if (Character.isHighSurrogate(kept[end - 1])) end--
            kept = kept.substring(0, end)
        }
        synchronized(itemsDeque) {
            pruneExpiredLocked(nowMs)
            itemsDeque.removeAll { it.text == kept }
            itemsDeque.addFirst(Item(kept, nowMs))
            while (itemsDeque.size > MAX_ITEMS) itemsDeque.removeLast()
        }
    }

    fun items(nowMs: Long = System.currentTimeMillis(), beforeMs: Long = Long.MAX_VALUE): List<Item> = synchronized(itemsDeque) {
        pruneExpiredLocked(nowMs)
        val out = ArrayList<Item>()
        for (item in itemsDeque) {
            if (nowMs - item.atMs > KEEP_MS) break
            if (item.atMs < beforeMs) out += item
        }
        out
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        while (itemsDeque.isNotEmpty() && nowMs - itemsDeque.last().atMs > KEEP_MS) {
            itemsDeque.removeLast()
        }
    }

    fun clear() {
        synchronized(itemsDeque) { itemsDeque.clear() }
    }
}
