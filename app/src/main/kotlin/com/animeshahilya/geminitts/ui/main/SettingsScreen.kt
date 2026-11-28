package com.animeshahilya.geminitts.ui.main

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.animeshahilya.geminitts.R
import com.animeshahilya.geminitts.audio.ClarityPreset
import com.animeshahilya.geminitts.data.AppProfile
import com.animeshahilya.geminitts.data.DigitGroupingMode
import com.animeshahilya.geminitts.data.ReadingStyle
import com.animeshahilya.geminitts.data.TextOptions
import com.animeshahilya.geminitts.data.TextPreset
import com.animeshahilya.geminitts.ui.RecentSpeechActivity
import kotlinx.coroutines.delay
import java.util.Locale

private const val FEEDBACK_LINK = "https://chat.whatsapp.com/HKVrT3hFRsjER9mE2Jogax"

enum class SettingsPage(val title: String) {
    HUB("Settings"),
    AUDIO("Speed, pitch & clarity"),
    TEXT("Text, numbers & OTPs"),
    APPS("Per-app overrides"),
    PRESETS("Voice presets"),
    UTILITIES("Sleep timer & recent speech"),
    BACKUP_ABOUT("Backup, restore & about")
}

@Composable
fun SettingsScreen(
    page: SettingsPage,
    state: UiState,
    viewModel: MainScreenViewModel,
    onNavigate: (SettingsPage) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    when (page) {
        SettingsPage.HUB -> SettingsHub(state, onNavigate)
        SettingsPage.AUDIO -> AudioSettingsPage(state, viewModel)
        SettingsPage.TEXT -> TextSettingsPage(state, viewModel)
        SettingsPage.APPS -> AppProfilesSettingsPage(state, viewModel)
        SettingsPage.PRESETS -> PresetsSettingsPage(state, viewModel)
        SettingsPage.UTILITIES -> UtilitiesSettingsPage(state, viewModel)
        SettingsPage.BACKUP_ABOUT -> BackupAboutSettingsPage(onExportBackup, onImportBackup)
    }
}

@Composable
private fun SettingsHub(state: UiState, onNavigate: (SettingsPage) -> Unit) {
    val lockLabels = listOfNotNull(
        if (state.lockSpeed) "speed locked" else null,
        if (state.lockPitch) "pitch locked" else null,
        if (state.lockVolume) "vol locked" else null
    )
    val audioSummary = buildString {
        append("${formatFactor(state.baseSpeed)} • ${state.volumePercent}% vol • ${state.clarityPreset.label}")
        if (lockLabels.isNotEmpty()) append(" (${lockLabels.joinToString(", ")})")
    }

    val opts = state.textOptions
    val textFeatures = listOfNotNull(
        if (opts.readCodesDigits) "OTP digits" else null,
        if (opts.indianNumbering) "Indian numbering" else null,
        if (opts.digitGrouping != DigitGroupingMode.OFF) opts.digitGrouping.label else null,
        if (opts.cleanMarkdown) "Markdown" else null,
        if (opts.silenceSymbols) "Silenced symbols" else null,
        if (opts.splitCamelCase) "Code split" else null,
        if (opts.shortenUrls) "Short URLs" else null,
        if (opts.normalizeTechWords) "Tech words" else null
    )
    val textSummary = if (textFeatures.isEmpty()) "Standard Unicode & repeat cleaning" else textFeatures.joinToString(", ")

    val appSummary = if (state.appProfiles.isEmpty()) "No app overrides yet" else "${state.appProfiles.size} app(s) customized"
    val presetSummary = state.activePresetName?.let { "Active: $it" }
        ?: if (state.presets.isEmpty()) "Save setups for one-tap switching" else "${state.presets.size} saved"
    val now = System.currentTimeMillis()
    val utilSummary = buildString {
        append(
            when {
                state.sleepStopAtMs <= 0L -> "Sleep timer off"
                now >= state.sleepStopAtMs -> "Sleep timer active"
                else -> "Sleep timer armed"
            }
        )
        append(" • ")
        append(if (state.recentSpeechEnabled) "Recent speech on (${state.recentSpeechCount})" else "Recent speech off")
    }

    PreferenceRow("Speed, pitch & clarity", audioSummary) { onNavigate(SettingsPage.AUDIO) }
    PreferenceRow("Text, numbers & OTPs", textSummary) { onNavigate(SettingsPage.TEXT) }
    PreferenceRow("Per-app overrides", appSummary) { onNavigate(SettingsPage.APPS) }
    PreferenceRow("Voice presets", presetSummary) { onNavigate(SettingsPage.PRESETS) }
    PreferenceRow("Sleep timer & recent speech", utilSummary) { onNavigate(SettingsPage.UTILITIES) }
    PreferenceRow("Backup, restore & about", "Export or import JSON settings and dictionary") { onNavigate(SettingsPage.BACKUP_ABOUT) }
}

@Composable
private fun AudioSettingsPage(state: UiState, viewModel: MainScreenViewModel) {
    SectionTitle("Speed, pitch, volume & locks")
    FactorSlider("Base speed", state.baseSpeed, 0.5f..3.5f, step = 0.05f, defaultValue = 1f, onChange = viewModel::setBaseSpeed)
    SwitchRow("Lock speed", "Ignore speed requested by TalkBack or apps; always use your speed and style.", state.lockSpeed, viewModel::setLockSpeed)
    FactorSlider("Base pitch", state.basePitch, 0.5f..2f, step = 0.05f, defaultValue = 1f, onChange = viewModel::setBasePitch)
    SwitchRow("Lock pitch", "Ignore pitch changes requested by apps.", state.lockPitch, viewModel::setLockPitch)
    PercentSlider("Volume & boost", state.volumePercent, 25..200, step = 5, defaultPercent = 100, onChange = viewModel::setVolumePercent)
    SwitchRow("Lock volume", "Ignore caller volume ducking or scaling.", state.lockVolume, viewModel::setLockVolume)

    HorizontalDivider()
    SectionTitle("Clarity & silence")
    Text("Consonant presence boost with true-peak clip protection.", style = MaterialTheme.typography.bodySmall)
    RadioOptionGroup(
        options = ClarityPreset.entries,
        isSelected = { it == state.clarityPreset },
        onSelect = viewModel::setClarity,
        label = { it.label },
        summary = { it.summary }
    )
    SwitchRow("Loudness leveler", "Gently evens out quiet syllables in longer speech.", state.levelerEnabled, viewModel::setLeveler)
    SwitchRow("Trim leading silence", "Strips initial dead air on swipes and cached clips.", state.trimSilence, viewModel::setTrimSilence)

    HorizontalDivider()
    SectionTitle("Saved audio cache")
    SwitchRow(
        "Remember spoken phrases",
        "${state.cachedPhrases} phrases (${"%.1f".format(Locale.ROOT, state.cachedBytes / (1024f * 1024f))} MB cached, up to 64 MB).",
        state.cacheEnabled,
        viewModel::setCacheEnabled
    )
    OutlinedButton(
        onClick = viewModel::clearCache,
        enabled = state.cachedPhrases > 0,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    ) { Text("Clear saved audio") }
}

@Composable
private fun TextSettingsPage(state: UiState, viewModel: MainScreenViewModel) {
    val opts = state.textOptions

    SectionTitle("Numbers & OTP codes")
    SwitchRow("Read OTP & PIN codes digit by digit", "Detects codes near keywords like OTP, PIN, account, password.", opts.readCodesDigits) { v ->
        viewModel.updateTextOptions { it.copy(readCodesDigits = v) }
    }
    SwitchRow("Preserve leading zeros", "Reads 007 as 0 0 7 while leaving times and dates intact.", opts.preserveLeadingZeros) { v ->
        viewModel.updateTextOptions { it.copy(preserveLeadingZeros = v) }
    }
    SwitchRow("Indian numbering", "Reads 10,00,000, ₹5L and 2cr in lakh and crore.", opts.indianNumbering) { v ->
        viewModel.updateTextOptions { it.copy(indianNumbering = v) }
    }
    Text("Digit grouping for long numbers", style = MaterialTheme.typography.titleSmall)
    RadioOptionGroup(
        options = DigitGroupingMode.entries,
        isSelected = { it == opts.digitGrouping },
        onSelect = { mode -> viewModel.updateTextOptions { it.copy(digitGrouping = mode) } },
        label = { it.label },
        summary = { it.summary }
    )
    if (opts.digitGrouping != DigitGroupingMode.OFF) {
        IntSlider(
            label = "Minimum digits",
            value = opts.digitGroupMinLen,
            range = 2..10,
            unit = "digits",
            defaultValue = 5
        ) { len ->
            viewModel.updateTextOptions { it.copy(digitGroupMinLen = len) }
        }
    }

    HorizontalDivider()
    SectionTitle("Chat, Markdown & symbols")
    SwitchRow("Normalize fancy Unicode fonts", "Reads styled letters like 𝐇𝐞𝐥𝐥𝐨 as normal words.", opts.normalizeFancyUnicode) { v ->
        viewModel.updateTextOptions { it.copy(normalizeFancyUnicode = v) }
    }
    SwitchRow("Collapse repeated emojis & symbols", "Turns 😂😂😂😂 into 😂, 4 and ----- into a brief pause.", opts.collapseRepeats) { v ->
        viewModel.updateTextOptions { it.copy(collapseRepeats = v) }
    }
    SwitchRow("Clean Markdown formatting", "Strips **bold**, `code`, # headings, and keeps [link](url) text.", opts.cleanMarkdown) { v ->
        viewModel.updateTextOptions { it.copy(cleanMarkdown = v) }
    }
    SwitchRow("Silence formatting symbols", "Mutes decorative symbols while keeping -5, 3.14 and emails intact.", opts.silenceSymbols) { v ->
        viewModel.updateTextOptions { it.copy(silenceSymbols = v) }
    }
    if (opts.silenceSymbols) {
        OutlinedTextField(
            value = opts.silencedSymbols,
            onValueChange = { s -> viewModel.updateTextOptions { it.copy(silencedSymbols = s) } },
            label = { Text("Silenced symbols") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.updateTextOptions { it.copy(silencedSymbols = (it.silencedSymbols + TextOptions.MARKDOWN_SYMBOLS).toSet().joinToString("")) } },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("+ Markdown") }
            OutlinedButton(
                onClick = { viewModel.updateTextOptions { it.copy(silencedSymbols = (it.silencedSymbols + TextOptions.CODE_SYMBOLS).toSet().joinToString("")) } },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("+ Formatting") }
            OutlinedButton(
                onClick = { viewModel.updateTextOptions { it.copy(silencedSymbols = (it.silencedSymbols + TextOptions.BULLET_SYMBOLS).toSet().joinToString("")) } },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("+ Bullets") }
            OutlinedButton(
                onClick = { viewModel.updateTextOptions { it.copy(silencedSymbols = TextOptions.DEFAULT_SILENCED_SYMBOLS) } },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("Reset") }
        }
    }
    SwitchRow("Split CamelCase & snake_case", "Reads getUserName as get User Name and user_id as user id.", opts.splitCamelCase) { v ->
        viewModel.updateTextOptions { it.copy(splitCamelCase = v) }
    }
    SwitchRow("Shorten web links", "Reads domain and path without https://www. or tracking parameters.", opts.shortenUrls) { v ->
        viewModel.updateTextOptions { it.copy(shortenUrls = v) }
    }
    SwitchRow("Tech words & acronyms", "Pronounces ChatGPT, UPI, OTP, GPay, JSON and similar terms clearly.", opts.normalizeTechWords) { v ->
        viewModel.updateTextOptions { it.copy(normalizeTechWords = v) }
    }

    HorizontalDivider()
    var testInput by rememberSaveable { mutableStateOf("**OTP** 482910 for getUserName at https://example.com?id=1234567890 😂😂😂😂") }
    OutlinedTextField(
        value = testInput,
        onValueChange = { testInput = it },
        label = { Text("Test text processing") },
        modifier = Modifier.fillMaxWidth()
    )
    val processed = remember(testInput, opts, state.rules, state.testLocale) { viewModel.previewTextTransform(testInput) }
    Text("Output: $processed", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AppProfilesSettingsPage(state: UiState, viewModel: MainScreenViewModel) {
    var editingApp by remember { mutableStateOf<AppEntry?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.knownApps.isEmpty()) viewModel.refreshApps()
    }

    Text(
        "Give TalkBack, book readers or other apps their own speed, pitch, volume, voice, style or text preset.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(
        onClick = { viewModel.refreshApps(); showAppPicker = true },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    ) { Text("Add or pick app") }

    val configuredEntries = state.appProfiles.keys.map { pkg ->
        state.knownApps.firstOrNull { it.packageName == pkg } ?: AppEntry(pkg, pkg, false)
    }
    if (configuredEntries.isEmpty()) {
        Text("No per-app overrides yet.", style = MaterialTheme.typography.bodySmall)
    }
    configuredEntries.forEach { app ->
        val profile = state.appProfiles[app.packageName]
        val parts = if (profile != null) {
            (listOfNotNull(
                profile.voiceId,
                profile.styleId?.let { s -> ReadingStyle.resolve(s, state.customStyle).name }
            ) + formatOverrideFactors(profile.speed, profile.pitch, profile.volume) + listOfNotNull(
                profile.textPreset.takeIf { it != TextPreset.INHERIT }?.label
            )).joinToString(", ")
        } else ""
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.label + if (app.isScreenReader) " (Screen reader)" else "")
                Text(parts.ifEmpty { "Default settings" }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { editingApp = app }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Edit") }
            TextButton(
                onClick = { viewModel.setAppProfile(AppProfile(app.packageName)) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) { Text("Remove") }
        }
        HorizontalDivider()
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = state.knownApps,
            onPick = { showAppPicker = false; editingApp = it },
            onDismiss = { showAppPicker = false }
        )
    }

    editingApp?.let { app ->
        AppProfileDialog(
            app = app,
            initial = state.appProfiles[app.packageName] ?: AppProfile(app.packageName),
            voices = state.voices,
            customStyle = state.customStyle,
            onSave = { viewModel.setAppProfile(it); editingApp = null },
            onDismiss = { editingApp = null }
        )
    }
}

@Composable
private fun PresetsSettingsPage(state: UiState, viewModel: MainScreenViewModel) {
    var showSavePreset by remember { mutableStateOf(false) }

    Text(
        "Save your active voice, style, speed, pitch, volume, clarity and text rules to switch in one tap or via the Voice preset Quick Settings tile.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(onClick = { showSavePreset = true }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text("Save current setup as preset")
    }
    if (state.presets.isEmpty()) {
        Text("No presets saved yet.", style = MaterialTheme.typography.bodySmall)
    }
    state.presets.forEach { preset ->
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(preset.name + if (preset.name == state.activePresetName) " (in use)" else "")
                Text(
                    "${preset.voiceId ?: "default"} • ${formatFactor(preset.baseSpeed)} • ${preset.volumePercent}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                onClick = { viewModel.applyPreset(preset) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Apply preset ${preset.name}" }
            ) { Text("Apply") }
            TextButton(
                onClick = { viewModel.deletePreset(preset.name) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Delete preset ${preset.name}" }
            ) { Text("Delete") }
        }
        HorizontalDivider()
    }

    if (showSavePreset) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSavePreset = false },
            title = { Text("Save preset", modifier = Modifier.semantics { heading() }) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name (e.g. Fast Navigation)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.savePreset(name); showSavePreset = false },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSavePreset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun UtilitiesSettingsPage(state: UiState, viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        viewModel.refreshRecentSpeechCount()
    }
    LaunchedEffect(state.sleepStopAtMs) {
        now = System.currentTimeMillis()
        while (state.sleepStopAtMs > 0L) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }

    SectionTitle("Sleep timer")
    val sleepStatus = when {
        state.sleepStopAtMs <= 0L -> "Off."
        now >= state.sleepStopAtMs -> "Active: reading apps are paused (TalkBack still speaks)."
        else -> "Pausing reading apps ${DateUtils.getRelativeTimeSpanString(state.sleepStopAtMs, now, DateUtils.MINUTE_IN_MILLIS)} (TalkBack never pauses)."
    }
    Text(sleepStatus, style = MaterialTheme.typography.bodySmall)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { viewModel.armSleepTimer(0) },
            enabled = state.sleepStopAtMs > 0L,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "Turn off sleep timer" }
        ) { Text("Off") }
        listOf(15, 30, 45, 60).forEach { mins ->
            FilledTonalButton(
                onClick = { viewModel.armSleepTimer(mins) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "$mins minutes" }
            ) { Text("${mins}m") }
        }
    }

    HorizontalDivider()
    SectionTitle("Recent speech")
    SwitchRow(
        "Capture recent speech in memory",
        "Keeps the last 15 spoken utterances for 5 minutes (never saved to disk). Also controlled by the Recent speech Quick Settings tile.",
        state.recentSpeechEnabled,
        viewModel::setRecentSpeechEnabled
    )
    OutlinedButton(
        onClick = { context.startActivity(Intent(context, RecentSpeechActivity::class.java)) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    ) { Text("Open recent speech (${state.recentSpeechCount})") }
}

@Composable
private fun BackupAboutSettingsPage(onExportBackup: () -> Unit, onImportBackup: () -> Unit) {
    val context = LocalContext.current

    SectionTitle("Backup & restore")
    Text(
        "Export or restore all settings, per-language overrides, per-app profiles, presets and dictionary rules as one JSON file.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onExportBackup, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Export JSON") }
        OutlinedButton(onClick = onImportBackup, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Import JSON") }
    }

    HorizontalDivider()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SectionTitle("${stringResource(R.string.app_name)} $version")
    Text(
        "A text-to-speech engine for TalkBack and other apps using the on-device GenAI voices in Google's Speech Services. Speech is made on the phone; nothing leaves your device."
    )
    Text("Not made by or affiliated with Google.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, FEEDBACK_LINK.toUri())) }
    }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text("Join adev ally on WhatsApp")
    }
}

@Composable
private fun AppPickerDialog(apps: List<AppEntry>, onPick: (AppEntry) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose app", modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.packageName }) { entry ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(vertical = 10.dp)
                        ) {
                            Text(entry.label + if (entry.isScreenReader) " (Screen reader)" else "")
                            Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AppProfileDialog(
    app: AppEntry,
    initial: AppProfile,
    voices: List<VoiceChoice>,
    customStyle: ReadingStyle,
    onSave: (AppProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var voiceId by remember { mutableStateOf(initial.voiceId) }
    var styleId by remember { mutableStateOf(initial.styleId) }
    var speed by remember { mutableFloatStateOf(initial.speed) }
    var pitch by remember { mutableFloatStateOf(initial.pitch) }
    var volume by remember { mutableFloatStateOf(initial.volume) }
    var textPreset by remember { mutableStateOf(initial.textPreset) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownSelector(
                    label = "Voice: ${voiceId ?: "Global default"}",
                    options = listOf<VoiceChoice?>(null) + voices,
                    itemLabel = { it?.let { v -> describeVoice(v.id, v.gender) } ?: "Global default" },
                    onSelect = { voiceId = it?.id }
                )
                val styleName = styleId?.let { id -> ReadingStyle.resolve(id, customStyle).name } ?: "Global style"
                DropdownSelector(
                    label = "Style: $styleName",
                    options = listOf<ReadingStyle?>(null) + ReadingStyle.all(customStyle),
                    itemLabel = { it?.name ?: "Global style" },
                    onSelect = { styleId = it?.id }
                )
                DropdownSelector(
                    label = "Text rules: ${textPreset.label}",
                    options = TextPreset.entries,
                    itemLabel = { it.label },
                    onSelect = { textPreset = it }
                )
                OverrideSliders(
                    prefix = "App",
                    speed = speed,
                    pitch = pitch,
                    volume = volume,
                    onSpeedChange = { speed = it },
                    onPitchChange = { pitch = it },
                    onVolumeChange = { volume = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(AppProfile(app.packageName, voiceId, styleId, speed, pitch, volume, textPreset)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!initial.isDefault) {
                    TextButton(onClick = { onSave(AppProfile(app.packageName)) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
