package com.animeshahilya.geminitts.ui

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.animeshahilya.geminitts.data.RecentSpeech
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.ui.theme.GeminiTtsTheme
import java.text.BreakIterator

/**
 * Dialog activity opened from the Recent Speech Quick Settings tile or Settings.
 * Lets a blind user replay the last spoken announcements slowly, spell character-by-character,
 * or copy OTPs, names, and addresses cleanly.
 */
class RecentSpeechActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var pendingAction: (() -> Unit)? = null
    @Volatile private var activeBatchPrefix: String = ""
    private var batchSeq = 0L
    private val speakingState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) pendingAction?.also { pendingAction = null }?.invoke()
        }, packageName).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith(activeBatchPrefix) && activeBatchPrefix.isNotEmpty()) {
                        speakingState.value = true
                    }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "${activeBatchPrefix}speech" || utteranceId == "${activeBatchPrefix}spell_last") {
                        speakingState.value = false
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null && utteranceId.startsWith(activeBatchPrefix)) speakingState.value = false
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId != null && utteranceId.startsWith(activeBatchPrefix)) speakingState.value = false
                }
            })
        }
        val beforeMs = intent.getLongExtra(EXTRA_OPENED_AT_MS, Long.MAX_VALUE)
        setContent {
            GeminiTtsTheme {
                RecentSpeechDialog(beforeMs)
            }
        }
    }

    override fun onDestroy() {
        pendingAction = null
        tts?.shutdown()
        super.onDestroy()
    }

    private fun checkLocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        return km.isDeviceLocked || km.isKeyguardLocked
    }

    private fun stopSpeech() {
        pendingAction = null
        activeBatchPrefix = ""
        speakingState.value = false
        tts?.stop()
    }

    @Composable
    private fun RecentSpeechDialog(beforeMs: Long) {
        val prefs = remember { TtsPrefs(this) }
        var isLocked by remember { mutableStateOf(checkLocked()) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var items by remember { mutableStateOf(if (isLocked) emptyList() else RecentSpeech.items(now, beforeMs)) }
        var selected by remember { mutableStateOf<RecentSpeech.Item?>(null) }
        var spellNotice by remember { mutableStateOf<String?>(null) }
        val isSpeaking by speakingState

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val lockedNow = checkLocked()
            val t = System.currentTimeMillis()
            isLocked = lockedNow
            now = t
            items = if (lockedNow) emptyList() else RecentSpeech.items(t, beforeMs)
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stopSpeech() }

        if (selected == null) {
            AlertDialog(
                onDismissRequest = { stopSpeech(); finish() },
                title = { Text("Recent speech", modifier = Modifier.semantics { heading() }) },
                text = {
                    when {
                        isLocked -> Text("Unlock your phone to inspect recent speech.")
                        !prefs.recentSpeechEnabled -> Text("Turn on Recent speech in Settings or add its Quick Settings tile to capture recent utterances in memory.")
                        items.isEmpty() -> Text("Nothing captured in the last 5 minutes yet.")
                        else -> LazyColumn(
                            Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(items) { item ->
                                val rel = DateUtils.getRelativeTimeSpanString(item.atMs, now, DateUtils.SECOND_IN_MILLIS)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = item; spellNotice = null }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text(shorten(item.text), style = MaterialTheme.typography.bodyMedium)
                                    Text(rel.toString(), style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { stopSpeech(); finish() }) { Text("Close") }
                },
                dismissButton = if (items.isNotEmpty()) {
                    { TextButton(onClick = { stopSpeech(); RecentSpeech.clear(); finish() }) { Text("Clear") } }
                } else null
            )
        } else {
            val item = selected!!
            AlertDialog(
                onDismissRequest = { stopSpeech(); selected = null; spellNotice = null },
                title = { Text(shorten(item.text), modifier = Modifier.semantics { heading() }) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runWhenReady { speak(item.text, 1f) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Say again")
                        }
                        OutlinedButton(onClick = { runWhenReady { speak(item.text, 0.6f) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Say slowly")
                        }
                        OutlinedButton(
                            onClick = {
                                val truncated = spellWhenReady(item.text, prefs)
                                spellNotice = if (truncated) "Spelling first $MAX_SPELL_CHARS characters." else null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Spell character by character")
                        }
                        if (isSpeaking) {
                            OutlinedButton(onClick = ::stopSpeech, modifier = Modifier.fillMaxWidth()) {
                                Text("Stop speaking")
                            }
                        }
                        OutlinedButton(onClick = { copyText(item.text) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copy text")
                        }
                        spellNotice?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { stopSpeech(); selected = null; spellNotice = null }) { Text("Back") }
                        Spacer(Modifier.padding(4.dp))
                        TextButton(onClick = { stopSpeech(); finish() }) { Text("Close") }
                    }
                }
            )
        }
    }

    private fun runWhenReady(action: () -> Unit) {
        if (ttsReady) action() else pendingAction = action
    }

    private fun speak(text: String, rate: Float) {
        val engine = tts ?: return
        val prefix = "b${++batchSeq}_"
        activeBatchPrefix = prefix
        speakingState.value = true
        engine.setSpeechRate(rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${prefix}speech")
        engine.setSpeechRate(1f)
    }

    private fun spellWhenReady(text: String, prefs: TtsPrefs): Boolean {
        val (tokens, truncated) = collectSpellTokens(text, prefs)
        runWhenReady { spell(tokens) }
        return truncated
    }

    private fun collectSpellTokens(text: String, prefs: TtsPrefs): Pair<List<String>, Boolean> {
        val dict = prefs.dictionary
        val langTag = java.util.Locale.getDefault().toLanguageTag()
        val chars = BreakIterator.getCharacterInstance()
        chars.setText(text)
        val out = ArrayList<String>()
        var start = chars.first()
        var end = chars.next()
        while (end != BreakIterator.DONE && out.size < MAX_SPELL_CHARS) {
            val ch = text.substring(start, end)
            val mappedByDict = dict.apply(ch, langTag, isSingleChar = true).toString() != ch
            out += if (mappedByDict) ch else spokenSpellToken(ch)
            start = end
            end = chars.next()
        }
        return out to (end != BreakIterator.DONE)
    }

    private fun spell(tokens: List<String>) {
        val engine = tts ?: return
        val prefix = "b${++batchSeq}_"
        activeBatchPrefix = prefix
        engine.stop()
        if (tokens.isEmpty()) return
        speakingState.value = true
        for (i in tokens.indices) {
            val token = tokens[i]
            val id = if (i == tokens.lastIndex) "${prefix}spell_last" else "${prefix}spell"
            if (token.isBlank()) {
                engine.playSilentUtterance(250L, TextToSpeech.QUEUE_ADD, id)
            } else {
                engine.speak(token, TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Recent speech", text)
        val extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clip.description.extras = extras
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shorten(text: String): String {
        if (text.length <= 140) return text
        var end = 140
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…"
    }

    companion object {
        const val EXTRA_OPENED_AT_MS = "opened_at_ms"
        private const val MAX_SPELL_CHARS = 300

        private val SPELL_SYMBOLS = mapOf(
            "." to "dot",
            "," to "comma",
            ":" to "colon",
            ";" to "semicolon",
            "-" to "dash",
            "_" to "underscore",
            "/" to "slash",
            "\\" to "backslash",
            "@" to "at",
            "!" to "exclamation mark",
            "?" to "question mark",
            "(" to "left parenthesis",
            ")" to "right parenthesis",
            "[" to "left bracket",
            "]" to "right bracket",
            "{" to "left brace",
            "}" to "right brace",
            "*" to "star",
            "#" to "hash",
            "+" to "plus",
            "=" to "equals",
            "&" to "and",
            "%" to "percent",
            "$" to "dollar",
            "\"" to "quote",
            "'" to "apostrophe",
            "~" to "tilde",
            "`" to "backtick",
            "|" to "pipe",
            "<" to "less than",
            ">" to "greater than",
            "^" to "caret",
            "…" to "ellipsis",
            "—" to "em dash",
            "–" to "en dash",
            "•" to "bullet",
            "°" to "degree",
            "₹" to "rupee",
            "€" to "euro",
            "£" to "pound",
            "¥" to "yen"
        )

        /** Maps raw punctuation (which GenAI voices skip), whitespace, and uppercase letters to spoken tokens. */
        fun spokenSpellToken(ch: String): String {
            when (ch) {
                " " -> return "space"
                "\n", "\r\n", "\r" -> return "new line"
                "\t" -> return "tab"
            }
            if (ch.isBlank()) return ch
            SPELL_SYMBOLS[ch]?.let { return it }
            if (ch.codePointCount(0, ch.length) == 1 && Character.isUpperCase(ch.codePointAt(0))) {
                return "capital $ch"
            }
            return ch
        }
    }
}
