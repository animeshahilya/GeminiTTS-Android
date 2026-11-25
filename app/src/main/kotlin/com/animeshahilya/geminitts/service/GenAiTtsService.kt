package com.animeshahilya.geminitts.service

import android.content.ComponentCallbacks2
import android.media.AudioFormat
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.TtsSpan
import android.util.Log
import com.animeshahilya.geminitts.audio.PcmProcessor
import com.animeshahilya.geminitts.data.AudioCache
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.VoiceEntry
import com.animeshahilya.geminitts.data.iso3Country
import com.animeshahilya.geminitts.data.iso3Language
import com.animeshahilya.geminitts.data.splitForPauses
import com.animeshahilya.geminitts.engine.PlatformSynth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The system TTS engine. Text goes through the user's dictionary, then to Google's on-device GenAI
 * voice for the caller's language (or that language's standard Google voice while it isn't downloaded). The audio is sped up or
 * pitched with Sonic to match the caller's rate, and phrases are cached at 1x.
 */
class GenAiTtsService : TextToSpeechService() {

    private lateinit var prefs: TtsPrefs
    private lateinit var cache: AudioCache
    private lateinit var synth: PlatformSynth
    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Bumped by onStop; an utterance whose number is stale stops at its next chunk.
    private val generation = AtomicLong()

    override fun onCreate() {
        // super.onCreate() already asks for the default language, so set up first.
        prefs = TtsPrefs(this)
        cache = AudioCache.get(this)
        synth = PlatformSynth(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeminiTts:synthesis")
            .apply { setReferenceCounted(false) }
        super.onCreate()
        scope.launch {
            val locale = Locale.getDefault()
            val entry = prefs.voiceFor(locale)
            synth.warmUp(entry?.name, entry?.locale ?: GenAiVoices.DEFAULT_LOCALE)
        }
        scope.launch { GenAiVoices.refresh(this@GenAiTtsService) }
    }

    override fun onDestroy() {
        scope.cancel()
        synth.shutdown()
        super.onDestroy()
    }

    /** Voices of every language; US English until ML Kit has answered once. */
    private fun knownVoices(): List<VoiceEntry> = prefs.voices.ifEmpty { listOf(VoiceEntry("", GenAiVoices.DEFAULT_LOCALE)) }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val sameLanguage = knownVoices().filter { it.matches(lang) }
        return when {
            sameLanguage.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            sameLanguage.any { it.matches(lang, country) } -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        val locale = prefs.voiceFor(Locale.getDefault())?.locale ?: GenAiVoices.DEFAULT_LOCALE
        return arrayOf(locale.iso3Language(), locale.iso3Country(), "")
    }

    override fun onGetVoices(): List<Voice> = prefs.voices.map { entry ->
        val features = if (synth.installedVoice(entry.name) == null) {
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        } else emptySet()
        Voice(entry.name, entry.locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, features)
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (prefs.voices.any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        prefs.voiceFor(lang, country)?.name

    override fun onStop() {
        generation.incrementAndGet()
        synth.stop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimMemory()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val gen = generation.get()
        val cancelled = { generation.get() != gen }

        val text = prefs.dictionary.apply(request.charSequenceText ?: "")
        // The reading style scales whatever the app asked for, so it works with any app's own settings.
        val style = prefs.style()
        val out = PcmProcessor(
            callback = callback,
            isCancelled = cancelled,
            speed = (request.speechRate / 100f * style.speed).coerceIn(0.25f, 4f),
            pitch = (request.pitch / 100f * style.pitch).coerceIn(0.25f, 4f)
        )
        if (text.isBlank()) {
            out.finish()
            return
        }

        // Try the voice the caller named, then the chosen voice in that language, then any other
        // downloaded GenAI voice in it: each voice is its own download, and apps (Android's Settings
        // included) may name one that isn't there. The GenAI voices are multilingual and switch language
        // by themselves, so next comes the chosen voice in any downloaded language. Google's standard
        // voice for the language is the last resort.
        val requested = prefs.voices.firstOrNull { it.name == request.voiceName }
        val sameLanguage = listOfNotNull(requested) + if (requested != null) {
            prefs.voicesFor(requested.locale)
        } else {
            prefs.voicesFor(request.language, request.country)
        }
        val language = sameLanguage.firstOrNull()?.locale ?: GenAiVoices.DEFAULT_LOCALE
        val voice = (sameLanguage + prefs.voicesAnyLanguage()).firstNotNullOfOrNull { synth.installedVoice(it.name) }
        Log.d(TAG, "request ${request.language}-${request.country} ${request.voiceName ?: ""} -> ${voice?.name ?: "standard ${language.toLanguageTag()} voice"}")

        // Audio is only cached for the GenAI voice, keyed by its engine name and the style's pauses
        // (speed and pitch are applied on replay), and not for text carrying TtsSpans (the spans
        // change how it is read but not its string).
        val hasSpans = text is Spanned && text.getSpans(0, text.length, TtsSpan::class.java).isNotEmpty()
        val key = if (voice != null && prefs.cacheEnabled && !hasSpans) {
            cache.key(text.toString(), "${voice.name}|${style.sentencePauseMs}|${style.paragraphPauseMs}")
        } else null
        val pieces = splitForPauses(text, style)

        wakeLock.acquire(WAKE_LOCK_MS)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            key?.let { cache.get(it) }?.let { clip ->
                out.start(clip.sampleRate, clip.channels)
                out.pcm(clip.pcm)
                out.finish(clip.sampleRate)
                Log.d(TAG, "cache hit, ${voice?.name}, rate ${request.speechRate}, ${SystemClock.elapsedRealtime() - startedAt} ms")
                return
            }
            var result = speak(pieces, voice, language, out, cancelled, key)
            if (result == PlatformSynth.Result.FAILED && voice != null && !out.started && !cancelled()) {
                // The GenAI voice failed before any audio (e.g. not downloaded yet): use the standard voice of that language.
                Log.w(TAG, "GenAI voice ${voice.name} failed, using the standard ${language.toLanguageTag()} voice")
                synth.forget()
                result = speak(pieces, null, language, out, cancelled, null)
            }
            if (result == PlatformSynth.Result.FAILED) callback.error()
        } catch (_: InterruptedIOException) {
            // Stopped by the caller.
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** Synthesizes each piece in turn, with the style's silence after it. */
    private fun speak(
        pieces: List<Pair<CharSequence, Int>>,
        voice: Voice?,
        language: Locale,
        out: PcmProcessor,
        cancelled: () -> Boolean,
        key: String?
    ): PlatformSynth.Result {
        var sampleRate = 24_000
        var channels = 1
        var keep: ByteArrayOutputStream? = key?.let { ByteArrayOutputStream(64 * 1024) }
        fun emit(bytes: ByteArray) {
            out.start(sampleRate, channels)
            out.pcm(bytes)
            keep?.let { if (it.size() + bytes.size > AudioCache.MAX_ENTRY_BYTES) keep = null else it.write(bytes) }
        }
        val startedAt = SystemClock.elapsedRealtime()
        var firstAudioMs = -1L
        var lastAudioAt = startedAt
        var maxGapMs = 0L
        var audioBytes = 0L
        var result = PlatformSynth.Result.DONE
        for ((text, pauseMs) in pieces) {
            result = synth.synthesize(text, voice, language, cancelled) { event ->
                when (event) {
                    is PlatformSynth.Event.Begin -> {
                        if (event.format != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("Engine sent audio format ${event.format}, only 16-bit PCM is supported")
                        }
                        sampleRate = event.sampleRate
                        channels = event.channels
                    }
                    is PlatformSynth.Event.Audio -> {
                        val now = SystemClock.elapsedRealtime()
                        if (firstAudioMs < 0) firstAudioMs = now - startedAt else maxGapMs = maxOf(maxGapMs, now - lastAudioAt)
                        lastAudioAt = now
                        audioBytes += event.bytes.size
                        emit(event.bytes)
                    }
                    else -> Unit
                }
            }
            if (result != PlatformSynth.Result.DONE) break
            // Silence is stretched with the speech, so pauses shrink at higher speeds too.
            if (pauseMs > 0 && out.started) emit(ByteArray(sampleRate * channels * 2 / 1000 * pauseMs))
        }
        Log.d(
            TAG, "${voice?.name ?: "default voice"}: $result, ${pieces.size} piece(s), first audio $firstAudioMs ms, " +
                "longest gap $maxGapMs ms, ${audioBytes * 1000 / (sampleRate * 2L * channels)} ms of audio " +
                "in ${SystemClock.elapsedRealtime() - startedAt} ms"
        )
        if (result == PlatformSynth.Result.DONE) {
            out.finish(sampleRate)
            val audio = keep
            if (key != null && audio != null && !cancelled()) {
                cache.put(key, AudioCache.Clip(sampleRate, channels, audio.toByteArray()))
            }
        }
        return result
    }

    companion object {
        private const val TAG = "GenAiTtsService"
        private const val WAKE_LOCK_MS = 60_000L
    }
}
