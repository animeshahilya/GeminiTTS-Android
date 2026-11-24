package com.example.genaittsdemo.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.genaittsdemo.audio.Sonic
import com.example.genaittsdemo.service.PerformanceScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

enum class EngineMode {
    GENAI_NPU,
    SYSTEM_ON_DEVICE
}

sealed interface ModelAvailability {
    object Checking : ModelAvailability
    data class Ready(val voiceName: String, val gender: String) : ModelAvailability
    data class DownloadRequired(val voiceName: String) : ModelAvailability
    data class Downloading(val bytesDownloaded: Long) : ModelAvailability
    data class Unavailable(val reason: String) : ModelAvailability
}

enum class PlaybackStatus {
    IDLE,
    SYNTHESIZING,
    PLAYING,
    ERROR
}

data class TtsEngineState(
    val activeEngine: EngineMode = EngineMode.GENAI_NPU,
    val modelStatus: ModelAvailability = ModelAvailability.Checking,
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val statusMessage: String = "Initializing on-device speech engines...",
    val latencyMs: Long? = null,           // Time to First Audio (TTFA)
    val totalTimeMs: Long? = null,         // Total synthesis duration
    val chunksProcessed: Int = 0,
    val isCacheHit: Boolean = false,
    val realTimeFactor: Float? = null,     // Lower is faster (e.g. 0.15 = 6.6x real-time)
    val deviceModel: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val detectedSoc: String = "",
    val thermalStatus: String = "Nominal",
    val availableVoices: List<String> = emptyList(),
    val selectedVoiceIndex: Int = 0,
    val cacheStats: CacheStats? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

class GenAiTtsManager(private val context: Context) {

    private val TAG = "GenAiTtsManager"

    // High priority single-thread executor for low latency
    private val ttsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            runnable.run()
        }, "GenAiTtsManager-Worker").apply {
            priority = Thread.MAX_PRIORITY
        }
    }

    private val coroutineScope = CoroutineScope(ttsExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val audioCache = NpuAudioCache.getInstance(context)

    private val _state = MutableStateFlow(
        TtsEngineState(
            detectedSoc = detectSoc(),
            thermalStatus = queryThermalStatus(),
            cacheStats = audioCache.getStats()
        )
    )
    val state: StateFlow<TtsEngineState> = _state.asStateFlow()

    private var speechSynthesizer: SpeechSynthesizer? = null
    private var genAiVoiceList: List<VoiceInfo> = emptyList()
    private var selectedGenAiVoice: VoiceInfo? = null

    private var systemTts: TextToSpeech? = null
    private var systemVoiceList: List<android.speech.tts.Voice> = emptyList()
    private var selectedSystemVoice: android.speech.tts.Voice? = null

    private var synthesisJob: Job? = null
    private var downloadJob: Job? = null
    private var cachePlaybackTrack: AudioTrack? = null

    init {
        initEngines()
    }

    private fun detectSoc(): String {
        return try {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else ""

            when {
                soc.contains("SM8750", ignoreCase = true) -> "Snapdragon 8 Elite (Hexagon NPU)"
                soc.contains("SM8650", ignoreCase = true) -> "Snapdragon 8 Gen 3 (Hexagon NPU)"
                soc.contains("SM8550", ignoreCase = true) -> "Snapdragon 8 Gen 2 (Hexagon NPU)"
                soc.isNotBlank() -> "$soc (Hardware NPU)"
                Build.HARDWARE.contains("zuma", ignoreCase = true) -> "Google Tensor G3 (TPU/NPU)"
                Build.HARDWARE.contains("zumapro", ignoreCase = true) -> "Google Tensor G4 (TPU/NPU)"
                Build.HARDWARE.contains("qcom", ignoreCase = true) -> "Qualcomm Snapdragon NPU"
                Build.HARDWARE.contains("exynos", ignoreCase = true) -> "Samsung Exynos NPU"
                else -> "${Build.HARDWARE} / ${Build.BOARD}"
            }
        } catch (_: Exception) {
            "On-Device NPU Accelerator"
        }
    }

    private fun queryThermalStatus(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Cool (Nominal)"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
                    else -> "Normal"
                }
            } else "Normal"
        } catch (_: Exception) {
            "Normal"
        }
    }

    private fun initEngines() {
        coroutineScope.launch {
            initSystemTts()
            initGenAiTts()
        }
    }

    private fun initGenAiTts() {
        try {
            val options = speechSynthesizerOptions(Locale.US) {
                setVoiceFallbackEnabled(true)
                setExecutor(ttsExecutor)
            }
            val client = SpeechSynthesis.getClient(options)
            speechSynthesizer = client

            coroutineScope.launch {
                try {
                    val voices = client.getSupportedVoices(Locale.US)
                    genAiVoiceList = voices
                    Log.d(TAG, "Found ${voices.size} GenAI voices")

                    if (voices.isNotEmpty()) {
                        selectedGenAiVoice = voices.first()
                        _state.update {
                            it.copy(
                                availableVoices = voices.map { v -> "${v.name} (${v.gender})" },
                                selectedVoiceIndex = 0
                            )
                        }
                        checkModelStatus(selectedGenAiVoice!!)
                    } else {
                        fallbackToSystemTts("No GenAI voices registered for Locale.US")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query GenAI voices", e)
                    fallbackToSystemTts("GenAI initialization failed: ${e.localizedMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SpeechSynthesis client", e)
            fallbackToSystemTts("GenAI client error: ${e.localizedMessage}")
        }
    }

    suspend fun checkModelStatus(voice: VoiceInfo) {
        val client = speechSynthesizer ?: return
        _state.update { it.copy(modelStatus = ModelAvailability.Checking, statusMessage = "Checking on-device NPU model...") }

        try {
            val status = client.checkStatus(voice)
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    _state.update {
                        it.copy(
                            activeEngine = EngineMode.GENAI_NPU,
                            modelStatus = ModelAvailability.Ready(voice.name, voice.gender.name),
                            statusMessage = "On-Device GenAI Voice Ready (NPU Acceleration active)"
                        )
                    }
                }
                FeatureStatus.DOWNLOADABLE -> {
                    _state.update {
                        it.copy(
                            modelStatus = ModelAvailability.DownloadRequired(voice.name),
                            statusMessage = "Voice model requires local download"
                        )
                    }
                }
                FeatureStatus.DOWNLOADING -> {
                    _state.update {
                        it.copy(
                            modelStatus = ModelAvailability.Downloading(0L),
                            statusMessage = "Voice model currently downloading..."
                        )
                    }
                }
                FeatureStatus.UNAVAILABLE -> {
                    fallbackToSystemTts("On-device GenAI unavailable on current system image. Using System Neural TTS.")
                }
                else -> {
                    fallbackToSystemTts("Status code: $status. Using System Neural TTS.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus failed", e)
            fallbackToSystemTts("Status check failed: ${e.localizedMessage}")
        }
    }

    fun downloadSelectedModel() {
        val client = speechSynthesizer ?: return
        val voice = selectedGenAiVoice ?: return

        downloadJob?.cancel()
        downloadJob = coroutineScope.launch {
            _state.update {
                it.copy(
                    modelStatus = ModelAvailability.Downloading(0L),
                    statusMessage = "Downloading on-device model..."
                )
            }

            try {
                client.download(voice).collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadProgress -> {
                            _state.update {
                                it.copy(
                                    modelStatus = ModelAvailability.Downloading(status.totalBytesDownloaded),
                                    statusMessage = "Downloading: ${status.totalBytesDownloaded / 1024} KB"
                                )
                            }
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            _state.update {
                                it.copy(
                                    modelStatus = ModelAvailability.Ready(voice.name, voice.gender.name),
                                    statusMessage = "Model downloaded and cached on-device!"
                                )
                            }
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _state.update {
                                it.copy(
                                    modelStatus = ModelAvailability.Unavailable(status.e.localizedMessage ?: "Download failed"),
                                    statusMessage = "Download failed: ${status.e.localizedMessage}"
                                )
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                _state.update {
                    it.copy(
                        modelStatus = ModelAvailability.Unavailable(e.localizedMessage ?: "Download error"),
                        statusMessage = "Download exception: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun initSystemTts() {
        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTts?.language = Locale.US
                systemTts?.setSpeechRate(_state.value.speechRate)
                systemTts?.setPitch(_state.value.pitch)

                val voices = systemTts?.voices?.filter { !it.isNetworkConnectionRequired }?.toList() ?: emptyList()
                systemVoiceList = voices
                selectedSystemVoice = voices.firstOrNull { it.locale == Locale.US } ?: voices.firstOrNull()

                systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.update {
                            it.copy(playbackStatus = PlaybackStatus.PLAYING, statusMessage = "Speaking (System On-Device)...")
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        _state.update {
                            it.copy(playbackStatus = PlaybackStatus.IDLE, statusMessage = "Finished playback")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.update {
                            it.copy(playbackStatus = PlaybackStatus.ERROR, statusMessage = "Playback error occurred")
                        }
                    }
                })
            }
        }
    }

    private fun fallbackToSystemTts(reason: String) {
        _state.update {
            it.copy(
                activeEngine = EngineMode.SYSTEM_ON_DEVICE,
                modelStatus = ModelAvailability.Unavailable(reason),
                statusMessage = "Fallback Active: $reason"
            )
        }
    }

    fun selectVoice(index: Int) {
        if (index in genAiVoiceList.indices) {
            selectedGenAiVoice = genAiVoiceList[index]
            _state.update { it.copy(selectedVoiceIndex = index) }
            coroutineScope.launch {
                checkModelStatus(selectedGenAiVoice!!)
            }
        }
    }

    fun setEngineMode(mode: EngineMode) {
        _state.update { it.copy(activeEngine = mode) }
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        _state.update { it.copy(speechRate = clamped) }
        systemTts?.setSpeechRate(clamped)
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        _state.update { it.copy(pitch = clamped) }
        systemTts?.setPitch(clamped)
    }

    fun clearAudioCache() {
        audioCache.clear()
        _state.update { it.copy(cacheStats = audioCache.getStats()) }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        stop()

        val startTime = SystemClock.elapsedRealtime()

        if (_state.value.activeEngine == EngineMode.GENAI_NPU && speechSynthesizer != null && selectedGenAiVoice != null) {
            synthesizeGenAi(text, startTime)
        } else {
            synthesizeSystem(text, startTime)
        }
    }

    private fun synthesizeGenAi(text: String, startTime: Long) {
        val client = speechSynthesizer ?: return
        val voice = selectedGenAiVoice ?: return

        val cacheKey = audioCache.createCacheKey(text, voice.name)
        val cachedPcm = audioCache.get(cacheKey)

        if (cachedPcm != null) {
            val latency = SystemClock.elapsedRealtime() - startTime
            _state.update {
                it.copy(
                    playbackStatus = PlaybackStatus.PLAYING,
                    statusMessage = "Instant Playback from NPU Audio Cache",
                    latencyMs = latency,
                    isCacheHit = true,
                    chunksProcessed = 1,
                    cacheStats = audioCache.getStats()
                )
            }
            playCachedPcm(cachedPcm, startTime)
            return
        }

        synthesisJob = coroutineScope.launch {
            PerformanceScope(context).use { perfScope ->
                perfScope.beginWork()

                _state.update {
                    it.copy(
                        playbackStatus = PlaybackStatus.SYNTHESIZING,
                        statusMessage = "Synthesizing with on-device NPU...",
                        latencyMs = null,
                        totalTimeMs = null,
                        chunksProcessed = 0,
                        isCacheHit = false,
                        realTimeFactor = null,
                        thermalStatus = queryThermalStatus()
                    )
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build()

                val request = SpeechSynthesizerRequest(
                    input = SpeechSynthesizerRequest.Input.Text(text, voice),
                    audioAttributes = audioAttributes,
                    queueMode = SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH
                )

                val accumulator = NpuAudioCache.AudioAccumulator()

                try {
                    var firstChunk = true
                    var chunkCount = 0

                    client.synthesize(request).collect { response ->
                        when (response) {
                            is SpeechSynthesizerResponse.StatusUpdate -> {
                                when (response.status) {
                                    SpeechSynthesizerResponse.Status.START -> {
                                        val latency = SystemClock.elapsedRealtime() - startTime
                                        _state.update {
                                            it.copy(
                                                playbackStatus = PlaybackStatus.PLAYING,
                                                statusMessage = "Streaming audio from NPU",
                                                latencyMs = latency
                                            )
                                        }
                                    }
                                    SpeechSynthesizerResponse.Status.DONE, SpeechSynthesizerResponse.Status.STOP -> {
                                        val totalDuration = SystemClock.elapsedRealtime() - startTime
                                        val totalBytes = accumulator.size
                                        val audioDurationMs = if (totalBytes > 0) totalBytes / 48 else 1
                                        val rtf = totalDuration.toFloat() / audioDurationMs.coerceAtLeast(1)

                                        if (accumulator.size > 0 && response.status == SpeechSynthesizerResponse.Status.DONE) {
                                            audioCache.put(cacheKey, accumulator.toByteArray())
                                        }

                                        _state.update {
                                            it.copy(
                                                playbackStatus = PlaybackStatus.IDLE,
                                                statusMessage = "Synthesis complete (${totalDuration}ms, RTF: ${"%.2f".format(rtf)}x)",
                                                totalTimeMs = totalDuration,
                                                realTimeFactor = rtf,
                                                cacheStats = audioCache.getStats()
                                            )
                                        }
                                    }
                                }
                            }
                            is SpeechSynthesizerResponse.AudioAvailable -> {
                                if (firstChunk) {
                                    val latency = SystemClock.elapsedRealtime() - startTime
                                    firstChunk = false
                                    _state.update {
                                        it.copy(
                                            playbackStatus = PlaybackStatus.PLAYING,
                                            latencyMs = latency
                                        )
                                    }
                                }
                                accumulator.append(response.audio)
                                chunkCount++
                                perfScope.report()
                                perfScope.beginWork()
                                _state.update { it.copy(chunksProcessed = chunkCount) }
                            }
                            is SpeechSynthesizerResponse.Error -> {
                                Log.e(TAG, "GenAI synthesis returned error: ${response.errorCode}")
                                synthesizeSystem(text, startTime)
                            }
                        }
                    }
                    perfScope.report()
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "GenAI synthesis failed, falling back to system TTS", e)
                        synthesizeSystem(text, startTime)
                    }
                }
            }
        }
    }

    private fun playCachedPcm(pcm: ByteArray, startTime: Long) {
        synthesisJob = coroutineScope.launch {
            try {
                val speed = _state.value.speechRate
                val pitch = _state.value.pitch

                // Apply Sonic DSP time-stretching if speed or pitch is non-unity
                val playbackData = if (abs(speed - 1.0f) > 0.001f || abs(pitch - 1.0f) > 0.001f) {
                    val sonic = Sonic(24000, 1).apply {
                        this.speed = speed
                        this.pitch = pitch
                    }
                    sonic.writeBytesToStream(pcm, pcm.size)
                    sonic.flushStream()

                    val estimatedSize = ((pcm.size / speed) * 2).toInt().coerceAtLeast(4096)
                    val buffer = ByteArray(estimatedSize)
                    val read = sonic.readBytesFromStream(buffer, buffer.size)
                    if (read > 0) buffer.copyOf(read) else pcm
                } else {
                    pcm
                }

                val minBuffer = AudioTrack.getMinBufferSize(
                    24000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, playbackData.size))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                cachePlaybackTrack = track
                track.write(playbackData, 0, playbackData.size)
                track.play()

                // Calculate duration of audio: 24000 samples/sec * 2 bytes = 48 bytes/ms
                val durationMs = (playbackData.size / 48L)
                delay(durationMs)

                val totalDuration = SystemClock.elapsedRealtime() - startTime
                _state.update {
                    it.copy(
                        playbackStatus = PlaybackStatus.IDLE,
                        statusMessage = "Cached playback complete (${totalDuration}ms, ${speed}x rate)",
                        totalTimeMs = totalDuration,
                        realTimeFactor = 0.01f
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during cached audio playback", e)
                _state.update { it.copy(playbackStatus = PlaybackStatus.IDLE) }
            } finally {
                try {
                    cachePlaybackTrack?.stop()
                    cachePlaybackTrack?.release()
                } catch (_: Exception) {}
                cachePlaybackTrack = null
            }
        }
    }

    private fun synthesizeSystem(text: String, startTime: Long) {
        val tts = systemTts ?: return
        selectedSystemVoice?.let { tts.voice = it }

        _state.update {
            it.copy(
                activeEngine = EngineMode.SYSTEM_ON_DEVICE,
                playbackStatus = PlaybackStatus.SYNTHESIZING,
                statusMessage = "Synthesizing with System Neural Voice...",
                latencyMs = null,
                isCacheHit = false
            )
        }

        val utteranceId = "utterance_${System.currentTimeMillis()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        val latency = SystemClock.elapsedRealtime() - startTime
        _state.update { it.copy(latencyMs = latency) }
    }

    fun stop() {
        synthesisJob?.cancel()
        synthesisJob = null

        try {
            cachePlaybackTrack?.stop()
            cachePlaybackTrack?.release()
        } catch (_: Exception) {}
        cachePlaybackTrack = null

        try {
            speechSynthesizer?.stop()
        } catch (_: Exception) {}
        try {
            systemTts?.stop()
        } catch (_: Exception) {}

        _state.update { it.copy(playbackStatus = PlaybackStatus.IDLE, statusMessage = "Stopped") }
    }

    fun release() {
        stop()
        coroutineScope.cancel()
        ttsExecutor.shutdown()
        try {
            speechSynthesizer?.close()
        } catch (_: Exception) {}
        try {
            systemTts?.shutdown()
        } catch (_: Exception) {}
    }
}
