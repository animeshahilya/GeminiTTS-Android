package com.example.genaittsdemo.service

import android.content.ComponentCallbacks2
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Bundle
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.genaittsdemo.audio.PcmProcessor
import com.example.genaittsdemo.data.AccessibilitySpanParser
import com.example.genaittsdemo.data.GeminiVoiceCatalog
import com.example.genaittsdemo.data.GeminiVoiceProfile
import com.example.genaittsdemo.data.NpuAudioCache
import com.example.genaittsdemo.data.TtsCustomizationPreferences
import com.google.mlkit.genai.texttospeech.*
import kotlinx.coroutines.*
import java.io.File
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class GenAiTtsService : TextToSpeechService() {

    private val TAG = "GenAiTtsService"

    // Dedicated high-priority single-thread executor for low-latency NPU/TTS operations
    private val ttsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            runnable.run()
        }, "GenAiTts-Worker").apply {
            priority = Thread.MAX_PRIORITY
        }
    }

    private val serviceScope = CoroutineScope(ttsExecutor.asCoroutineDispatcher() + SupervisorJob())
    private lateinit var preferences: TtsCustomizationPreferences
    private lateinit var audioCache: NpuAudioCache

    private var speechSynthesizer: SpeechSynthesizer? = null
    private var activeGenAiVoice: VoiceInfo? = null
    private var supportedVoices: List<VoiceInfo> = emptyList()
    private var fallbackSystemTts: TextToSpeech? = null

    // Active synthesis job tracker for coroutine cancellation
    private val activeJob = AtomicReference<Job?>(null)

    // Active fallback latch for immediate cancellation of external engine synthesis
    private val activeFallbackLatch = AtomicReference<CountDownLatch?>(null)

    // Lock-free generation counter for sub-millisecond cancellation on fast TalkBack swipes
    private val cancellation = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        preferences = TtsCustomizationPreferences(applicationContext)
        audioCache = NpuAudioCache.getInstance(applicationContext)
        initFallbackTts()
        initSynthesizer()
    }

    private fun initFallbackTts() {
        try {
            // Query available external engines to prevent calling ourselves recursively
            val dummyTts = TextToSpeech(applicationContext, null)
            val fallbackPackage = dummyTts.engines
                .map { it.name }
                .firstOrNull { it != packageName && it != "com.example.genaittsdemo" }
                ?: "app.grapheneos.speechservices"
            dummyTts.shutdown()

            Log.i(TAG, "Selected secondary engine for on-device fallback: $fallbackPackage")
            fallbackSystemTts = TextToSpeech(applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    fallbackSystemTts?.language = Locale.US
                }
            }, fallbackPackage)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize fallback TTS", e)
        }
    }

    private fun initSynthesizer() {
        try {
            val options = speechSynthesizerOptions(Locale.US) {
                setVoiceFallbackEnabled(true)
                setExecutor(ttsExecutor)
            }
            val client = SpeechSynthesis.getClient(options)
            speechSynthesizer = client

            // Pre-warm client and fetch voice metadata asynchronously
            serviceScope.launch {
                try {
                    val voices = client.getSupportedVoices(Locale.US)
                    supportedVoices = voices
                    val targetGender = preferences.preferredGender
                    activeGenAiVoice = voices.firstOrNull { it.gender.name.equals(targetGender, ignoreCase = true) }
                        ?: voices.firstOrNull()
                    Log.i(TAG, "Initialized GenAI NPU service with ${voices.size} voices. Selected: ${activeGenAiVoice?.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching voices during initialization", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechSynthesis client", e)
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang.equals("eng", ignoreCase = true) || lang.equals("en", ignoreCase = true)) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        val locale = activeGenAiVoice?.locale ?: Locale.US
        val lang = try { locale.isO3Language } catch (_: Exception) { "eng" }
        val country = try { locale.isO3Country } catch (_: Exception) { "USA" }
        return arrayOf(lang, country, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (voiceName.contains("gemini", ignoreCase = true) ||
            voiceName.contains("genai", ignoreCase = true) ||
            voiceName.contains("en", ignoreCase = true) ||
            GeminiVoiceCatalog.findById(voiceName) != null ||
            GeminiVoiceCatalog.findByName(voiceName) != null) {
            return TextToSpeech.SUCCESS
        }
        return if (supportedVoices.any { it.name.equals(voiceName, ignoreCase = true) }) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.SUCCESS
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        val catalogProfile = GeminiVoiceCatalog.findById(voiceName) ?: GeminiVoiceCatalog.findByName(voiceName)
        if (catalogProfile != null) {
            preferences.preferredVoiceName = catalogProfile.name
        }
        val match = supportedVoices.firstOrNull { it.name.equals(voiceName, ignoreCase = true) }
        if (match != null) {
            activeGenAiVoice = match
        }
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val preferred = GeminiVoiceCatalog.findByName(preferences.preferredVoiceName)
            ?: GeminiVoiceCatalog.getDefaultVoiceForGender(preferences.preferredGender)
        return supportedVoices.firstOrNull { it.name.contains(preferred.name, ignoreCase = true) }?.name
            ?: supportedVoices.firstOrNull { it.gender.name.equals(preferred.gender, ignoreCase = true) }?.name
            ?: preferred.id
    }

    /**
     * Called whenever TalkBack or Android requests immediate speech cessation.
     * Increments the cancellation generation counter, giving sub-millisecond aborts
     * without deadlock risk.
     */
    override fun onStop() {
        cancellation.incrementAndGet()
        activeFallbackLatch.getAndSet(null)?.countDown()
        val job = activeJob.getAndSet(null)
        job?.cancel()

        try {
            speechSynthesizer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechSynthesizer", e)
        }
    }

    override fun onGetVoices(): List<Voice> {
        val voiceList = mutableListOf<Voice>()
        // 1. Add all official Gemini catalog voices
        for (profile in GeminiVoiceCatalog.ALL_VOICES) {
            voiceList.add(profile.toAospVoice())
        }
        // 2. Also register any hardware-reported voices
        for (v in supportedVoices) {
            if (voiceList.none { it.name.equals(v.name, ignoreCase = true) }) {
                voiceList.add(
                    Voice(
                        v.name,
                        v.locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_VERY_LOW,
                        false,
                        setOf("gemini", "ondevice", "npu")
                    )
                )
            }
        }
        return voiceList
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        val currentGen = cancellation.get()
        val isCancelled = { cancellation.get() != currentGen }

        PerformanceScope(applicationContext).use { perfScope ->
            perfScope.beginWork()

            // 1. Accessibility Span Extraction: Translate TtsSpan objects (phone, money, date, etc.)
            val rawCharSequence = request.charSequenceText
            val spanExpandedText = AccessibilitySpanParser.parse(rawCharSequence)
            if (spanExpandedText.isBlank()) {
                callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            // 2. Text Normalization: TalkBack regex dictionaries, punctuation verbosity, acronyms
            val filteredText = preferences.applyFilters(spanExpandedText)
            if (filteredText.isBlank()) {
                callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            val sampleRate = 24000
            val channelCount = 1
            val client = speechSynthesizer
            val voice = activeGenAiVoice

            val voiceProfile = GeminiVoiceCatalog.findByName(preferences.preferredVoiceName)
                ?: GeminiVoiceCatalog.getDefaultVoiceForGender(preferences.preferredGender)

            // Map TalkBack speech rate and pitch (100 = 1.0f) with persona tonal tuning
            val speechRate = (request.speechRate / 100.0f).coerceIn(0.5f, 3.5f)
            val pitch = ((request.pitch / 100.0f) * voiceProfile.pitchMultiplier).coerceIn(0.5f, 2.0f)

            // Configure PCM Processor with Sonic time-stretching and reserve buffer
            val pcmProcessor = PcmProcessor(
                callback = callback,
                isCancelled = isCancelled,
                speed = speechRate,
                pitch = pitch
            )
            pcmProcessor.start(sampleRate, channelCount)

            // 3. High-Speed Cache Lookup: Serve common accessibility phrases in <5ms
            val voiceKey = voice?.name ?: voiceProfile.name
            val cacheKey = audioCache.createCacheKey(filteredText, voiceKey)
            val cachedPcm = audioCache.get(cacheKey)

            if (cachedPcm != null) {
                try {
                    pcmProcessor.pcm(cachedPcm)
                    pcmProcessor.stop()
                    perfScope.report()
                    Log.d(TAG, "Served utterance from NPU Cache via Sonic (speed=${speechRate}x): '${filteredText.take(30)}'")
                } catch (_: InterruptedIOException) {
                    Log.d(TAG, "Cached utterance stopped by user")
                }
                return
            }

            // Fallback to high-fidelity on-device system engine if GenAI NPU is initializing
            if (client == null || voice == null) {
                Log.d(TAG, "GenAI NPU initializing, using on-device synthesis fallback for: '${filteredText.take(30)}'")
                synthesizeWithFallback(filteredText, pcmProcessor, perfScope, cacheKey)
                return
            }

            // 4. Live NPU Synthesis with PcmProcessor Streaming & ADPF Boost
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build()

            // Long utterances are split by line breaks and sentence boundaries to pipeline synthesis
            val sentenceChunks = splitIntoSentences(filteredText)
            val accumulator = NpuAudioCache.AudioAccumulator()

            runBlocking(serviceScope.coroutineContext) {
                val job = coroutineContext[Job]
                activeJob.set(job)

                try {
                    for (i in sentenceChunks.indices) {
                        if (isCancelled() || !isActive) break

                        val chunk = sentenceChunks[i]
                        val queueMode = if (i == 0) {
                            SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH
                        } else {
                            SpeechSynthesizerRequest.QueueMode.QUEUE_ADD
                        }

                        val synthRequest = SpeechSynthesizerRequest(
                            input = SpeechSynthesizerRequest.Input.Text(chunk, voice),
                            audioAttributes = audioAttributes,
                            queueMode = queueMode
                        )

                        client.synthesize(synthRequest).collect { response ->
                            if (isCancelled()) {
                                currentCoroutineContext().cancel()
                                return@collect
                            }

                            when (response) {
                                is SpeechSynthesizerResponse.AudioAvailable -> {
                                    accumulator.append(response.audio)
                                    pcmProcessor.pcm(response.audio)
                                    perfScope.report()
                                    perfScope.beginWork()
                                }
                                is SpeechSynthesizerResponse.StatusUpdate -> {
                                    if (response.status == SpeechSynthesizerResponse.Status.DONE ||
                                        response.status == SpeechSynthesizerResponse.Status.STOP) {
                                        // Segment completed
                                    }
                                }
                                is SpeechSynthesizerResponse.Error -> {
                                    Log.e(TAG, "NPU Synthesis error code: ${response.errorCode}")
                                }
                            }
                        }
                    }

                    pcmProcessor.stop()
                    perfScope.report()

                    // Cache complete utterance if successfully synthesized and not cancelled
                    if (!isCancelled() && accumulator.size > 0) {
                        audioCache.put(cacheKey, accumulator.toByteArray())
                    }
                } catch (e: InterruptedIOException) {
                    Log.d(TAG, "Utterance cancelled via PcmProcessor: '${filteredText.take(20)}'")
                } catch (e: CancellationException) {
                    Log.d(TAG, "Utterance cancelled: '${filteredText.take(20)}'")
                } catch (e: Exception) {
                    Log.e(TAG, "Synthesis exception in TalkBack queue", e)
                    callback.error()
                } finally {
                    activeJob.compareAndSet(job, null)
                }
            }
        }
    }

    /**
     * Splits long accessibility passages into sentence/clause/line-break segments for pipelined NPU synthesis.
     * Prevents long-tail latency on large notifications or multi-line documents.
     */
    private fun splitIntoSentences(text: String): List<String> {
        if (text.length <= 140) return listOf(text)

        // Split on explicit line breaks first (paragraphs, list items)
        val lineSections = text.split(Regex("[\r\n]+")).map { it.trim() }.filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        val sentenceRegex = Regex("(?<=[.!?;\u3002\uff01\uff1f])\\s+")

        for (section in lineSections) {
            if (section.length <= 140) {
                result.add(section)
            } else {
                val rawParts = section.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }
                if (rawParts.size <= 1) {
                    result.add(section)
                } else {
                    var buffer = StringBuilder()
                    for (part in rawParts) {
                        if (buffer.isNotEmpty() && (buffer.length + part.length > 120)) {
                            result.add(buffer.toString())
                            buffer = StringBuilder()
                        }
                        if (buffer.isNotEmpty()) buffer.append(" ")
                        buffer.append(part)
                    }
                    if (buffer.isNotEmpty()) {
                        result.add(buffer.toString())
                    }
                }
            }
        }

        return if (result.isEmpty()) listOf(text) else result
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory received: level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            audioCache.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory received - flushing in-memory audio caches")
        audioCache.trimMemory()
    }

    private fun synthesizeWithFallback(
        text: String,
        pcmProcessor: PcmProcessor,
        perfScope: PerformanceScope,
        cacheKey: String
    ) {
        val tts = fallbackSystemTts
        if (tts == null) {
            pcmProcessor.stop()
            return
        }
        val tempFile = File(cacheDir, "tts_fallback_${System.currentTimeMillis()}.wav")
        val latch = CountDownLatch(1)
        activeFallbackLatch.set(latch)
        try {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val utteranceId = "fallback_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    latch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    latch.countDown()
                }
            })
            val res = tts.synthesizeToFile(text, params, tempFile, utteranceId)
            Log.d(TAG, "synthesizeToFile queued with result: $res")
            if (res == TextToSpeech.SUCCESS) {
                val ok = latch.await(4, TimeUnit.SECONDS)
                Log.d(TAG, "synthesizeToFile latch=$ok, file exists=${tempFile.exists()}, length=${tempFile.length()}")
                if (tempFile.exists() && tempFile.length() > 44) {
                    val bytes = tempFile.readBytes()
                    // Strip 44-byte standard RIFF WAV header for raw 16-bit PCM
                    val pcmBytes = bytes.copyOfRange(44, bytes.size)
                    pcmProcessor.pcm(pcmBytes)
                    pcmProcessor.stop()
                    perfScope.report()
                    audioCache.put(cacheKey, pcmBytes)
                    Log.d(TAG, "Synthesized and cached via on-device engine: '${text.take(30)}'")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback synthesis failed", e)
        } finally {
            activeFallbackLatch.compareAndSet(latch, null)
            try { tempFile.delete() } catch (_: Exception) {}
        }
        pcmProcessor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ttsExecutor.shutdown()
        try {
            speechSynthesizer?.close()
        } catch (_: Exception) {}
        try {
            fallbackSystemTts?.shutdown()
        } catch (_: Exception) {}
    }
}
