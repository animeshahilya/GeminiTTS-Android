package com.animeshahilya.geminitts.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeshahilya.geminitts.R
import com.animeshahilya.geminitts.data.DictionaryRule
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.label
import java.util.Locale
import kotlin.math.roundToInt

private const val FEEDBACK_LINK = "https://chat.whatsapp.com/HKVrT3hFRsjER9mE2Jogax"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Voice", "Languages", "Dictionary", "About")

    // The user may have changed the preferred engine in system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshEngineCheck() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    0 -> VoiceTab(state, viewModel)
                    1 -> LanguagesTab(state, viewModel)
                    2 -> DictionaryTab(state, viewModel)
                    else -> AboutTab()
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

private fun openTtsSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun VoiceTab(state: UiState, viewModel: MainScreenViewModel) {
    val context = LocalContext.current

    if (!state.isDefaultEngine) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemini TTS isn't your preferred speech engine yet. Choose it in the text-to-speech settings so TalkBack and other apps use it.")
                Button(onClick = { openTtsSettings(context) }) { Text("Open text-to-speech settings") }
            }
        }
    }

    SectionTitle("Voice")
    if (state.loading) Text("Looking for voices…")
    state.error?.let { Text(it) }
    if (state.voices.isNotEmpty()) {
        Text("Used for every language that has it.", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            state.voices.forEach { voice ->
                val label = if (voice.gender == "unknown") voice.id else "${voice.id}, ${voice.gender}"
                Row(
                    Modifier.fillMaxWidth()
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
                    Text(label)
                }
            }
        }
    }

    SectionTitle("Reading style")
    Text("Applies to every app, voice and language.", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        (ReadingStyle.PRESETS + state.customStyle).forEach { style ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(
                        selected = style.id == state.styleId,
                        onClick = { viewModel.setStyle(style.id) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 8.dp),
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
        LabeledSlider("Speed", c.speed, 0.5f..2f) { viewModel.setCustomStyle(c.copy(speed = it)) }
        LabeledSlider("Pitch", c.pitch, 0.5f..2f) { viewModel.setCustomStyle(c.copy(pitch = it)) }
        PauseSlider("Pause after sentences", c.sentencePauseMs, 1500) { viewModel.setCustomStyle(c.copy(sentencePauseMs = it)) }
        PauseSlider("Pause after paragraphs", c.paragraphPauseMs, 3000) { viewModel.setCustomStyle(c.copy(paragraphPauseMs = it)) }
    }

    SectionTitle("Try it")
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
            OutlinedButton(onClick = { open = true }) { Text("Language: ${state.testLocale.label()}") }
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
    LabeledSlider("Test speed", state.testRate, 0.5f..3f, viewModel::setTestRate)
    LabeledSlider("Test pitch", state.testPitch, 0.5f..2f, viewModel::setTestPitch)
    Text(
        "TalkBack and other apps use their own speed and pitch.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { viewModel.speak(text) }, enabled = text.isNotBlank()) { Text("Speak") }
        OutlinedButton(onClick = viewModel::stop, enabled = state.speaking) { Text("Stop") }
    }
    val timing = listOfNotNull(
        state.firstAudioMs?.let { "First sound after $it ms" },
        state.totalMs?.let { "finished in ${"%.1f".format(Locale.ROOT, it / 1000f)} s" }
    ).joinToString(", ")
    if (timing.isNotEmpty()) {
        Text(timing, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }

    SectionTitle("Saved audio")
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = state.cacheEnabled, onValueChange = viewModel::setCacheEnabled, role = Role.Switch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Remember spoken phrases")
            Text(
                "Phrases you hear often start instantly. Uses up to 64 MB.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = state.cacheEnabled, onCheckedChange = null)
    }
    Text("${state.cachedPhrases} phrases saved, ${"%.1f".format(Locale.ROOT, state.cachedBytes / (1024f * 1024f))} MB")
    OutlinedButton(onClick = viewModel::clearCache, enabled = state.cachedPhrases > 0) { Text("Clear saved audio") }
}

@Composable
private fun LanguagesTab(state: UiState, viewModel: MainScreenViewModel) {
    SectionTitle("Languages")
    Text(
        "Apps switch language as they read. A language that isn't downloaded uses the phone's standard voice for it.",
        style = MaterialTheme.typography.bodySmall
    )
    if (state.loading) Text("Looking for languages…")
    state.languages.forEach { row ->
        val name = row.locale.label()
        val status = when (val s = row.status) {
            ModelStatus.Checking -> "checking"
            ModelStatus.Ready -> "ready"
            ModelStatus.NeedsDownload -> "not downloaded"
            is ModelStatus.Downloading -> if (s.bytes > 0) "downloading, ${s.bytes / (1024 * 1024)} MB" else "downloading"
            is ModelStatus.Unavailable -> s.reason
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$name: $status", modifier = Modifier.weight(1f))
            if (row.status == ModelStatus.NeedsDownload) {
                TextButton(
                    onClick = { viewModel.download(row.locale) },
                    modifier = Modifier.semantics { contentDescription = "Download $name" }
                ) { Text("Download") }
            }
        }
    }
}

@Composable
private fun PauseSlider(label: String, valueMs: Int, maxMs: Int, onChange: (Int) -> Unit) {
    // Steps of 50 ms, so TalkBack users can move it in useful amounts.
    val shown = if (valueMs == 0) "none" else "${"%.2f".format(Locale.ROOT, valueMs / 1000f)} seconds"
    Column {
        Text("$label: $shown")
        Slider(
            value = valueMs.toFloat(),
            onValueChange = { onChange((it / 50).roundToInt() * 50) },
            valueRange = 0f..maxMs.toFloat(),
            steps = maxMs / 50 - 1,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = shown
            }
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val shown = "%.2fx".format(Locale.ROOT, value)
    Column {
        Text("$label: $shown")
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = shown
            }
        )
    }
}

@Composable
private fun DictionaryTab(state: UiState, viewModel: MainScreenViewModel) {
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionTitle("Add a pronunciation")
    Text(
        "Change how a word is read, for example \"GIF\" said as \"jif\". Whole words only.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        value = pattern, onValueChange = { pattern = it; error = null },
        label = { Text("Word") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = replacement, onValueChange = { replacement = it; error = null },
        label = { Text("Say it as") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Row(
        Modifier.toggleable(value = isRegex, onValueChange = { isRegex = it }, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isRegex, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text("Regular expression")
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
    }
    FilledTonalButton(onClick = {
        error = viewModel.addRule(pattern, replacement, isRegex)
        if (error == null) {
            pattern = ""
            replacement = ""
            isRegex = false
        }
    }) { Text("Add") }

    SectionTitle("Your pronunciations")
    if (state.rules.isEmpty()) {
        Text("None yet.")
    }
    state.rules.forEach { rule -> RuleRow(rule, onDelete = { viewModel.removeRule(rule) }) }
}

@Composable
private fun RuleRow(rule: DictionaryRule, onDelete: () -> Unit) {
    val said = rule.replacement.ifEmpty { "nothing" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${rule.pattern} → $said" + if (rule.isRegex) " (regular expression)" else "",
            modifier = Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = "${rule.pattern}, said as $said" + if (rule.isRegex) ", regular expression" else ""
            }
        )
        TextButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${rule.pattern}" }
        ) { Text("Delete") }
    }
}

@Composable
private fun AboutTab() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SectionTitle("${stringResource(R.string.app_name)} $version")
    Text(
        "A text-to-speech engine for TalkBack and other apps that uses the on-device GenAI voices " +
            "in Google's Speech Services. Speech is made on the phone; nothing you hear is sent anywhere."
    )
    Text(
        "Not made by or affiliated with Google.",
        style = MaterialTheme.typography.bodySmall
    )
    SectionTitle("Feedback")
    Text("To give feedback about this app, join the WhatsApp group adev ally.")
    Button(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, FEEDBACK_LINK.toUri())) }
    }) { Text("Join adev ally on WhatsApp") }
}
