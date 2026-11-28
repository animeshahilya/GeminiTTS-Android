package com.animeshahilya.geminitts.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.animeshahilya.geminitts.audio.ClarityPreset
import java.util.Locale

/**
 * Settings shared by the app, tiles, and the TTS service. Kept in device-protected storage so the
 * service can read them before the phone is first unlocked.
 */
class TtsPrefs(context: Context) {

    internal val prefs: SharedPreferences = context.createDeviceProtectedStorageContext().let { dp ->
        dp.moveSharedPreferencesFrom(context, NAME)
        dp.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** The chosen voice ("star02"), used in every language; null means each language's first voice. */
    var voiceId: String?
        // Older versions stored a full name ("en-us-x-star02"); voiceId() reduces either form.
        get() = prefs.getString(KEY_VOICE, null)?.takeIf { it.isNotBlank() }?.let(::voiceId)
        set(value) = prefs.edit { putString(KEY_VOICE, value?.takeIf { it.isNotBlank() }) }

    var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE, true)
        set(value) = prefs.edit { putBoolean(KEY_CACHE, value) }

    /** Download a language's voice in the background the first time an app asks for that language. */
    var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_DOWNLOAD, value) }

    /** Google's engine version the saved audio was made with; a new version means new-sounding voices. */
    var engineVersion: Long
        get() = prefs.getLong(KEY_ENGINE_VERSION, 0L)
        set(value) = prefs.edit { putLong(KEY_ENGINE_VERSION, value) }

    var styleId: String
        get() = prefs.getString(KEY_STYLE, null) ?: ReadingStyle.STANDARD
        set(value) = prefs.edit { putString(KEY_STYLE, value) }

    /** The values behind the Custom style; start as Audiobook's so there is something to adjust. */
    var customStyle: ReadingStyle
        get() {
            val base = ReadingStyle.PRESETS.first { it.id == "audiobook" }
            return ReadingStyle.custom(
                prefs.getFloat(KEY_CUSTOM_SPEED, base.speed).coerceIn(0.5f, 3f),
                prefs.getFloat(KEY_CUSTOM_PITCH, base.pitch).coerceIn(0.5f, 2f),
                prefs.getInt(KEY_CUSTOM_SENTENCE, base.sentencePauseMs).coerceIn(0, 1500),
                prefs.getInt(KEY_CUSTOM_PARAGRAPH, base.paragraphPauseMs).coerceIn(0, 3000)
            )
        }
        set(value) = prefs.edit {
            putFloat(KEY_CUSTOM_SPEED, value.speed)
            putFloat(KEY_CUSTOM_PITCH, value.pitch)
            putInt(KEY_CUSTOM_SENTENCE, value.sentencePauseMs)
            putInt(KEY_CUSTOM_PARAGRAPH, value.paragraphPauseMs)
        }

    /** The style in use, or the override for [overrideStyleId] when given. */
    fun style(overrideStyleId: String? = null): ReadingStyle = (overrideStyleId ?: styleId).let { id ->
        if (id == ReadingStyle.CUSTOM) customStyle else ReadingStyle.PRESETS.firstOrNull { it.id == id } ?: ReadingStyle.PRESETS.first()
    }

    // --- Audio controls & caller locks ---

    var baseSpeed: Float
        get() = prefs.getFloat(KEY_BASE_SPEED, 1f).coerceIn(0.5f, 3.5f)
        set(value) = prefs.edit { putFloat(KEY_BASE_SPEED, value.coerceIn(0.5f, 3.5f)) }

    var basePitch: Float
        get() = prefs.getFloat(KEY_BASE_PITCH, 1f).coerceIn(0.5f, 2f)
        set(value) = prefs.edit { putFloat(KEY_BASE_PITCH, value.coerceIn(0.5f, 2f)) }

    var volumePercent: Int
        get() = prefs.getInt(KEY_VOLUME_PERCENT, 100).coerceIn(25, 200)
        set(value) = prefs.edit { putInt(KEY_VOLUME_PERCENT, value.coerceIn(25, 200)) }

    var lockSpeed: Boolean
        get() = prefs.getBoolean(KEY_LOCK_SPEED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_SPEED, value) }

    var lockPitch: Boolean
        get() = prefs.getBoolean(KEY_LOCK_PITCH, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_PITCH, value) }

    var lockVolume: Boolean
        get() = prefs.getBoolean(KEY_LOCK_VOLUME, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_VOLUME, value) }

    var clarityPreset: ClarityPreset
        get() = ClarityPreset.fromId(prefs.getString(KEY_CLARITY, null))
        set(value) = prefs.edit { putString(KEY_CLARITY, value.id) }

    var levelerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEVELER, false)
        set(value) = prefs.edit { putBoolean(KEY_LEVELER, value) }

    var trimSilence: Boolean
        get() = prefs.getBoolean(KEY_TRIM_SILENCE, true)
        set(value) = prefs.edit { putBoolean(KEY_TRIM_SILENCE, value) }

    // --- Text & number options ---

    var textOptions: TextOptions
        get() {
            val raw = prefs.getString(KEY_TEXT_OPTIONS, null).orEmpty()
            parsedTextOptions?.let { (k, v) -> if (k == raw) return v }
            val opts = if (raw.isEmpty()) TextOptions() else TextOptions.fromMap(JsonCodec.parseObject(raw))
            return opts.also { parsedTextOptions = raw to it }
        }
        set(value) = prefs.edit { putString(KEY_TEXT_OPTIONS, JsonCodec.stringify(value.toMap())) }

    // --- Per-language overrides ---

    var languageOverrides: Map<String, LanguageOverride>
        get() {
            val raw = prefs.getString(KEY_LANG_OVERRIDES, null).orEmpty()
            parsedLangOverrides?.let { (k, v) -> if (k == raw) return v }
            val out = LinkedHashMap<String, LanguageOverride>()
            if (raw.isNotEmpty()) {
                val arr = JsonCodec.parseArray(raw).orEmpty()
                for (item in arr) {
                    @Suppress("UNCHECKED_CAST")
                    val map = item as? Map<String, Any?> ?: continue
                    LanguageOverride.fromMap(map)?.let { out[it.localeTag] = it }
                }
            }
            return out.also { parsedLangOverrides = raw to it }
        }
        set(value) = prefs.edit {
            val cleaned = value.values.filterNot { it.isDefault }.map { it.toMap() }
            putString(KEY_LANG_OVERRIDES, if (cleaned.isEmpty()) null else JsonCodec.stringify(cleaned))
        }

    fun languageOverrideFor(locale: Locale): LanguageOverride? {
        val map = languageOverrides
        if (map.isEmpty()) return null
        val tag = locale.toLanguageTag()
        return map[tag] ?: map.values.firstOrNull {
            it.localeTag.equals(tag, ignoreCase = true)
        }
    }

    fun setLanguageOverride(override: LanguageOverride) {
        val map = LinkedHashMap(languageOverrides)
        if (override.isDefault) map.remove(override.localeTag)
        else map[override.localeTag] = override
        languageOverrides = map
    }

    fun isLocaleEnabled(locale: Locale): Boolean = languageOverrideFor(locale)?.enabled ?: true

    // --- Per-app profiles ---

    var appProfiles: Map<String, AppProfile>
        get() {
            val raw = prefs.getString(KEY_APP_PROFILES, null).orEmpty()
            parsedAppProfiles?.let { (k, v) -> if (k == raw) return v }
            val out = LinkedHashMap<String, AppProfile>()
            if (raw.isNotEmpty()) {
                val arr = JsonCodec.parseArray(raw).orEmpty()
                for (item in arr) {
                    @Suppress("UNCHECKED_CAST")
                    val map = item as? Map<String, Any?> ?: continue
                    AppProfile.fromMap(map)?.let { out[it.packageName] = it }
                }
            }
            return out.also { parsedAppProfiles = raw to it }
        }
        set(value) = prefs.edit {
            val cleaned = value.values.filterNot { it.isDefault }.map { it.toMap() }
            putString(KEY_APP_PROFILES, if (cleaned.isEmpty()) null else JsonCodec.stringify(cleaned))
        }

    fun setAppProfile(profile: AppProfile) {
        val map = LinkedHashMap(appProfiles)
        if (profile.isDefault) map.remove(profile.packageName)
        else map[profile.packageName] = profile
        appProfiles = map
    }

    var seenApps: Set<String>
        get() = parsedSeenApps ?: (prefs.getStringSet(KEY_SEEN_APPS, emptySet())?.toSet() ?: emptySet()).also { parsedSeenApps = it }
        set(value) {
            parsedSeenApps = value
            prefs.edit { putStringSet(KEY_SEEN_APPS, value) }
        }

    fun noteSeenApp(packageName: String) {
        val cur = seenApps
        if (packageName in cur || cur.size >= MAX_SEEN_APPS) return
        seenApps = cur + packageName
    }

    // --- Voice presets ---

    var presets: List<VoicePreset>
        get() {
            val raw = prefs.getString(KEY_PRESETS, null).orEmpty()
            parsedPresets?.let { (k, v) -> if (k == raw) return v }
            val list = if (raw.isEmpty()) emptyList() else JsonCodec.parseArray(raw).orEmpty().mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                (item as? Map<String, Any?>)?.let(VoicePreset::fromMap)
            }
            return list.also { parsedPresets = raw to it }
        }
        set(value) = prefs.edit {
            putString(KEY_PRESETS, if (value.isEmpty()) null else JsonCodec.stringify(value.map { it.toMap() }))
        }

    fun capturePreset(name: String): VoicePreset = VoicePreset(
        name = name.trim().take(VoicePreset.MAX_NAME),
        voiceId = voiceId,
        styleId = styleId,
        customStyle = customStyle,
        baseSpeed = baseSpeed,
        basePitch = basePitch,
        volumePercent = volumePercent,
        lockSpeed = lockSpeed,
        lockPitch = lockPitch,
        lockVolume = lockVolume,
        clarity = clarityPreset,
        leveler = levelerEnabled,
        textOptions = textOptions
    )

    fun savePreset(name: String): VoicePreset? {
        val clean = name.trim().take(VoicePreset.MAX_NAME)
        if (clean.isEmpty()) return null
        val preset = capturePreset(clean)
        presets = presets.filterNot { it.name.equals(clean, ignoreCase = true) } + preset
        return preset
    }

    fun deletePreset(name: String) {
        presets = presets.filterNot { it.name.equals(name, ignoreCase = true) }
    }

    fun applyPreset(preset: VoicePreset) {
        voiceId = preset.voiceId
        styleId = preset.styleId
        customStyle = preset.customStyle
        baseSpeed = preset.baseSpeed
        basePitch = preset.basePitch
        volumePercent = preset.volumePercent
        lockSpeed = preset.lockSpeed
        lockPitch = preset.lockPitch
        lockVolume = preset.lockVolume
        clarityPreset = preset.clarity
        levelerEnabled = preset.leveler
        textOptions = preset.textOptions
    }

    fun currentPresetName(): String? {
        val list = presets
        if (list.isEmpty()) return null
        val now = capturePreset("now")
        return list.firstOrNull { it.matchesState(now) }?.name
    }

    private fun VoicePreset.matchesState(other: VoicePreset): Boolean =
        voiceId == other.voiceId &&
            styleId == other.styleId &&
            approxEq(customStyle.speed, other.customStyle.speed) &&
            approxEq(customStyle.pitch, other.customStyle.pitch) &&
            customStyle.sentencePauseMs == other.customStyle.sentencePauseMs &&
            customStyle.paragraphPauseMs == other.customStyle.paragraphPauseMs &&
            approxEq(baseSpeed, other.baseSpeed) &&
            approxEq(basePitch, other.basePitch) &&
            volumePercent == other.volumePercent &&
            lockSpeed == other.lockSpeed &&
            lockPitch == other.lockPitch &&
            lockVolume == other.lockVolume &&
            clarity == other.clarity &&
            leveler == other.leveler &&
            textOptions == other.textOptions

    private fun approxEq(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.005f

    fun cycleNextPreset(): VoicePreset? {
        val list = presets
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.name == currentPresetName() }
        val next = list[(idx + 1) % list.size]
        applyPreset(next)
        return next
    }

    // --- Sleep timer (device-local, never silences screen readers) ---

    fun sleepStopAt(nowMs: Long = System.currentTimeMillis()): Long {
        val at = prefs.getLong(KEY_SLEEP_STOP_AT, 0L)
        return if (at > 0L && at - nowMs <= (SLEEP_MAX_MINUTES + 1) * 60_000L && nowMs - at < SLEEP_HOLD_MS) at else 0L
    }

    fun isSleepStopped(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = sleepStopAt(nowMs)
        return at > 0L && nowMs >= at
    }

    fun armSleepTimer(minutes: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val at = nowMs + minutes.coerceIn(1, SLEEP_MAX_MINUTES) * 60_000L
        prefs.edit { putLong(KEY_SLEEP_STOP_AT, at) }
        return at
    }

    fun clearSleepTimer() {
        prefs.edit { remove(KEY_SLEEP_STOP_AT) }
    }

    // --- Recent speech opt-in (device-local; off by default) ---

    var recentSpeechEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECENT_SPEECH, false)
        set(value) = prefs.edit { putBoolean(KEY_RECENT_SPEECH, value) }

    // --- Voices cache & Dictionary ---

    /** Every language's voices from the last ML Kit listing, so the service can answer before ML Kit does. */
    var voices: List<VoiceEntry>
        get() {
            val raw = prefs.getString(KEY_VOICES, null).orEmpty()
            parsedVoices?.let { (key, list) -> if (key == raw) return list }
            return parseVoices(raw).also { parsedVoices = raw to it }
        }
        set(value) = prefs.edit {
            putString(KEY_VOICES, value.joinToString("\n") { "${it.name}|${it.locale.toLanguageTag()}|${it.gender}" })
        }

    /** The voice for a caller's language (ISO3 codes, as in a SynthesisRequest), respecting per-language overrides. */
    fun voiceFor(lang: String?, country: String?, preferredVoiceId: String? = null): VoiceEntry? {
        val match = pickVoice(voices, preferredVoiceId ?: voiceId, lang, country) ?: return null
        val langChoice = languageOverrideFor(match.locale)?.voiceId
        return if (preferredVoiceId == null && langChoice != null) {
            pickVoice(voices, langChoice, lang, country) ?: match
        } else match
    }

    fun voiceFor(locale: Locale, preferredVoiceId: String? = null): VoiceEntry? =
        voiceFor(locale.iso3Language(), locale.iso3Country(), preferredVoiceId)

    /** All voices of a language, the chosen one first. */
    fun voicesFor(lang: String?, country: String?, preferredVoiceId: String? = null): List<VoiceEntry> {
        val first = pickVoice(voices, preferredVoiceId ?: voiceId, lang, country)
        val effectiveId = preferredVoiceId ?: first?.let { languageOverrideFor(it.locale)?.voiceId } ?: voiceId
        return voicesFor(voices, effectiveId, lang, country)
    }

    fun voicesFor(locale: Locale, preferredVoiceId: String? = null): List<VoiceEntry> =
        voicesFor(locale.iso3Language(), locale.iso3Country(), preferredVoiceId)

    /** Every voice: the chosen one first (by id, so star02 when none is chosen), the phone's language first. */
    fun voicesAnyLanguage(preferredVoiceId: String? = voiceId): List<VoiceEntry> {
        val system = Locale.getDefault().language
        return voices.sortedWith(compareBy({ preferredVoiceId != null && it.id != preferredVoiceId }, { it.id }, { it.locale.language != system }))
    }

    var rules: List<DictionaryRule>
        get() {
            val jsonRaw = prefs.getString(KEY_RULES_JSON, null)
            if (jsonRaw != null) {
                parsedRuleList?.let { (k, v) -> if (k == jsonRaw) return v }
                val list = JsonCodec.parseArray(jsonRaw).orEmpty().mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? Map<String, Any?>)?.let(DictionaryRule::fromMap)
                }
                return list.also { parsedRuleList = jsonRaw to it }
            }
            val legacyRaw = prefs.getString(KEY_RULES, null).orEmpty()
            return parseLegacyRules(legacyRaw)
        }
        set(value) {
            val serialized = JsonCodec.stringify(value.map { it.toMap() })
            prefs.edit {
                putString(KEY_RULES_JSON, serialized)
                remove(KEY_RULES)
            }
        }

    /** Compiled dictionary and its content hash, rebuilt only when rules change. */
    val dictionary: Dictionary
        get() {
            val raw = prefs.getString(KEY_RULES_JSON, null) ?: prefs.getString(KEY_RULES, null).orEmpty()
            compiled?.let { (key, dict) -> if (key == raw) return dict }
            return Dictionary(rules).also { compiled = raw to it }
        }

    private fun parseVoices(raw: String): List<VoiceEntry> = raw.lines().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size >= 2) VoiceEntry(parts[0], Locale.forLanguageTag(parts[1]), parts.getOrElse(2) { "" }) else null
    }

    private fun parseLegacyRules(raw: String): List<DictionaryRule> = raw.lines().mapNotNull { line ->
        val parts = line.split(SEP)
        if (parts.size >= 2 && parts[0].isNotEmpty()) {
            DictionaryRule(parts[0], parts[1], parts.getOrNull(2).toBoolean())
        } else null
    }

    companion object {
        private const val NAME = "tts_prefs"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_CACHE = "cache_enabled"
        private const val KEY_VOICES = "genai_voices"
        private const val KEY_AUTO_DOWNLOAD = "auto_download"
        private const val KEY_ENGINE_VERSION = "engine_version"
        private const val KEY_RULES = "dictionary_rules"
        private const val KEY_RULES_JSON = "dictionary_rules_json"
        private const val KEY_STYLE = "reading_style"
        private const val KEY_CUSTOM_SPEED = "custom_speed"
        private const val KEY_CUSTOM_PITCH = "custom_pitch"
        private const val KEY_CUSTOM_SENTENCE = "custom_sentence_pause"
        private const val KEY_CUSTOM_PARAGRAPH = "custom_paragraph_pause"
        private const val KEY_BASE_SPEED = "base_speed"
        private const val KEY_BASE_PITCH = "base_pitch"
        private const val KEY_VOLUME_PERCENT = "volume_percent"
        private const val KEY_LOCK_SPEED = "lock_speed"
        private const val KEY_LOCK_PITCH = "lock_pitch"
        private const val KEY_LOCK_VOLUME = "lock_volume"
        private const val KEY_CLARITY = "clarity_preset"
        private const val KEY_LEVELER = "leveler_enabled"
        private const val KEY_TRIM_SILENCE = "trim_silence"
        private const val KEY_TEXT_OPTIONS = "text_options_json"
        private const val KEY_LANG_OVERRIDES = "language_overrides_json"
        private const val KEY_APP_PROFILES = "app_profiles_json"
        private const val KEY_SEEN_APPS = "seen_apps"
        private const val KEY_PRESETS = "voice_presets_json"
        private const val KEY_SLEEP_STOP_AT = "sleep_stop_at_ms"
        private const val KEY_RECENT_SPEECH = "recent_speech_enabled"
        private const val SEP = "=>"
        private const val MAX_SEEN_APPS = 40
        const val SLEEP_MAX_MINUTES = 60
        const val SLEEP_HOLD_MS = 6 * 60 * 60_000L

        @Volatile private var compiled: Pair<String, Dictionary>? = null
        @Volatile private var parsedVoices: Pair<String, List<VoiceEntry>>? = null
        @Volatile private var parsedRuleList: Pair<String, List<DictionaryRule>>? = null
        @Volatile private var parsedTextOptions: Pair<String, TextOptions>? = null
        @Volatile private var parsedLangOverrides: Pair<String, Map<String, LanguageOverride>>? = null
        @Volatile private var parsedAppProfiles: Pair<String, Map<String, AppProfile>>? = null
        @Volatile private var parsedPresets: Pair<String, List<VoicePreset>>? = null
        @Volatile private var parsedSeenApps: Set<String>? = null
    }
}
