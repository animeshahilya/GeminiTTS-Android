package com.animeshahilya.geminitts.ui.main

import android.app.Application
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeshahilya.geminitts.data.AudioCache
import com.animeshahilya.geminitts.data.DictionaryRule
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.label
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.voiceId
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface ModelStatus {
    data object Checking : ModelStatus
    data object Ready : ModelStatus
    data object NeedsDownload : ModelStatus
    data class Downloading(val bytes: Long) : ModelStatus
    data class Unavailable(val reason: String) : ModelStatus
}

/** One of the voices, the same in every language. */
data class VoiceChoice(val id: String, val gender: String)

data class LanguageRow(val locale: Locale, val status: ModelStatus = ModelStatus.Checking)

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val voices: List<VoiceChoice> = emptyList(),
    val selectedVoice: String? = null,
    val languages: List<LanguageRow> = emptyList(),
    val testLocale: Locale = GenAiVoices.DEFAULT_LOCALE,
    val isDefaultEngine: Boolean = true,
    val speaking: Boolean = false,
    val firstAudioMs: Long? = null,
    val totalMs: Long? = null,
    val testRate: Float = 1f,
    val testPitch: Float = 1f,
    val styleId: String = ReadingStyle.STANDARD,
    val customStyle: ReadingStyle = ReadingStyle.custom(1f, 1f, 0, 0),
    val rules: List<DictionaryRule> = emptyList(),
    val cacheEnabled: Boolean = true,
    val cachedPhrases: Int = 0,
    val cachedBytes: Long = 0
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = TtsPrefs(application)
    private val cache = AudioCache.get(application)
    private val client = GenAiVoices.client()
    private var allVoices: List<VoiceInfo> = emptyList()

    private val _state = MutableStateFlow(
        UiState(
            rules = prefs.rules,
            cacheEnabled = prefs.cacheEnabled,
            styleId = prefs.styleId,
            customStyle = prefs.customStyle
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    // The test button speaks through this app's own TTS service, so it sounds exactly like TalkBack will.
    private var startedAt = 0L
    private var firstAudioSeen = false
    private val tts: TextToSpeech = TextToSpeech(application, { refreshEngineCheck() }, application.packageName).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                if (!firstAudioSeen) {
                    firstAudioSeen = true
                    _state.update { it.copy(firstAudioMs = SystemClock.elapsedRealtime() - startedAt) }
                }
            }
            override fun onDone(id: String?) = finished(true)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(false)
            override fun onStop(id: String?, interrupted: Boolean) = finished(false)
        })
    }

    init {
        refreshCacheStats()
        viewModelScope.launch {
            allVoices = try {
                GenAiVoices.fetch(app, client)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.localizedMessage ?: "ML Kit error") }
                return@launch
            }
            if (allVoices.isEmpty()) {
                _state.update { it.copy(loading = false, error = "This phone has no on-device GenAI voices.") }
                return@launch
            }
            val choices = allVoices.distinctBy { voiceId(it.name) }
                .map { VoiceChoice(voiceId(it.name), it.gender.name.lowercase()) }
                .sortedBy { it.id }
            val locales = allVoices.map { it.locale }.distinct().sortedBy { it.label() }
            val system = Locale.getDefault()
            _state.update {
                it.copy(
                    loading = false,
                    voices = choices,
                    selectedVoice = prefs.voiceId?.takeIf { id -> choices.any { c -> c.id == id } } ?: choices.first().id,
                    languages = locales.map { locale -> LanguageRow(locale) },
                    testLocale = locales.firstOrNull { l -> l == system }
                        ?: locales.firstOrNull { l -> l.language == system.language }
                        ?: locales.firstOrNull { l -> l == GenAiVoices.DEFAULT_LOCALE } ?: locales.first()
                )
            }
            checkAllStatus()
        }
    }

    /** The chosen voice in [locale], or that language's first voice. */
    private fun voiceIn(locale: Locale): VoiceInfo? {
        val inLocale = allVoices.filter { it.locale == locale }
        return inLocale.firstOrNull { voiceId(it.name) == _state.value.selectedVoice } ?: inLocale.firstOrNull()
    }

    private fun setStatus(locale: Locale, status: ModelStatus) = _state.update { s ->
        s.copy(languages = s.languages.map { if (it.locale == locale) it.copy(status = status) else it })
    }

    private suspend fun checkAllStatus() {
        for (row in _state.value.languages) {
            val voice = voiceIn(row.locale) ?: continue
            val status = try {
                when (client.checkStatus(voice)) {
                    FeatureStatus.AVAILABLE -> ModelStatus.Ready
                    FeatureStatus.DOWNLOADABLE -> ModelStatus.NeedsDownload
                    FeatureStatus.DOWNLOADING -> ModelStatus.Downloading(0)
                    else -> ModelStatus.Unavailable("Not available on this phone")
                }
            } catch (e: Exception) {
                ModelStatus.Unavailable(e.localizedMessage ?: "Status check failed")
            }
            setStatus(row.locale, status)
        }
    }

    fun selectVoice(id: String) {
        prefs.voiceId = id
        _state.update { it.copy(selectedVoice = id) }
        viewModelScope.launch { checkAllStatus() }
    }

    fun download(locale: Locale) {
        val voice = voiceIn(locale) ?: return
        viewModelScope.launch {
            setStatus(locale, ModelStatus.Downloading(0))
            try {
                client.download(voice).collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadProgress -> setStatus(locale, ModelStatus.Downloading(status.totalBytesDownloaded))
                        is DownloadStatus.DownloadCompleted -> setStatus(locale, ModelStatus.Ready)
                        is DownloadStatus.DownloadFailed ->
                            setStatus(locale, ModelStatus.Unavailable(status.e.localizedMessage ?: "Download failed"))
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                setStatus(locale, ModelStatus.Unavailable(e.localizedMessage ?: "Download failed"))
            }
        }
    }

    fun setStyle(id: String) {
        prefs.styleId = id
        _state.update { it.copy(styleId = id) }
    }

    fun setCustomStyle(style: ReadingStyle) {
        prefs.customStyle = style
        _state.update { it.copy(customStyle = style) }
    }

    fun setTestLocale(locale: Locale) = _state.update { it.copy(testLocale = locale) }

    fun speak(text: String) {
        if (text.isBlank()) return
        startedAt = SystemClock.elapsedRealtime()
        firstAudioSeen = false
        _state.update { it.copy(speaking = true, firstAudioMs = null, totalMs = null) }
        val s = _state.value
        tts.language = s.testLocale
        tts.setSpeechRate(s.testRate)
        tts.setPitch(s.testPitch)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test") != TextToSpeech.SUCCESS) finished(false)
    }

    fun stop() {
        tts.stop()
    }

    private fun finished(completed: Boolean) {
        _state.update {
            it.copy(speaking = false, totalMs = if (completed) SystemClock.elapsedRealtime() - startedAt else null)
        }
        refreshCacheStats()
    }

    fun setTestRate(rate: Float) = _state.update { it.copy(testRate = rate) }

    fun setTestPitch(pitch: Float) = _state.update { it.copy(testPitch = pitch) }

    fun refreshEngineCheck() {
        _state.update { it.copy(isDefaultEngine = tts.defaultEngine == app.packageName) }
    }

    fun setCacheEnabled(enabled: Boolean) {
        prefs.cacheEnabled = enabled
        _state.update { it.copy(cacheEnabled = enabled) }
    }

    fun clearCache() {
        cache.clear()
        _state.update { it.copy(cachedPhrases = 0, cachedBytes = 0) }
    }

    private fun refreshCacheStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val (count, bytes) = cache.stats()
            _state.update { it.copy(cachedPhrases = count, cachedBytes = bytes) }
        }
    }

    /** Adds a rule, or returns why it can't be added. */
    fun addRule(pattern: String, replacement: String, isRegex: Boolean): String? {
        val p = pattern.trim()
        val r = replacement.trim()
        if (p.isEmpty()) return "Enter the word to replace."
        if (listOf(p, r).any { "=>" in it || '\n' in it }) return "Rules can't contain => or line breaks."
        if (isRegex && runCatching { Regex(p) }.isFailure) return "That pattern isn't a valid regular expression."
        val updated = _state.value.rules.filterNot { it.pattern == p } + DictionaryRule(p, r, isRegex)
        prefs.rules = updated
        _state.update { it.copy(rules = updated) }
        return null
    }

    fun removeRule(rule: DictionaryRule) {
        val updated = _state.value.rules - rule
        prefs.rules = updated
        _state.update { it.copy(rules = updated) }
    }

    override fun onCleared() {
        tts.shutdown()
        client.close()
    }
}
