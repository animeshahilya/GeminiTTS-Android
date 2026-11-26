package com.animeshahilya.geminitts.ui.main

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeshahilya.geminitts.audio.ClarityPreset
import com.animeshahilya.geminitts.data.AppProfile
import com.animeshahilya.geminitts.data.AudioCache
import com.animeshahilya.geminitts.data.BackupRestore
import com.animeshahilya.geminitts.data.Dictionary
import com.animeshahilya.geminitts.data.DictionaryRule
import com.animeshahilya.geminitts.data.GenAiVoices
import com.animeshahilya.geminitts.data.LanguageOverride
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.RecentSpeech
import com.animeshahilya.geminitts.data.SafeRegex
import com.animeshahilya.geminitts.data.SampleTexts
import com.animeshahilya.geminitts.data.TextOptions
import com.animeshahilya.geminitts.data.TextPipeline
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.data.VoicePreset
import com.animeshahilya.geminitts.data.label
import com.animeshahilya.geminitts.data.voiceId
import com.animeshahilya.geminitts.engine.PlatformSynth
import com.animeshahilya.geminitts.service.CallerTracker
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface ModelStatus {
    data object Checking : ModelStatus
    data object Ready : ModelStatus
    data object NeedsDownload : ModelStatus
    data class Downloading(val bytes: Long) : ModelStatus
    data class Unavailable(val reason: String) : ModelStatus
}

data class VoiceChoice(val id: String, val gender: String)

data class LanguageRow(val locale: Locale, val status: ModelStatus = ModelStatus.Checking)

data class AppEntry(val packageName: String, val label: String, val isScreenReader: Boolean)

data class UiState(
    val loading: Boolean = true,
    val error: String? = null,
    val statusMessage: String? = null,
    val voices: List<VoiceChoice> = emptyList(),
    val selectedVoice: String? = null,
    val previewingVoiceId: String? = null,
    val languages: List<LanguageRow> = emptyList(),
    val languageOverrides: Map<String, LanguageOverride> = emptyMap(),
    val appProfiles: Map<String, AppProfile> = emptyMap(),
    val knownApps: List<AppEntry> = emptyList(),
    val presets: List<VoicePreset> = emptyList(),
    val activePresetName: String? = null,
    val testLocale: Locale = GenAiVoices.DEFAULT_LOCALE,
    val isDefaultEngine: Boolean = true,
    val speaking: Boolean = false,
    val firstAudioMs: Long? = null,
    val totalMs: Long? = null,
    val styleId: String = ReadingStyle.STANDARD,
    val customStyle: ReadingStyle = ReadingStyle.custom(1f, 1f, 0, 0),
    val baseSpeed: Float = 1f,
    val basePitch: Float = 1f,
    val volumePercent: Int = 100,
    val lockSpeed: Boolean = false,
    val lockPitch: Boolean = false,
    val lockVolume: Boolean = false,
    val clarityPreset: ClarityPreset = ClarityPreset.OFF,
    val levelerEnabled: Boolean = false,
    val trimSilence: Boolean = true,
    val textOptions: TextOptions = TextOptions(),
    val rules: List<DictionaryRule> = emptyList(),
    val cacheEnabled: Boolean = true,
    val autoDownload: Boolean = true,
    val cachedPhrases: Int = 0,
    val cachedBytes: Long = 0,
    val sleepStopAtMs: Long = 0L,
    val recentSpeechEnabled: Boolean = false,
    val recentSpeechCount: Int = 0
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = TtsPrefs(application)
    private val cache = AudioCache.get(application)
    private val client = GenAiVoices.client()
    private val google = PlatformSynth(application)
    private var allVoices: List<VoiceInfo> = emptyList()

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var startedAt = 0L
    private var firstAudioSeen = false
    private val tts: TextToSpeech = TextToSpeech(application, { refreshEngineCheck() }, application.packageName).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                if (id == "test" && !firstAudioSeen) {
                    firstAudioSeen = true
                    _state.update { it.copy(firstAudioMs = SystemClock.elapsedRealtime() - startedAt) }
                }
            }
            override fun onDone(id: String?) = finished(id == "test")
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(false)
            override fun onStop(id: String?, interrupted: Boolean) = finished(false)
        })
    }

    init {
        refreshCacheStats()
        refreshApps()
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

    private fun buildInitialState() = UiState(
        rules = prefs.rules,
        cacheEnabled = prefs.cacheEnabled,
        autoDownload = prefs.autoDownload,
        styleId = prefs.styleId,
        customStyle = prefs.customStyle,
        baseSpeed = prefs.baseSpeed,
        basePitch = prefs.basePitch,
        volumePercent = prefs.volumePercent,
        lockSpeed = prefs.lockSpeed,
        lockPitch = prefs.lockPitch,
        lockVolume = prefs.lockVolume,
        clarityPreset = prefs.clarityPreset,
        levelerEnabled = prefs.levelerEnabled,
        trimSilence = prefs.trimSilence,
        textOptions = prefs.textOptions,
        languageOverrides = prefs.languageOverrides,
        appProfiles = prefs.appProfiles,
        presets = prefs.presets,
        activePresetName = prefs.currentPresetName(),
        sleepStopAtMs = prefs.sleepStopAt(),
        recentSpeechEnabled = prefs.recentSpeechEnabled,
        recentSpeechCount = RecentSpeech.items().size
    )

    private fun voiceIn(locale: Locale): VoiceInfo? {
        val inLocale = allVoices.filter { it.locale == locale }
        val choice = prefs.languageOverrideFor(locale)?.voiceId ?: _state.value.selectedVoice
        return inLocale.firstOrNull { voiceId(it.name) == choice } ?: inLocale.firstOrNull()
    }

    private fun setStatus(locale: Locale, status: ModelStatus) = _state.update { s ->
        s.copy(languages = s.languages.map { if (it.locale == locale) it.copy(status = status) else it })
    }

    private suspend fun checkAllStatus() {
        google.forget()
        for (row in _state.value.languages) {
            val voice = voiceIn(row.locale) ?: continue
            setStatus(row.locale, statusOf(voice))
        }
    }

    private suspend fun statusOf(voice: VoiceInfo): ModelStatus {
        if (withContext(Dispatchers.IO) { google.installedVoice(voice.name) } != null) return ModelStatus.Ready
        return try {
            when (client.checkStatus(voice)) {
                FeatureStatus.AVAILABLE -> ModelStatus.Unavailable("downloaded, but Google can't use it yet")
                FeatureStatus.DOWNLOADABLE -> ModelStatus.NeedsDownload
                FeatureStatus.DOWNLOADING -> ModelStatus.Downloading(0)
                else -> ModelStatus.Unavailable("not available on this phone")
            }
        } catch (e: Exception) {
            ModelStatus.Unavailable(e.localizedMessage ?: "status check failed")
        }
    }

    fun voicesInLocale(locale: Locale): List<VoiceChoice> {
        val inLocale = allVoices.filter { it.locale == locale }
            .distinctBy { voiceId(it.name) }
            .map { VoiceChoice(voiceId(it.name), it.gender.name.lowercase()) }
            .sortedBy { it.id }
        return inLocale.ifEmpty { _state.value.voices }
    }

    fun selectVoice(id: String) {
        prefs.voiceId = id
        _state.update { it.copy(selectedVoice = id, activePresetName = prefs.currentPresetName()) }
        viewModelScope.launch { checkAllStatus() }
    }

    fun previewVoice(id: String) {
        if (_state.value.previewingVoiceId == id) {
            stop()
            return
        }
        val locale = _state.value.testLocale
        val targetEntry = prefs.voicesFor(locale, id).firstOrNull() ?: prefs.voicesAnyLanguage(id).firstOrNull()
        val effectiveLocale = targetEntry?.locale ?: locale
        val ttsVoice = targetEntry?.let { entry ->
            runCatching { tts.voices?.firstOrNull { it.name == entry.name } }.getOrNull()
        }
        _state.update { it.copy(speaking = true, previewingVoiceId = id) }
        tts.language = effectiveLocale
        if (ttsVoice != null) tts.voice = ttsVoice
        tts.setSpeechRate(1f)
        tts.setPitch(1f)
        if (tts.speak(SampleTexts.forLocale(effectiveLocale), TextToSpeech.QUEUE_FLUSH, null, "preview_$id") != TextToSpeech.SUCCESS) {
            finished(false)
        }
    }

    fun download(locale: Locale) {
        val voice = voiceIn(locale) ?: return
        viewModelScope.launch {
            setStatus(locale, ModelStatus.Downloading(0))
            try {
                client.download(voice).collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadProgress -> setStatus(locale, ModelStatus.Downloading(status.totalBytesDownloaded))
                        is DownloadStatus.DownloadCompleted -> {
                            google.forget()
                            setStatus(locale, statusOf(voice))
                        }
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
        _state.update { it.copy(styleId = id, activePresetName = prefs.currentPresetName()) }
    }

    fun setCustomStyle(style: ReadingStyle) {
        prefs.customStyle = style
        _state.update { it.copy(customStyle = style, activePresetName = prefs.currentPresetName()) }
    }

    fun setBaseSpeed(v: Float) {
        prefs.baseSpeed = v
        _state.update { it.copy(baseSpeed = prefs.baseSpeed, activePresetName = prefs.currentPresetName()) }
    }

    fun setBasePitch(v: Float) {
        prefs.basePitch = v
        _state.update { it.copy(basePitch = prefs.basePitch, activePresetName = prefs.currentPresetName()) }
    }

    fun setVolumePercent(v: Int) {
        prefs.volumePercent = v
        _state.update { it.copy(volumePercent = prefs.volumePercent, activePresetName = prefs.currentPresetName()) }
    }

    fun setLockSpeed(v: Boolean) {
        prefs.lockSpeed = v
        _state.update { it.copy(lockSpeed = v, activePresetName = prefs.currentPresetName()) }
    }

    fun setLockPitch(v: Boolean) {
        prefs.lockPitch = v
        _state.update { it.copy(lockPitch = v, activePresetName = prefs.currentPresetName()) }
    }

    fun setLockVolume(v: Boolean) {
        prefs.lockVolume = v
        _state.update { it.copy(lockVolume = v, activePresetName = prefs.currentPresetName()) }
    }

    fun setClarity(preset: ClarityPreset) {
        prefs.clarityPreset = preset
        _state.update { it.copy(clarityPreset = preset, activePresetName = prefs.currentPresetName()) }
    }

    fun setLeveler(enabled: Boolean) {
        prefs.levelerEnabled = enabled
        _state.update { it.copy(levelerEnabled = enabled, activePresetName = prefs.currentPresetName()) }
    }

    fun setTrimSilence(enabled: Boolean) {
        prefs.trimSilence = enabled
        _state.update { it.copy(trimSilence = enabled) }
    }

    fun updateTextOptions(transform: (TextOptions) -> TextOptions) {
        val next = transform(_state.value.textOptions)
        prefs.textOptions = next
        _state.update { it.copy(textOptions = next, activePresetName = prefs.currentPresetName()) }
    }

    fun previewTextTransform(input: String, useDictionaryOnly: Boolean = false): String {
        val lang = _state.value.testLocale.toLanguageTag()
        return if (useDictionaryOnly) {
            prefs.dictionary.apply(input, lang).toString()
        } else {
            TextPipeline.process(input, _state.value.textOptions, prefs.dictionary, lang).toString()
        }
    }

    fun setLanguageOverride(override: LanguageOverride) {
        prefs.setLanguageOverride(override)
        _state.update { it.copy(languageOverrides = prefs.languageOverrides) }
        viewModelScope.launch { checkAllStatus() }
    }

    fun setAppProfile(profile: AppProfile) {
        prefs.setAppProfile(profile)
        _state.update { it.copy(appProfiles = prefs.appProfiles) }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val seen = prefs.seenApps
            val profiles = prefs.appProfiles.keys
            val discovered = CallerTracker.discoverableApps(app)
            val merged = LinkedHashMap<String, AppEntry>()
            for ((pkg, label) in discovered) {
                merged[pkg] = AppEntry(pkg, label, CallerTracker.isSpokenAccessibilityService(app, pkg))
            }
            for (pkg in seen + profiles) {
                if (pkg !in merged) {
                    merged[pkg] = AppEntry(pkg, CallerTracker.appLabel(app, pkg), CallerTracker.isSpokenAccessibilityService(app, pkg))
                }
            }
            val sorted = merged.values.sortedWith(compareBy({ !it.isScreenReader }, { it.packageName !in profiles }, { it.label.lowercase() }))
            _state.update { it.copy(knownApps = sorted) }
        }
    }

    fun savePreset(name: String) {
        val saved = prefs.savePreset(name) ?: return
        _state.update {
            it.copy(presets = prefs.presets, activePresetName = saved.name, statusMessage = "Saved preset '${saved.name}'.")
        }
    }

    fun applyPreset(preset: VoicePreset) {
        prefs.applyPreset(preset)
        syncPrefsToState("Applied preset '${preset.name}'.")
        viewModelScope.launch { checkAllStatus() }
    }

    private fun syncPrefsToState(message: String?) {
        _state.update { current ->
            buildInitialState().copy(
                loading = current.loading,
                error = current.error,
                voices = current.voices,
                selectedVoice = prefs.voiceId ?: current.selectedVoice,
                languages = current.languages,
                knownApps = current.knownApps,
                testLocale = current.testLocale,
                isDefaultEngine = current.isDefaultEngine,
                speaking = current.speaking,
                firstAudioMs = current.firstAudioMs,
                totalMs = current.totalMs,
                cachedPhrases = current.cachedPhrases,
                cachedBytes = current.cachedBytes,
                statusMessage = message
            )
        }
    }

    fun deletePreset(name: String) {
        prefs.deletePreset(name)
        _state.update { it.copy(presets = prefs.presets, activePresetName = prefs.currentPresetName()) }
    }

    fun armSleepTimer(minutes: Int) {
        val at = if (minutes <= 0) { prefs.clearSleepTimer(); 0L } else prefs.armSleepTimer(minutes)
        _state.update {
            it.copy(
                sleepStopAtMs = at,
                statusMessage = if (at == 0L) "Sleep timer turned off." else "Reading apps will pause in $minutes minutes."
            )
        }
    }

    fun setRecentSpeechEnabled(enabled: Boolean) {
        prefs.recentSpeechEnabled = enabled
        if (!enabled) RecentSpeech.clear()
        _state.update {
            it.copy(recentSpeechEnabled = enabled, recentSpeechCount = RecentSpeech.items().size)
        }
    }

    fun setTestLocale(locale: Locale) = _state.update { it.copy(testLocale = locale) }

    fun speak(text: String) {
        if (text.isBlank()) return
        startedAt = SystemClock.elapsedRealtime()
        firstAudioSeen = false
        _state.update { it.copy(speaking = true, previewingVoiceId = null, firstAudioMs = null, totalMs = null) }
        val s = _state.value
        val voice = prefs.voiceFor(s.testLocale)?.let { entry ->
            runCatching { tts.voices?.firstOrNull { it.name == entry.name } }.getOrNull()
        }
        tts.language = s.testLocale
        if (voice != null) tts.voice = voice
        tts.setSpeechRate(1f)
        tts.setPitch(1f)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test") != TextToSpeech.SUCCESS) finished(false)
    }

    fun stop() {
        tts.stop()
        _state.update { it.copy(speaking = false, previewingVoiceId = null) }
    }

    private fun finished(completed: Boolean) {
        _state.update {
            it.copy(
                speaking = false,
                previewingVoiceId = null,
                totalMs = if (completed) SystemClock.elapsedRealtime() - startedAt else null
            )
        }
        refreshCacheStats()
    }

    fun refreshEngineCheck() {
        syncPrefsToState(_state.value.statusMessage)
        _state.update { it.copy(isDefaultEngine = tts.defaultEngine == app.packageName) }
        refreshApps()
    }

    fun setAutoDownload(enabled: Boolean) {
        prefs.autoDownload = enabled
        _state.update { it.copy(autoDownload = enabled) }
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

    /** Adds or replaces a dictionary rule, or returns a human-readable validation error. */
    fun saveRule(existing: DictionaryRule?, updatedRule: DictionaryRule): String? {
        val p = updatedRule.pattern.trim()
        if (p.isEmpty()) return "Enter the word or pattern to replace."
        if (updatedRule.isRegex) {
            SafeRegex.validate(p)?.let { return it }
        }
        val clean = updatedRule.copy(pattern = p, language = updatedRule.language.trim())
        val baseList = if (existing != null) _state.value.rules.filterNot { it == existing } else _state.value.rules
        val next = baseList.filterNot {
            it.pattern == clean.pattern && it.language == clean.language && it.isCharacter == clean.isCharacter
        } + clean
        prefs.rules = next
        _state.update { it.copy(rules = next) }
        return null
    }

    fun removeRule(rule: DictionaryRule) {
        val updated = _state.value.rules - rule
        prefs.rules = updated
        _state.update { it.copy(rules = updated) }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = BackupRestore.exportBackupJson(prefs)
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                    ?: error("Could not open file for writing.")
            }.onSuccess {
                _state.update { it.copy(statusMessage = "Settings and dictionary exported.") }
            }.onFailure { e ->
                _state.update { it.copy(statusMessage = "Export failed: ${e.localizedMessage}") }
            }
        }
    }

    fun importBackupFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val raw = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read backup file.")
                BackupRestore.importBackupJson(prefs, raw)
            }.onSuccess { result ->
                val msg = if (result.skippedRules > 0) {
                    "Backup restored (${result.ruleCount} rules; ${result.skippedRules} invalid skipped)."
                } else {
                    "Backup restored (${result.ruleCount} dictionary rules)."
                }
                syncPrefsToState(msg)
                refreshApps()
                viewModelScope.launch { checkAllStatus() }
            }.onFailure { e ->
                _state.update { it.copy(statusMessage = "Import failed: ${e.localizedMessage}") }
            }
        }
    }

    fun exportNvdaDicToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val content = BackupRestore.formatNvdaDic(prefs.rules)
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Could not open file for writing.")
            }.onSuccess {
                _state.update { it.copy(statusMessage = "NVDA .dic dictionary exported.") }
            }.onFailure { e ->
                _state.update { it.copy(statusMessage = "Export failed: ${e.localizedMessage}") }
            }
        }
    }

    fun importNvdaDicFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val raw = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read .dic file.")
                val imported = BackupRestore.parseNvdaDic(raw)
                val merged = (prefs.rules.filterNot { existing ->
                    imported.any { it.pattern == existing.pattern && it.language == existing.language }
                } + imported)
                prefs.rules = merged
                imported.size
            }.onSuccess { count ->
                _state.update { it.copy(rules = prefs.rules, statusMessage = "Imported $count rules from .dic.") }
            }.onFailure { e ->
                _state.update { it.copy(statusMessage = "Import failed: ${e.localizedMessage}") }
            }
        }
    }

    fun clearStatusMessage() = _state.update { it.copy(statusMessage = null) }

    override fun onCleared() {
        tts.shutdown()
        google.shutdown()
        client.close()
    }
}
