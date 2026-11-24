package com.example.genaittsdemo.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.genaittsdemo.data.AccessibilitySpanParser
import com.example.genaittsdemo.data.DictionaryRule
import com.example.genaittsdemo.data.EngineMode
import com.example.genaittsdemo.data.GeminiVoiceCatalog
import com.example.genaittsdemo.data.GenAiTtsManager
import com.example.genaittsdemo.data.PunctuationMode
import com.example.genaittsdemo.data.TtsCustomizationPreferences
import com.example.genaittsdemo.data.TtsEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CustomizationUiState(
    val punctuationMode: PunctuationMode = PunctuationMode.SOME,
    val rules: List<DictionaryRule> = emptyList(),
    val speedMultiplier: Float = 1.0f,
    val pitchMultiplier: Float = 1.0f,
    val preferredVoiceName: String = "Aoede",
    val preferredGender: String = "FEMALE",
    val isCacheEnabled: Boolean = true,
    val isNumberExpansionEnabled: Boolean = true,
    val isSmartChunkingEnabled: Boolean = true,
    val searchQuery: String = ""
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val ttsManager = GenAiTtsManager(application)
    val uiState: StateFlow<TtsEngineState> = ttsManager.state

    private val preferences = TtsCustomizationPreferences(application)

    private val _customState = MutableStateFlow(
        CustomizationUiState(
            punctuationMode = preferences.punctuationMode,
            rules = preferences.getDictionaryRules(),
            speedMultiplier = preferences.speedMultiplier,
            pitchMultiplier = preferences.pitchMultiplier,
            preferredVoiceName = preferences.preferredVoiceName,
            preferredGender = preferences.preferredGender,
            isCacheEnabled = preferences.isCacheEnabled,
            isNumberExpansionEnabled = preferences.isNumberExpansionEnabled,
            isSmartChunkingEnabled = preferences.isSmartChunkingEnabled
        )
    )
    val customState: StateFlow<CustomizationUiState> = _customState.asStateFlow()

    init {
        ttsManager.setSpeechRate(preferences.speedMultiplier)
        ttsManager.setPitch(preferences.pitchMultiplier)
    }

    fun speak(text: String) {
        val spanExpanded = AccessibilitySpanParser.parse(text)
        val filtered = preferences.applyFilters(spanExpanded)
        ttsManager.speak(filtered)
    }

    fun stop() {
        ttsManager.stop()
    }

    fun downloadModel() {
        ttsManager.downloadSelectedModel()
    }

    fun selectVoice(index: Int) {
        ttsManager.selectVoice(index)
    }

    fun setEngineMode(mode: EngineMode) {
        ttsManager.setEngineMode(mode)
    }

    fun clearAudioCache() {
        ttsManager.clearAudioCache()
    }

    fun setPunctuationMode(mode: PunctuationMode) {
        preferences.punctuationMode = mode
        _customState.update { it.copy(punctuationMode = mode) }
    }

    fun setSpeedMultiplier(speed: Float) {
        preferences.speedMultiplier = speed
        ttsManager.setSpeechRate(speed)
        _customState.update { it.copy(speedMultiplier = speed) }
    }

    fun setPitchMultiplier(pitch: Float) {
        preferences.pitchMultiplier = pitch
        ttsManager.setPitch(pitch)
        _customState.update { it.copy(pitchMultiplier = pitch) }
    }

    fun setPreferredVoice(voiceName: String) {
        preferences.preferredVoiceName = voiceName
        val profile = GeminiVoiceCatalog.findByName(voiceName)
        if (profile != null) {
            preferences.preferredGender = profile.gender
            ttsManager.setPitch(preferences.pitchMultiplier * profile.pitchMultiplier)
        }
        _customState.update {
            it.copy(
                preferredVoiceName = voiceName,
                preferredGender = profile?.gender ?: it.preferredGender
            )
        }
    }

    fun setPreferredGender(gender: String) {
        preferences.preferredGender = gender
        val defaultForGender = GeminiVoiceCatalog.getDefaultVoiceForGender(gender)
        preferences.preferredVoiceName = defaultForGender.name
        _customState.update {
            it.copy(
                preferredGender = gender,
                preferredVoiceName = defaultForGender.name
            )
        }
    }

    fun setCacheEnabled(enabled: Boolean) {
        preferences.isCacheEnabled = enabled
        _customState.update { it.copy(isCacheEnabled = enabled) }
    }

    fun setNumberExpansionEnabled(enabled: Boolean) {
        preferences.isNumberExpansionEnabled = enabled
        _customState.update { it.copy(isNumberExpansionEnabled = enabled) }
    }

    fun setSmartChunkingEnabled(enabled: Boolean) {
        preferences.isSmartChunkingEnabled = enabled
        _customState.update { it.copy(isSmartChunkingEnabled = enabled) }
    }

    fun setSearchQuery(query: String) {
        _customState.update { it.copy(searchQuery = query) }
    }

    fun addRule(pattern: String, replacement: String, isRegex: Boolean = false, desc: String = "") {
        if (pattern.isBlank() || replacement.isBlank()) return
        val newRule = DictionaryRule(pattern.trim(), replacement.trim(), isRegex, desc.trim())
        val updated = _customState.value.rules + newRule
        preferences.saveDictionaryRules(updated)
        _customState.update { it.copy(rules = updated) }
    }

    fun removeRule(index: Int) {
        if (index in _customState.value.rules.indices) {
            val updated = _customState.value.rules.toMutableList().apply { removeAt(index) }
            preferences.saveDictionaryRules(updated)
            _customState.update { it.copy(rules = updated) }
        }
    }

    fun resetDefaultRules() {
        val defaults = preferences.resetToDefaultRules()
        _customState.update { it.copy(rules = defaults) }
    }

    fun previewFilter(input: String): String {
        val spanExpanded = AccessibilitySpanParser.parse(input)
        return preferences.applyFilters(spanExpanded)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.release()
    }
}
