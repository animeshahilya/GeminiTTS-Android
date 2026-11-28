package com.animeshahilya.geminitts.data

import com.animeshahilya.geminitts.audio.ClarityPreset

/**
 * Backup & restore for user settings, per-language/app overrides, presets, and dictionary rules,
 * plus NVDA `.dic` import/export. Device-local bookkeeping (downloaded voice list, engine version,
 * seen apps, active sleep timer, recent speech toggle) is intentionally never exported or imported.
 */
object BackupRestore {

    const val BACKUP_VERSION = 1
    const val MAX_IMPORT_CHARS = 5 * 1024 * 1024

    fun exportBackupJson(prefs: TtsPrefs): String {
        val custom = prefs.customStyle
        val root = linkedMapOf<String, Any?>(
            "version" to BACKUP_VERSION,
            "voiceId" to prefs.voiceId,
            "cacheEnabled" to prefs.cacheEnabled,
            "autoDownload" to prefs.autoDownload,
            "styleId" to prefs.styleId,
            "customStyle" to linkedMapOf(
                "speed" to custom.speed,
                "pitch" to custom.pitch,
                "sentencePauseMs" to custom.sentencePauseMs,
                "paragraphPauseMs" to custom.paragraphPauseMs
            ),
            "baseSpeed" to prefs.baseSpeed,
            "basePitch" to prefs.basePitch,
            "volumePercent" to prefs.volumePercent,
            "lockSpeed" to prefs.lockSpeed,
            "lockPitch" to prefs.lockPitch,
            "lockVolume" to prefs.lockVolume,
            "clarity" to prefs.clarityPreset.id,
            "leveler" to prefs.levelerEnabled,
            "trimSilence" to prefs.trimSilence,
            "textOptions" to prefs.textOptions.toMap(),
            "languageOverrides" to prefs.languageOverrides.values.map { it.toMap() },
            "appProfiles" to prefs.appProfiles.values.map { it.toMap() },
            "presets" to prefs.presets.map { it.toMap() },
            "dictionary" to prefs.rules.map { it.toMap() }
        )
        return JsonCodec.stringify(root, pretty = true)
    }

    data class ImportResult(val ruleCount: Int, val skippedRules: Int)

    /** Restores a JSON backup and returns restored and skipped dictionary rule counts. */
    fun importBackupJson(prefs: TtsPrefs, raw: String): ImportResult {
        require(raw.length <= MAX_IMPORT_CHARS) { "Backup file exceeds 5 MB limit." }
        val cleaned = raw.removePrefix("\uFEFF")
        val root = JsonCodec.parseObject(cleaned) ?: throw IllegalArgumentException("Unreadable JSON backup file.")

        if ("voiceId" in root) prefs.voiceId = root["voiceId"]?.toString()?.takeIf { it.isNotBlank() }?.let(::voiceId)
        if ("cacheEnabled" in root) prefs.cacheEnabled = root.bool("cacheEnabled", prefs.cacheEnabled)
        if ("autoDownload" in root) prefs.autoDownload = root.bool("autoDownload", prefs.autoDownload)
        if ("styleId" in root) {
            val rawStyle = root.str("styleId", ReadingStyle.STANDARD)
            val valid = rawStyle == ReadingStyle.CUSTOM || ReadingStyle.PRESETS.any { it.id == rawStyle }
            prefs.styleId = if (valid) rawStyle else ReadingStyle.STANDARD
        }

        @Suppress("UNCHECKED_CAST")
        (root["customStyle"] as? Map<String, Any?>)?.let { cs ->
            val cur = prefs.customStyle
            prefs.customStyle = ReadingStyle.custom(
                cs.float("speed", cur.speed).coerceIn(0.5f, 3f),
                cs.float("pitch", cur.pitch).coerceIn(0.5f, 2f),
                cs.int("sentencePauseMs", cur.sentencePauseMs).coerceIn(0, 1500),
                cs.int("paragraphPauseMs", cur.paragraphPauseMs).coerceIn(0, 3000)
            )
        }

        if ("baseSpeed" in root) prefs.baseSpeed = root.float("baseSpeed", prefs.baseSpeed)
        if ("basePitch" in root) prefs.basePitch = root.float("basePitch", prefs.basePitch)
        if ("volumePercent" in root) prefs.volumePercent = root.int("volumePercent", prefs.volumePercent)
        if ("lockSpeed" in root) prefs.lockSpeed = root.bool("lockSpeed", prefs.lockSpeed)
        if ("lockPitch" in root) prefs.lockPitch = root.bool("lockPitch", prefs.lockPitch)
        if ("lockVolume" in root) prefs.lockVolume = root.bool("lockVolume", prefs.lockVolume)
        if ("clarity" in root) prefs.clarityPreset = ClarityPreset.fromId(root.str("clarity", prefs.clarityPreset.id))
        if ("leveler" in root) prefs.levelerEnabled = root.bool("leveler", prefs.levelerEnabled)
        if ("trimSilence" in root) prefs.trimSilence = root.bool("trimSilence", prefs.trimSilence)

        @Suppress("UNCHECKED_CAST")
        (root["textOptions"] as? Map<String, Any?>)?.let {
            prefs.textOptions = TextOptions.fromMap(it)
        }

        (root["languageOverrides"] as? List<*>)?.let { arr ->
            val map = LinkedHashMap<String, LanguageOverride>()
            for (item in arr) {
                @Suppress("UNCHECKED_CAST")
                val obj = item as? Map<String, Any?> ?: continue
                LanguageOverride.fromMap(obj)?.let { map[it.localeTag] = it }
            }
            prefs.languageOverrides = map
        }

        (root["appProfiles"] as? List<*>)?.let { arr ->
            val map = LinkedHashMap<String, AppProfile>()
            for (item in arr) {
                @Suppress("UNCHECKED_CAST")
                val obj = item as? Map<String, Any?> ?: continue
                AppProfile.fromMap(obj)?.let { map[it.packageName] = it }
            }
            prefs.appProfiles = map
        }

        (root["presets"] as? List<*>)?.let { arr ->
            prefs.presets = arr.mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                (item as? Map<String, Any?>)?.let(VoicePreset::fromMap)
            }
        }

        var ruleCount = prefs.rules.size
        var skippedRules = 0
        (root["dictionary"] as? List<*>)?.let { arr ->
            val rules = ArrayList<DictionaryRule>()
            for (item in arr) {
                @Suppress("UNCHECKED_CAST")
                val parsed = (item as? Map<String, Any?>)?.let(DictionaryRule::fromMap)
                if (parsed != null && parsed.isValid()) rules += parsed else skippedRules++
            }
            prefs.rules = rules
            ruleCount = rules.size
        }
        return ImportResult(ruleCount, skippedRules)
    }

    /**
     * Parses NVDA `.dic` tab-separated lines (`pattern\treplacement\tcaseSensitive\ttype`).
     * Types: `0` = anywhere/substring (`wholeWord=false`), `1` = regex, `2` = whole word (`wholeWord=true`).
     */
    fun parseNvdaDic(raw: String, language: String = ""): List<DictionaryRule> {
        require(raw.length <= MAX_IMPORT_CHARS) { "Dictionary file exceeds 5 MB limit." }
        val rules = ArrayList<DictionaryRule>()
        for (line in raw.removePrefix("\uFEFF").lineSequence()) {
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val pattern = parts[0]
            if (pattern.isEmpty()) continue
            val replacement = parts[1]
            val caseSensitive = parts.getOrNull(2)?.trim() == "1"
            val type = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
            val isRegex = type == 1
            val wholeWord = type == 2
            val rule = DictionaryRule(
                pattern = pattern,
                replacement = replacement,
                isRegex = isRegex,
                wholeWord = wholeWord,
                caseSensitive = caseSensitive,
                language = language
            )
            if (rule.isValid()) rules += rule
        }
        return rules
    }

    fun formatNvdaDic(rules: List<DictionaryRule>): String = buildString {
        appendLine("# Gemini TTS dictionary export (NVDA .dic compatible)")
        for (r in rules) {
            if (r.isCharacter) continue // NVDA .dic has no single-character-only category
            val cs = if (r.caseSensitive) "1" else "0"
            val type = when {
                r.isRegex -> "1"
                r.wholeWord -> "2"
                else -> "0"
            }
            append(r.pattern.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
            append('\t')
            append(r.replacement.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
            append('\t').append(cs)
            append('\t').appendLine(type)
        }
    }
}
