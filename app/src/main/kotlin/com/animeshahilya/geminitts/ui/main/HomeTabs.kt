package com.animeshahilya.geminitts.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.animeshahilya.geminitts.data.DictionaryRule
import com.animeshahilya.geminitts.data.LanguageOverride
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.label
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VoiceTab(state: UiState, viewModel: MainScreenViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var tryExpanded by rememberSaveable { mutableStateOf(false) }

    if (!state.isDefaultEngine) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemini TTS isn't your preferred speech engine yet. Choose it in text-to-speech settings so TalkBack and other apps use it.")
                Button(onClick = { openTtsSettings(context) }) { Text("Open text-to-speech settings") }
            }
        }
    }

    if (state.presets.isNotEmpty()) {
        SectionTitle("Presets")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.presets.forEach { preset ->
                FilterChip(
                    selected = preset.name == state.activePresetName,
                    onClick = { viewModel.applyPreset(preset) },
                    label = { Text(preset.name) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
            }
        }
    }

    SectionTitle("Voice")
    if (state.loading) Text("Looking for voices…")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.voices.isNotEmpty()) {
        Text("Default voice across every language unless overridden.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            state.voices.forEach { voice ->
                val desc = describeVoice(voice.id, voice.gender)
                val isPreviewing = state.previewingVoiceId == voice.id
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .selectable(
                                selected = voice.id == state.selectedVoice,
                                onClick = { viewModel.selectVoice(voice.id) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = voice.id == state.selectedVoice, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(desc)
                    }
                    TextButton(
                        onClick = { viewModel.previewVoice(voice.id) },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            .semantics {
                                contentDescription = if (isPreviewing) "Stop preview of ${voice.id}" else "Preview ${voice.id}"
                            }
                    ) {
                        Text(if (isPreviewing) "Stop" else "Preview")
                    }
                }
            }
        }
    }

    SectionTitle("Reading style")
    Text("Applies across apps, voices and languages unless overridden.", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        (ReadingStyle.PRESETS + state.customStyle).forEach { style ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(
                        selected = style.id == state.styleId,
                        onClick = { viewModel.setStyle(style.id) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = style.id == state.styleId, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(style.name)
                    Text(style.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (state.styleId == ReadingStyle.CUSTOM) {
        val c = state.customStyle
        FactorSlider("Style speed", c.speed, 0.5f..3f, step = 0.05f, defaultValue = 1f) {
            viewModel.setCustomStyle(c.copy(speed = it))
        }
        FactorSlider("Style pitch", c.pitch, 0.5f..2f, step = 0.05f, defaultValue = 1f) {
            viewModel.setCustomStyle(c.copy(pitch = it))
        }
        PauseSlider("Pause after sentences", c.sentencePauseMs, 1500) {
            viewModel.setCustomStyle(c.copy(sentencePauseMs = it))
        }
        PauseSlider("Pause after paragraphs", c.paragraphPauseMs, 3000) {
            viewModel.setCustomStyle(c.copy(paragraphPauseMs = it))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clickable { tryExpanded = !tryExpanded }
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (tryExpanded) "Expanded" else "Collapsed"
                        contentDescription = "Try current setup, test speech in ${state.testLocale.label()}"
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Try current setup", style = MaterialTheme.typography.titleSmall)
                    Text("Test voice, speed, clarity and rules in ${state.testLocale.label()}", style = MaterialTheme.typography.bodySmall)
                }
                Text(if (tryExpanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (tryExpanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    var text by rememberSaveable { mutableStateOf("Home screen, page 1 of 3. Double-tap to activate Chrome.") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text to speak") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.languages.isNotEmpty()) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { open = true },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) { Text("Language: ${state.testLocale.label()}") }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                state.languages.forEach { row ->
                                    DropdownMenuItem(
                                        text = { Text(row.locale.label()) },
                                        onClick = { viewModel.setTestLocale(row.locale); open = false }
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.speak(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Speak") }
                        OutlinedButton(
                            onClick = viewModel::stop,
                            enabled = state.speaking,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Stop") }
                    }
                    val timing = listOfNotNull(
                        state.firstAudioMs?.let { "First sound after $it ms" },
                        state.totalMs?.let { "finished in ${"%.1f".format(Locale.ROOT, it / 1000f)} s" }
                    ).joinToString(", ")
                    if (timing.isNotEmpty()) {
                        Text(timing, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }

    TextButton(onClick = onOpenSettings, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text("Speed, volume, clarity, per-app profiles & tools in Settings")
    }
}

@Composable
fun LanguagesTab(state: UiState, viewModel: MainScreenViewModel) {
    var editingLocale by remember { mutableStateOf<Locale?>(null) }

    SectionTitle("Languages")
    Text(
        "Download languages for offline pronunciation, or tune voice, speed, pitch and volume per language.",
        style = MaterialTheme.typography.bodySmall
    )
    SwitchRow(
        "Download languages automatically",
        "The first time an app reads a language, its voice downloads in the background.",
        state.autoDownload,
        viewModel::setAutoDownload
    )
    if (state.loading) Text("Looking for languages…")

    state.languages.forEach { row ->
        val name = row.locale.label()
        val tag = row.locale.toLanguageTag()
        val ov = state.languageOverrides[tag]
        val status = when (val s = row.status) {
            ModelStatus.Checking -> "checking"
            ModelStatus.Ready -> "ready"
            ModelStatus.NeedsDownload -> "not downloaded"
            is ModelStatus.Downloading -> if (s.bytes > 0) "downloading, ${s.bytes / (1024 * 1024)} MB" else "downloading"
            is ModelStatus.Unavailable -> s.reason
        }
        val overrideSummary = ov?.let {
            val parts = listOfNotNull(
                it.voiceId,
                if (it.speed != 1f) "${"%.2fx".format(Locale.ROOT, it.speed)} speed" else null,
                if (it.pitch != 1f) "${"%.2fx".format(Locale.ROOT, it.pitch)} pitch" else null,
                if (it.volume != 1f) "${(it.volume * 100).roundToInt()}% vol" else null,
                if (!it.enabled) "hidden" else null
            )
            if (parts.isEmpty()) null else " (${parts.joinToString(", ")})"
        }.orEmpty()
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$name: $status$overrideSummary", modifier = Modifier.weight(1f))
            if (row.status == ModelStatus.NeedsDownload) {
                TextButton(
                    onClick = { viewModel.download(row.locale) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Download $name" }
                ) { Text("Download") }
            }
            TextButton(
                onClick = { editingLocale = row.locale },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Customize $name" }
            ) { Text("Tune") }
        }
    }

    editingLocale?.let { locale ->
        LanguageOverrideDialog(
            locale = locale,
            initial = state.languageOverrides[locale.toLanguageTag()] ?: LanguageOverride(locale.toLanguageTag()),
            voices = viewModel.voicesInLocale(locale),
            onSave = { viewModel.setLanguageOverride(it); editingLocale = null },
            onDismiss = { editingLocale = null }
        )
    }
}

@Composable
private fun LanguageOverrideDialog(
    locale: Locale,
    initial: LanguageOverride,
    voices: List<VoiceChoice>,
    onSave: (LanguageOverride) -> Unit,
    onDismiss: () -> Unit
) {
    var voiceId by remember { mutableStateOf(initial.voiceId) }
    var speed by remember { mutableFloatStateOf(initial.speed) }
    var pitch by remember { mutableFloatStateOf(initial.pitch) }
    var volume by remember { mutableFloatStateOf(initial.volume) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var voiceMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(locale.label(), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { voiceMenuOpen = true }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                        Text("Voice: ${voiceId ?: "Global default"}")
                    }
                    DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Global default") }, onClick = { voiceId = null; voiceMenuOpen = false })
                        voices.forEach { v ->
                            DropdownMenuItem(text = { Text(describeVoice(v.id, v.gender)) }, onClick = { voiceId = v.id; voiceMenuOpen = false })
                        }
                    }
                }
                FactorSlider("Language speed", speed, 0.5f..2.5f, step = 0.05f, defaultValue = 1f) { speed = it }
                FactorSlider("Language pitch", pitch, 0.5f..2f, step = 0.05f, defaultValue = 1f) { pitch = it }
                PercentSlider("Language volume", (volume * 100).roundToInt(), 25..200, step = 5, defaultPercent = 100) {
                    volume = it / 100f
                }
                SwitchRow("Show in system language list", "Keep this language visible to Android TTS settings.", enabled) { enabled = it }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(LanguageOverride(locale.toLanguageTag(), voiceId, speed, pitch, volume, enabled)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!initial.isDefault) {
                    TextButton(onClick = { onSave(LanguageOverride(locale.toLanguageTag())) }) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun DictionaryTab(
    state: UiState,
    viewModel: MainScreenViewModel,
    onImportDic: () -> Unit,
    onExportDic: () -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableIntStateOf(0) }
    var editingRule by remember { mutableStateOf<Pair<DictionaryRule?, DictionaryRule>?>(null) }
    var showTester by rememberSaveable { mutableStateOf(false) }
    var testPhrase by rememberSaveable { mutableStateOf("") }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { editingRule = null to DictionaryRule("", "") },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) { Text("Add rule") }
        OutlinedButton(onClick = onImportDic, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Import .dic") }
        OutlinedButton(onClick = onExportDic, enabled = state.rules.isNotEmpty(), modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Export .dic")
        }
    }

    if (state.rules.isNotEmpty()) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search rules") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("All", "Words", "Single char").forEachIndexed { idx, label ->
                FilterChip(
                    selected = filterMode == idx,
                    onClick = { filterMode = idx },
                    label = { Text(label) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
            }
            TextButton(
                onClick = { showTester = !showTester },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text(if (showTester) "Hide test" else "Test") }
        }
        if (showTester) {
            OutlinedTextField(
                value = testPhrase,
                onValueChange = { testPhrase = it },
                label = { Text("Test dictionary on phrase") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (testPhrase.isNotBlank()) {
                val out = remember(testPhrase, state.rules, state.testLocale) {
                    viewModel.previewTextTransform(testPhrase, useDictionaryOnly = true)
                }
                Text("Result: $out", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }

    SectionTitle("Your pronunciations (${state.rules.size})")
    val visible = remember(state.rules, search, filterMode) {
        val q = search.trim().lowercase()
        state.rules.filter { r ->
            val matchFilter = when (filterMode) {
                1 -> !r.isCharacter
                2 -> r.isCharacter
                else -> true
            }
            val matchQuery = q.isEmpty() || r.pattern.lowercase().contains(q) || r.replacement.lowercase().contains(q)
            matchFilter && matchQuery
        }
    }
    if (visible.isEmpty()) {
        Text(
            if (state.rules.isEmpty()) "No pronunciation rules yet. Tap Add rule or import an NVDA .dic file." else "No rules match your filter.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    visible.forEach { rule ->
        RuleRow(
            rule = rule,
            onEdit = { editingRule = rule to rule },
            onDelete = { viewModel.removeRule(rule) }
        )
        HorizontalDivider()
    }

    editingRule?.let { (existing, draft) ->
        RuleEditorDialog(
            isEdit = existing != null,
            initial = draft,
            languages = state.languages,
            onSave = { updated -> viewModel.saveRule(existing, updated) },
            onDismiss = { editingRule = null }
        )
    }
}

@Composable
private fun RuleRow(rule: DictionaryRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val said = rule.replacement.ifEmpty { "nothing" }
    val flags = listOfNotNull(
        if (rule.isRegex) "regex" else if (rule.wholeWord) null else "substring",
        if (rule.caseSensitive) "case" else null,
        if (rule.isCharacter) "single char" else null,
        rule.language.takeIf { it.isNotEmpty() }
    )
    val flagSuffix = if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${rule.pattern} → $said$flagSuffix",
            modifier = Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = "${rule.pattern}, said as $said$flagSuffix"
            }
        )
        TextButton(
            onClick = onEdit,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "Edit ${rule.pattern}" }
        ) { Text("Edit") }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "Delete ${rule.pattern}" }
        ) { Text("Delete") }
    }
}

@Composable
private fun RuleEditorDialog(
    isEdit: Boolean,
    initial: DictionaryRule,
    languages: List<LanguageRow>,
    onSave: (DictionaryRule) -> String?,
    onDismiss: () -> Unit
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var replacement by remember { mutableStateOf(initial.replacement) }
    var isRegex by remember { mutableStateOf(initial.isRegex) }
    var wholeWord by remember { mutableStateOf(initial.wholeWord) }
    var caseSensitive by remember { mutableStateOf(initial.caseSensitive) }
    var isCharacter by remember { mutableStateOf(initial.isCharacter) }
    var language by remember { mutableStateOf(initial.language) }
    var langOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit pronunciation" else "Add pronunciation", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it; error = null },
                    label = { Text("Word or pattern") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it; error = null },
                    label = { Text("Say it as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                CheckboxRow("Regular expression", isRegex) { isRegex = it; error = null }
                if (!isRegex && !isCharacter) {
                    CheckboxRow("Whole words only", wholeWord) { wholeWord = it }
                }
                CheckboxRow("Case sensitive", caseSensitive) { caseSensitive = it }
                CheckboxRow("Single character / spelling only", isCharacter) { isCharacter = it }
                Box {
                    OutlinedButton(onClick = { langOpen = true }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                        Text("Language: ${if (language.isEmpty()) "All languages" else language}")
                    }
                    DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                        DropdownMenuItem(text = { Text("All languages") }, onClick = { language = ""; langOpen = false })
                        languages.forEach { row ->
                            val tag = row.locale.toLanguageTag()
                            DropdownMenuItem(text = { Text("${row.locale.label()} ($tag)") }, onClick = { language = tag; langOpen = false })
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = onSave(DictionaryRule(pattern, replacement, isRegex, wholeWord, caseSensitive, language, isCharacter))
                if (err == null) onDismiss() else error = err
            }) { Text(if (isEdit) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
