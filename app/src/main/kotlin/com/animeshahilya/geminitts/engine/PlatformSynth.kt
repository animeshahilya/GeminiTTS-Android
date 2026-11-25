package com.animeshahilya.geminitts.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams audio out of the phone's own TTS engine (Google Speech Services when installed) without
 * playing it.
 *
 * ML Kit's on-device GenAI voices are ordinary Google TTS voices: ML Kit picks the engine voice
 * whose name, minus "-local", matches the ML Kit voice name, then calls speak(). speak() plays the
 * audio itself, so a TTS service built on ML Kit plays every utterance twice. synthesizeToFile()
 * into /dev/null delivers the same audio through onAudioAvailable, as fast as it is made, with no
 * playback.
 *
 * Requests run one at a time ([synthesize] holds [lock]); [stop] and [shutdown] may come from any thread.
 */
class PlatformSynth(context: Context) {

    sealed interface Event {
        data class Begin(val sampleRate: Int, val format: Int, val channels: Int) : Event
        class Audio(val bytes: ByteArray) : Event
        data object Done : Event
        data object Failed : Event
    }

    enum class Result { DONE, CANCELLED, FAILED }

    private val appContext = context.applicationContext
    val enginePackage: String? = pickEngine(appContext)

    private val lock = Any()
    private val queues = ConcurrentHashMap<String, LinkedBlockingQueue<Event>>()
    private val ids = AtomicLong()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var connected = false
    private var currentVoice: Voice? = null
    private var currentLanguage: Locale? = null
    private var sink = File("/dev/null")

    // Installed engine voices by ML Kit name ("hi-in-x-star02"), refreshed at most every MISS_RECHECK_MS
    // so a voice that finishes downloading is picked up. Also read from binder threads (onGetVoices).
    @Volatile private var installed: Map<String, Voice> = emptyMap()
    @Volatile private var installedAt = 0L

    private fun connect() {
        tts?.shutdown()
        currentVoice = null
        currentLanguage = null
        connected = false
        val latch = CountDownLatch(1)
        ready = latch
        val pkg = enginePackage
        if (pkg == null) {
            latch.countDown()
            tts = null
            return
        }
        tts = TextToSpeech(appContext, { status ->
            connected = status == TextToSpeech.SUCCESS
            latch.countDown()
        }, pkg).apply { setOnUtteranceProgressListener(listener) }
    }

    private fun awaitReady(): Boolean = ready.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && connected

    private fun post(id: String?, event: Event) {
        id?.let { queues[it]?.offer(event) }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onBeginSynthesis(id: String?, sampleRate: Int, format: Int, channels: Int) =
            post(id, Event.Begin(sampleRate, format, channels))
        override fun onAudioAvailable(id: String?, audio: ByteArray) = post(id, Event.Audio(audio))
        override fun onDone(id: String?) = post(id, Event.Done)
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = post(id, Event.Failed)
        override fun onError(id: String?, errorCode: Int) = post(id, Event.Failed)
        override fun onStop(id: String?, interrupted: Boolean) = post(id, Event.Failed)
    }

    // Must come after every property above: Kotlin runs initializers in order, and connect() uses listener.
    init {
        connect()
    }

    /**
     * The engine voice for an ML Kit GenAI voice name, or null when it is missing or not downloaded.
     * One engine voice-list call covers every name, and a miss refreshes it at most every 30 s.
     */
    fun installedVoice(name: String?): Voice? {
        if (name.isNullOrBlank()) return null
        installed[name.lowercase()]?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (installedAt != 0L && now - installedAt < MISS_RECHECK_MS) return null
        if (!awaitReady()) return null
        val voices = runCatching { tts?.voices }.getOrNull() ?: return null
        installed = voices
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .associateBy { it.name.removeSuffix("-local").lowercase() }
        installedAt = now
        return installed[name.lowercase()]
    }

    /** Re-reads the installed voices on the next lookup, e.g. after one stopped working. */
    fun forget() {
        installedAt = 0L
        installed = emptyMap()
    }

    /**
     * Synthesizes [text] with [voice], or with the engine's default voice for [language] when null, handing
     * [Event.Begin] and [Event.Audio] to [onEvent] on the calling thread. Blocks until done.
     */
    fun synthesize(
        text: CharSequence,
        voice: Voice?,
        language: Locale,
        isCancelled: () -> Boolean,
        onEvent: (Event) -> Unit
    ): Result = synchronized(lock) {
        repeat(2) { attempt ->
            val engine = tts
            if (engine != null && awaitReady()) {
                val started = start(engine, text, voice, language)
                if (started != null) return pump(started, isCancelled, onEvent)
            }
            if (attempt == 0 && enginePackage != null) {
                Log.w(TAG, "Engine $enginePackage not answering, reconnecting")
                connect()
            }
        }
        Result.FAILED
    }

    /**
     * Google loads a voice model per connection, about 700 ms on a Galaxy S25 Ultra, on the first
     * request. Speaking one short word right away, audio thrown away, keeps that load off the first
     * real utterance. Any request arriving meanwhile waits for it on [lock].
     */
    fun warmUp(voiceName: String?, language: Locale) {
        val voice = installedVoice(voiceName)
        val result = synthesize("OK", voice, language, { false }) {}
        Log.d(TAG, "Warmed up ${voice?.name ?: "default voice"}: $result")
    }

    /** Queues the request. Returns its event queue, or null when the engine refused it. */
    private fun start(
        engine: TextToSpeech,
        text: CharSequence,
        voice: Voice?,
        language: Locale
    ): Pair<String, LinkedBlockingQueue<Event>>? {
        if (voice != null && voice != currentVoice) {
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return null
            currentVoice = voice
            currentLanguage = null
        } else if (voice == null && language != currentLanguage) {
            engine.setLanguage(language)
            currentVoice = null
            currentLanguage = language
        }
        val params = Bundle().apply {
            // Fail instead of silently swapping in another voice, so the cache never mixes voices.
            if (voice != null) putString(DISABLE_VOICE_FALLBACK, "true")
        }
        val id = "u" + ids.incrementAndGet()
        val queue = LinkedBlockingQueue<Event>()
        queues[id] = queue
        var result = engine.synthesizeToFile(text, params, sink, id)
        if (result != TextToSpeech.SUCCESS && sink.path == "/dev/null") {
            sink = File(appContext.cacheDir, "synth-sink.wav")
            result = engine.synthesizeToFile(text, params, sink, id)
        }
        if (result != TextToSpeech.SUCCESS) {
            queues.remove(id)
            return null
        }
        return id to queue
    }

    private fun pump(
        started: Pair<String, LinkedBlockingQueue<Event>>,
        isCancelled: () -> Boolean,
        onEvent: (Event) -> Unit
    ): Result {
        val (id, queue) = started
        try {
            var idleMs = 0L
            while (true) {
                if (isCancelled()) return Result.CANCELLED
                val event = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (event == null) {
                    idleMs += POLL_MS
                    if (idleMs >= STALL_TIMEOUT_MS) {
                        Log.w(TAG, "Engine stalled, giving up on utterance")
                        tts?.stop()
                        return Result.FAILED
                    }
                    continue
                }
                idleMs = 0
                when (event) {
                    Event.Done -> return Result.DONE
                    Event.Failed -> return if (isCancelled()) Result.CANCELLED else Result.FAILED
                    else -> onEvent(event)
                }
            }
        } finally {
            queues.remove(id)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        private const val TAG = "PlatformSynth"
        const val GOOGLE_TTS = "com.google.android.tts"
        private const val DISABLE_VOICE_FALLBACK = "com.google.android.tts:DisableVoiceSelectionFallback"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val POLL_MS = 20L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val MISS_RECHECK_MS = 30_000L

        /** Google's engine when installed, else any other engine that isn't this app. */
        fun pickEngine(context: Context): String? {
            val engines = context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .map { it.serviceInfo.packageName }
                .filter { it != context.packageName }
            return engines.firstOrNull { it == GOOGLE_TTS } ?: engines.firstOrNull()
        }
    }
}
