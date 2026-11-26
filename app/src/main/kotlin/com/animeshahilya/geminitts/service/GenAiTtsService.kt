package com.animeshahilya.geminitts.service

import android.content.ComponentCallbacks2
import android.media.AudioFormat
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.LocaleSpan
import android.text.style.TtsSpan
import android.util.Log
import com.animeshahilya.geminitts.audio.LeadingSilenceTrimmer
import com.animeshahilya.geminitts.audio.PcmProcessor
import com.animeshahilya.geminitts.data.AudioCache
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.RecentSpeech
import com.animeshahilya.geminitts.data.TextPipeline
import com.animeshahilya.geminitts.data.TextPreset
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.VoiceEntry
import com.animeshahilya.geminitts.data.iso3Country
import com.animeshahilya.geminitts.data.iso3Language
import com.animeshahilya.geminitts.data.resolveVoicePlan
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The system TTS engine. Applies dictionary & blind-friendly text rules, honors per-language and
 * per-app overrides, sleep timer (never silencing screen readers), recent-speech capture, and
 * streams Google's on-device GenAI voices through Sonic + AudioOptimizer.
 */
class GenAiTtsService : TextToSpeechService() {

    private lateinit var prefs: TtsPrefs
    private lateinit var cache: AudioCache
    private lateinit var synth: PlatformSynth
    private lateinit var wakeLock: PowerManager.WakeLock
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Bumped by onStop; an utterance whose number is stale stops at its next chunk.
    private val generation = AtomicLong()

    // Languages already downloaded (or tried) in the background since the service started.
    private val downloads: MutableSet<Locale> = ConcurrentHashMap.newKeySet()

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
            fun warmUp(): Boolean {
                val entry = prefs.voiceFor(Locale.getDefault())
                return synth.warmUp(entry?.name, entry?.locale ?: GenAiVoices.DEFAULT_LOCALE)
            }
            val warmed = warmUp()
            GenAiVoices.refresh(this@GenAiTtsService)
            if (!warmed && prefs.voices.isNotEmpty()) {
                synth.forget()
                warmUp()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        synth.shutdown()
        super.onDestroy()
    }

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

    override fun onGetVoices(): List<Voice> = prefs.voices
        .filter { prefs.isLocaleEnabled(it.locale) }
        .ifEmpty { prefs.voices }
        .map { entry ->
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

        val caller = CallerTracker.callerPackage(this, request.callerUid)
        if (caller != null) {
            if (prefs.isSleepStopped() && !CallerTracker.isSpokenAccessibilityService(this, caller)) {
                callback.error(TextToSpeech.ERROR_SERVICE)
                return
            }
            prefs.noteSeenApp(caller)
            RecentSpeech.record(prefs, request.charSequenceText)
        }

        val appProfile = caller?.let { prefs.appProfiles[it] }
        val rawText = request.charSequenceText ?: ""

        // Honor a whole-utterance LocaleSpan (e.g. TalkBack's spoken-language switcher).
        val wholeLocale = wholeTextLocale(rawText)
        val reqLang = wholeLocale?.iso3Language() ?: request.language
        val reqCountry = wholeLocale?.iso3Country() ?: request.country

        val plan = resolveVoicePlan(
            voices = prefs.voices,
            globalVoiceId = prefs.voiceId,
            appVoiceId = appProfile?.voiceId,
            langOverrideLookup = { locale -> prefs.languageOverrideFor(locale)?.voiceId },
            wholeLocale = wholeLocale,
            reqLang = reqLang,
            reqCountry = reqCountry,
            requestedVoiceName = request.voiceName,
            defaultLocale = GenAiVoices.DEFAULT_LOCALE,
            isInternalCaller = caller == null
        )
        val language = plan.language
        val langOverride = prefs.languageOverrideFor(language)
        val voice = plan.firstInstalled { synth.installedVoice(it.name) }

        plan.wanted?.let { wanted ->
            val using = voice?.name?.removeSuffix("-local")
            if (!using.equals(wanted.name, ignoreCase = true)) downloadLater(wanted)
        }

        val style = prefs.style(appProfile?.styleId)
        val isExternalCaller = caller != null
        val callerRate = if ((isExternalCaller && prefs.lockSpeed) || request.speechRate <= 0) 1f else request.speechRate / 100f
        val callerPitch = if ((isExternalCaller && prefs.lockPitch) || request.pitch <= 0) 1f else request.pitch / 100f
        val callerVolume = if (isExternalCaller && prefs.lockVolume) 1f else readVolumeParam(request.params)

        val effectiveSpeed = (callerRate * prefs.baseSpeed * style.speed *
            (langOverride?.speed ?: 1f) * (appProfile?.speed ?: 1f)).coerceIn(0.25f, 4f)
        val effectivePitch = (callerPitch * prefs.basePitch * style.pitch *
            (langOverride?.pitch ?: 1f) * (appProfile?.pitch ?: 1f)).coerceIn(0.25f, 4f)
        val effectiveVolume = (callerVolume * (prefs.volumePercent / 100f) *
            (langOverride?.volume ?: 1f) * (appProfile?.volume ?: 1f)).coerceIn(0f, 2f)

        val textOpts = prefs.textOptions.applyAppPreset(appProfile?.textPreset ?: TextPreset.INHERIT)
        val text = TextPipeline.process(rawText, textOpts, prefs.dictionary, language.toLanguageTag())
        val isSingleChar = prefs.levelerEnabled && TextPipeline.isSingleCharacter(text)

        val out = PcmProcessor(
            callback = callback,
            isCancelled = cancelled,
            speed = effectiveSpeed,
            pitch = effectivePitch,
            volume = effectiveVolume,
            clarity = prefs.clarityPreset,
            leveler = prefs.levelerEnabled,
            trimLeadingSilence = prefs.trimSilence,
            isSingleChar = isSingleChar
        )
        if (text.isBlank()) {
            out.finish()
            return
        }

        Log.d(
            TAG,
            "request ${reqLang}-${reqCountry} ${request.voiceName ?: ""} (caller=${caller ?: "self"}) -> " +
                "${voice?.name ?: "standard ${language.toLanguageTag()} voice"} speed=$effectiveSpeed vol=$effectiveVolume"
        )

        val hasSpans = text is Spanned && text.getSpans(0, text.length, TtsSpan::class.java).isNotEmpty()
        if (synth.engineVersion != 0L && synth.engineVersion != prefs.engineVersion) {
            cache.clear()
            prefs.engineVersion = synth.engineVersion
        }
        val key = if (voice != null && prefs.cacheEnabled && !hasSpans) {
            cache.key(
                text.toString(),
                "${voice.name}|${synth.engineVersion}|${style.sentencePauseMs}|${style.paragraphPauseMs}|${textOpts.fingerprint}|${prefs.trimSilence}"
            )
        } else null
        val pieces = splitForPauses(text, style)

        val prevPriority = Process.getThreadPriority(Process.myTid())
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        wakeLock.acquire(WAKE_LOCK_MS)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            key?.let { cache.get(it) }?.let { clip ->
                out.start(clip.sampleRate, clip.channels)
                out.pcm(clip.pcm)
                out.finish(clip.sampleRate)
                Log.d(TAG, "cache hit, ${voice?.name}, ${SystemClock.elapsedRealtime() - startedAt} ms")
                return
            }
            var result = speak(pieces, voice, language, out, cancelled, key)
            if (result == PlatformSynth.Result.FAILED && voice != null && !out.started && !cancelled()) {
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
            runCatching { Process.setThreadPriority(prevPriority) }
        }
    }

    /** Downloads [entry] in the background, once per language while the service runs, if the user allows it. */
    private fun downloadLater(entry: VoiceEntry) {
        if (!prefs.autoDownload || !downloads.add(entry.locale)) return
        scope.launch {
            val ok = GenAiVoices.download(entry.locale, entry.name)
            Log.d(TAG, "Background download of ${entry.name}: ${if (ok) "done" else "failed"}")
            if (ok) synth.forget()
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
        for ((index, piece) in pieces.withIndex()) {
            val (text, pauseMs) = piece
            if (index > 0) out.resetLeadingTrim()
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
            if (pauseMs > 0 && out.started) {
                val pauseFrames = (sampleRate.toLong() * pauseMs / 1000L).toInt().coerceAtLeast(0)
                if (pauseFrames > 0) emit(ByteArray(pauseFrames * channels * 2))
            }
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
                val rawBytes = audio.toByteArray()
                val stored = if (prefs.trimSilence) {
                    LeadingSilenceTrimmer.trimClip(rawBytes, sampleRate, channels, trimLead = true, trimTail = true)
                } else rawBytes
                if (stored.isNotEmpty()) cache.put(key, AudioCache.Clip(sampleRate, channels, stored))
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun readVolumeParam(params: Bundle?): Float {
        if (params == null) return 1f
        val v = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME) ?: return 1f
        val parsed = when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: 1f
            else -> 1f
        }
        return if (parsed.isFinite()) parsed.coerceIn(0f, 1f) else 1f
    }

    private fun wholeTextLocale(cs: CharSequence): Locale? {
        if (cs !is Spanned) return null
        var first = 0
        var last = cs.length
        while (first < last && cs[first].isWhitespace()) first++
        while (last > first && cs[last - 1].isWhitespace()) last--
        if (first == last) return null
        return cs.getSpans(0, cs.length, LocaleSpan::class.java)
            .firstOrNull { it.locale != null && cs.getSpanStart(it) <= first && cs.getSpanEnd(it) >= last }
            ?.locale
    }

    companion object {
        private const val TAG = "GenAiTtsService"
        private const val WAKE_LOCK_MS = 60_000L
    }
}
